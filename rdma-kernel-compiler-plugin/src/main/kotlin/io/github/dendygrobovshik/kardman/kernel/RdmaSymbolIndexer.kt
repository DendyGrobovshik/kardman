/*
 * Copyright 2026 DendyGrobovshik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.dendygrobovshik.kardman.kernel

import io.github.dendygrobovshik.kardman.types.RdmaDeclaration
import io.github.dendygrobovshik.kardman.types.RdmaParameterSignature
import io.github.dendygrobovshik.kardman.types.RdmaPolyfill
import io.github.dendygrobovshik.kardman.types.RdmaSourceRange
import io.github.dendygrobovshik.kardman.types.RdmaSymbolKind
import io.github.dendygrobovshik.kardman.types.RdmaUse
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.PsiIrFileEntry
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrExternalPackageFragment
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.FqName
import java.io.File
import java.util.IdentityHashMap

data class RdmaIndexResult(
    val declarations: List<RdmaDeclaration>,
    val uses: List<RdmaUse>,
    val polyfills: List<RdmaPolyfill> = emptyList(),
)

/**
 * Walks every real source declaration in the current module and produces a content-hashed,
 * member-granular index plus a usage graph. The graph is emitted at "polyfill-node" granularity:
 * class members collapse into their enclosing class, so a node is either a top-level
 * function/property or a whole class. This is the raw material for change detection (Phase 1)
 * and the dirty subgraph (Phase 2).
 */
object RdmaSymbolIndexer {
    private val RDMA_ANNOTATION = FqName("io.github.dendygrobovshik.kardman.RDMA")
    private val COMPOSABLE_ANNOTATION = FqName("androidx.compose.runtime.Composable")
    private val DEPRECATED_ANNOTATION = FqName("kotlin.Deprecated")
    private val RUNTIME_LOCAL_ANNOTATION = FqName("io.github.dendygrobovshik.kardman.RuntimeLocal")

    /**
     * Packages of other modules whose code also exists in the plugin runtime (Kotlin/JS), so a
     * kernel declaration may call them and still compile to JS. Anything else outside the
     * module (platform SDKs, JVM/Native-only libraries) makes the caller non-emulatable unless
     * it has a manual polyfill (compatibility.md §5.2). The materializer's JS compile stays the
     * final judge: a call that slips through here fails the dynamic release, it never ships.
     */
    private val JS_AVAILABLE_PREFIXES = listOf("kotlin.", "kotlinx.coroutines.", "androidx.compose.runtime.")
    private val JS_UNAVAILABLE_PREFIXES = listOf(
        "kotlin.native.", "kotlin.concurrent.", "kotlin.system.", "kotlin.reflect.full.", "kotlin.reflect.jvm.",
    )
    private val JS_AVAILABLE_JVM_PREFIXES = listOf("kotlin.jvm.functions.", "kotlin.jvm.internal.")

    /**
     * JVM classes behind the Kotlin stdlib's `typealias`es (`kotlin.IllegalStateException`,
     * `kotlin.collections.ArrayList`, …): the JVM IR names the Java class, but the code is plain
     * Kotlin and exists in JS too.
     */
    private val JVM_STDLIB_ALIASES = setOf(
        "java.lang.Error", "java.lang.Exception", "java.lang.RuntimeException", "java.lang.Throwable",
        "java.lang.IllegalArgumentException", "java.lang.IllegalStateException",
        "java.lang.IndexOutOfBoundsException", "java.lang.UnsupportedOperationException",
        "java.lang.ArithmeticException", "java.lang.NumberFormatException", "java.lang.NullPointerException",
        "java.lang.ClassCastException", "java.lang.AssertionError", "java.lang.StringBuilder",
        "java.lang.Appendable", "java.lang.Comparable", "java.lang.CharSequence", "java.lang.Number",
        "java.lang.Object", "java.lang.String", "java.lang.Iterable", "java.lang.Enum",
        "java.util.NoSuchElementException", "java.util.ConcurrentModificationException", "java.util.Comparator",
        "java.util.RandomAccess", "java.util.ArrayList", "java.util.HashMap", "java.util.LinkedHashMap",
        "java.util.HashSet", "java.util.LinkedHashSet", "java.util.AbstractList", "java.util.AbstractMap",
        "java.util.Collection", "java.util.List", "java.util.Map", "java.util.Set", "java.util.Iterator",
        "java.util.concurrent.CancellationException", "java.nio.charset.CharacterCodingException",
    )
    private val POLYFILL_TARGET_REGEX = Regex("@Polyfill\\s*\\(\\s*`?for`?\\s*=\\s*\"([^\"]+)\"")

    fun index(moduleFragment: IrModuleFragment): RdmaIndexResult {
        val declarations = mutableListOf<RdmaDeclaration>()
        val polyfills = mutableListOf<RdmaPolyfill>()
        val irToNode = IdentityHashMap<IrDeclaration, String>()
        val sources = IdentityHashMap<IrFile, String>()

        for (file in moduleFragment.files) {
            val text = readSource(file) ?: continue
            sources[file] = text
            for (decl in file.declarations) {
                indexDeclaration(decl, text, file.fileEntry.name, enclosingRdma = false, memberOfClass = false, declarations, polyfills, irToNode)
            }
        }

        val uses = mutableListOf<RdmaUse>()
        for (file in moduleFragment.files) {
            for (decl in file.declarations) {
                val from = irToNode[decl] ?: continue
                collectUses(from, decl, irToNode, uses)
            }
        }

        // A symbol with a manual polyfill is emulatable whatever it calls (compatibility.md §6.3).
        val polyfilled = polyfills.map { it.target }.toSet()
        val resolved = declarations.map { if (it.fqn in polyfilled && !it.emulatable) it.copy(emulatable = true) else it }
        return RdmaIndexResult(resolved, uses, polyfills)
    }

    private fun readSource(file: IrFile): String? {
        val name = file.fileEntry.name
        if (name.replace('\\', '/').contains("/build/generated/")) return null
        val psiText = (file.fileEntry as? PsiIrFileEntry)?.psiFile?.text
        if (psiText != null) return psiText
        return try {
            File(name).takeIf { it.isFile }?.readText()
        } catch (e: Exception) {
            null
        }
    }

    /** Returns the `for = "…"` target of a `@Polyfill` annotation, or null if not annotated. */
    private fun readPolyfillTarget(text: String, start: Int, end: Int): String? {
        // The @Polyfill annotation sits immediately before the function declaration, so look
        // backwards from the declaration start to cover it.
        val head = text.substring(maxOf(0, start - 256), minOf(text.length, end))
        return POLYFILL_TARGET_REGEX.find(head)?.groupValues?.get(1)
    }

    /** Regular (non-synthetic) value parameters of a callable, in declaration order. */
    private fun functionParameters(fn: IrSimpleFunction): List<IrValueParameter> =
        fn.parameters.filter { it.kind == IrParameterKind.Regular && !it.name.asString().startsWith("$") }

    /**
     * Why a changed top-level `@RDMA` function cannot be hotfixed yet: its native call sites
     * have no slot (runtime_rework §6.2, §6.8). Null when it can.
     */
    private fun hotfixBlockerOf(fn: IrSimpleFunction, isRdma: Boolean): String? = when {
        !isRdma -> null
        fn.hasAnnotation(COMPOSABLE_ANNOTATION) -> "it is a @Composable widget"
        fn.isSuspend -> "it is a suspend function"
        (functionParameters(fn).map { it.type } + fn.returnType).any(::containsFunctionType) ->
            "it has a function-typed parameter or result"
        else -> null
    }

    private fun containsFunctionType(type: IrType): Boolean {
        val fqn = type.classFqName?.asString() ?: return false
        if (fqn.startsWith("kotlin.Function") || fqn.startsWith("kotlin.jvm.functions.Function") ||
            fqn.startsWith("kotlin.coroutines.SuspendFunction") || fqn.startsWith("kotlin.reflect.KFunction")
        ) return true
        return (type as? IrSimpleType)?.arguments?.any { it.typeOrNull?.let(::containsFunctionType) == true } == true
    }

    private fun constructorParameters(cls: IrClass): List<IrValueParameter> =
        cls.primaryConstructor?.parameters?.filter { it.kind == IrParameterKind.Regular } ?: emptyList()

    private fun signatureOf(params: List<IrValueParameter>, sourceText: String?): List<RdmaParameterSignature> =
        params.map { RdmaParameterSignature(it.name.asString(), signatureType(it.type), it.defaultValue != null, defaultValueText(it, sourceText)) }

    /**
     * A function's signature. The Compose compiler runs first and clears `defaultValue` on a
     * `@Composable` function's parameters, so defaults are also read from the source (as the API
     * extractor does); otherwise a widget's defaulted parameters look required and appending one
     * is refused as an incompatible change.
     */
    private fun functionSignatureOf(fn: IrSimpleFunction, sourceText: String): List<RdmaParameterSignature> {
        val fromSource = RdmaDefaultSupport.defaultValuesFromFunctionSource(sourceText, fn.startOffset, fn.endOffset)
        return signatureOf(functionParameters(fn), sourceText).mapIndexed { i, sig ->
            val text = fromSource[i] ?: return@mapIndexed sig
            sig.copy(hasDefault = true, defaultValue = sig.defaultValue ?: text)
        }
    }

    private fun defaultValueText(p: IrValueParameter, sourceText: String?): String? {
        val d = p.defaultValue ?: return null
        if (sourceText == null || d.startOffset < 0 || d.endOffset < d.startOffset || d.endOffset > sourceText.length) return null
        return sourceText.substring(d.startOffset, d.endOffset).trim()
    }

    /** A stable type descriptor for the signature classifier (primitives by FQN, lists/maps nested). */
    private fun signatureType(t: IrType): String {
        val fqn = t.classFqName?.asString()
        if (fqn == "kotlin.collections.List" || fqn == "kotlin.collections.MutableList") {
            val elem = (t as? IrSimpleType)?.arguments?.firstOrNull()?.typeOrNull ?: return "list:kotlin.Any"
            return "list:" + signatureType(elem)
        }
        if (fqn == "kotlin.collections.Map" || fqn == "kotlin.collections.MutableMap") {
            val args = (t as? IrSimpleType)?.arguments?.mapNotNull { it.typeOrNull } ?: emptyList()
            val key = args.getOrNull(0) ?: return "map:kotlin.Any:kotlin.Any"
            val value = args.getOrNull(1) ?: return "map:${signatureType(key)}:kotlin.Any"
            return "map:" + signatureType(key) + ":" + signatureType(value)
        }
        return fqn ?: "kotlin.Any"
    }

    /**
     * External declarations [root] references that do not exist in the plugin runtime (see
     * [JS_AVAILABLE_PREFIXES]); sorted, deduplicated. `@RDMA` symbols of other kernel modules are
     * reachable from JS through `RDMA.*` and do not count.
     */
    private fun nativeRefs(root: IrElement): List<String> {
        val refs = sortedSetOf<String>()
        val visitor = object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitCall(expression: IrCall) {
                expression.acceptChildrenVoid(this)
                nativeRefName(expression.symbol.owner)?.let(refs::add)
            }

            override fun visitConstructorCall(expression: IrConstructorCall) {
                expression.acceptChildrenVoid(this)
                nativeRefName(expression.symbol.owner)?.let(refs::add)
            }

            override fun visitGetObjectValue(expression: IrGetObjectValue) {
                expression.acceptChildrenVoid(this)
                nativeRefName(expression.symbol.owner)?.let(refs::add)
            }
        }
        root.acceptChildrenVoid(visitor)
        return refs.toList()
    }

    /** A class: its supertypes, `init` blocks and constructors (members are indexed on their own). */
    private fun classNativeRefs(cls: IrClass): List<String> {
        val refs = sortedSetOf<String>()
        for (superType in cls.superTypes) {
            superType.classOrNull?.owner?.let { nativeRefName(it) }?.let(refs::add)
        }
        for (member in cls.declarations) {
            if (member is IrAnonymousInitializer || member is IrConstructor) refs += nativeRefs(member)
        }
        return refs.toList()
    }

    private fun nativeRefName(decl: IrDeclaration): String? {
        val fragment = runCatching { decl.getPackageFragment() }.getOrNull() ?: return null
        if (fragment !is IrExternalPackageFragment) return null
        if (isRdmaVisible(decl)) return null
        val owner = (decl as? IrConstructor)?.parentClassOrNull ?: decl
        val fqn = (owner as? IrDeclarationWithName)?.fqNameWhenAvailable?.asString() ?: return null
        if (JS_AVAILABLE_JVM_PREFIXES.any(fqn::startsWith)) return null
        val ownerClass = ((owner as? IrClass) ?: owner.parentClassOrNull)?.fqNameWhenAvailable?.asString()
        if (ownerClass in JVM_STDLIB_ALIASES) return null
        if (fqn.startsWith("kotlin.jvm.")) return fqn
        if (JS_UNAVAILABLE_PREFIXES.any(fqn::startsWith)) return fqn
        if (JS_AVAILABLE_PREFIXES.any(fqn::startsWith)) return null
        return fqn
    }

    private fun isRdmaVisible(decl: IrDeclaration): Boolean {
        if (decl.hasAnnotation(RDMA_ANNOTATION)) return true
        val property = (decl as? IrSimpleFunction)?.correspondingPropertySymbol?.owner
        if (property?.hasAnnotation(RDMA_ANNOTATION) == true) return true
        var cls = (decl as? IrClass) ?: decl.parentClassOrNull
        while (cls != null) {
            if (cls.hasAnnotation(RDMA_ANNOTATION)) return true
            cls = cls.parentClassOrNull
        }
        return false
    }

    /**
     * Emulatable unless it calls code that does not exist in the plugin runtime. A `@Composable`
     * function follows the same rule in principle (compatibility.md §5.2), but the materializer
     * cannot build widget polyfills yet, so a widget still anchors the floor: a plugin must not
     * be told it runs on a host that could never receive the widget.
     */
    private fun emulatableOf(fn: IrSimpleFunction, nativeRefs: List<String>): Boolean =
        nativeRefs.isEmpty() && !fn.hasAnnotation(COMPOSABLE_ANNOTATION)

    /** The property's value type FQN (e.g. `kotlin.Int`), for the runtime-local exemption. */
    private fun propertyValueType(p: IrProperty): String? {
        val type = p.getter?.returnType ?: p.backingField?.type ?: return null
        val cls = type.classOrNull?.owner
        val fqn = type.classFqName?.asString() ?: return null
        return if (cls?.kind == ClassKind.ENUM_CLASS) "enum:$fqn" else fqn
    }

    private fun indexDeclaration(
        decl: IrDeclaration,
        text: String,
        path: String,
        enclosingRdma: Boolean,
        memberOfClass: Boolean,
        out: MutableList<RdmaDeclaration>,
        polyfills: MutableList<RdmaPolyfill>,
        irToNode: MutableMap<IrDeclaration, String>,
    ) {
        if (decl.origin != IrDeclarationOrigin.DEFINED) return
        val start = decl.startOffset
        if (start < 0) return

        when (decl) {
            is IrClass -> {
                val selfRdma = enclosingRdma || decl.hasAnnotation(RDMA_ANNOTATION)
                val end = decl.endOffset
                if (end < start) return
                val headerEnd = minOf(classHeaderEnd(text, start), end)
                val fqn = decl.fqNameWhenAvailable?.asString() ?: decl.name.asString()
                out += RdmaDeclaration(
                    fqn = fqn,
                    kind = RdmaSymbolKind.CLASS,
                    hash = RdmaContentHasher.sha256(text.substring(start, headerEnd)),
                    isRdma = selfRdma,
                    sourceRange = RdmaSourceRange(path, start, end),
                    deprecated = decl.hasAnnotation(DEPRECATED_ANNOTATION),
                    signature = signatureOf(constructorParameters(decl), text),
                    emulatable = classNativeRefs(decl).isEmpty(),
                    nativeRefs = classNativeRefs(decl),
                    isObject = decl.kind == ClassKind.OBJECT,
                    runtimeLocal = decl.hasAnnotation(RUNTIME_LOCAL_ANNOTATION),
                )
                irToNode[decl] = fqn
                for (member in decl.declarations) {
                    indexMember(member, text, path, selfRdma, fqn, out, irToNode)
                }
            }

            is IrSimpleFunction -> {
                if (decl.isExternal) return
                val end = decl.endOffset
                if (end < start) return
                val fqn = decl.fqNameWhenAvailable?.asString() ?: decl.name.asString()
                // A `@Polyfill` function is not public API: it is recorded for the materializer
                // but excluded from the hashed declarations and the usage graph (§6.3).
                if (!memberOfClass) {
                    val target = readPolyfillTarget(text, start, end)
                    if (target != null) {
                        polyfills += RdmaPolyfill(target, fqn, RdmaSourceRange(path, start, end))
                        return
                    }
                }
                val isRdma = enclosingRdma || decl.hasAnnotation(RDMA_ANNOTATION)
                out += RdmaDeclaration(
                    fqn = fqn,
                    kind = if (memberOfClass) RdmaSymbolKind.CLASS_METHOD else RdmaSymbolKind.TOP_LEVEL_FUNCTION,
                    hash = RdmaContentHasher.sha256(text.substring(start, end)),
                    isRdma = isRdma,
                    sourceRange = RdmaSourceRange(path, start, end),
                    emulatable = emulatableOf(decl, nativeRefs(decl)),
                    nativeRefs = nativeRefs(decl),
                    deprecated = decl.hasAnnotation(DEPRECATED_ANNOTATION),
                    signature = functionSignatureOf(decl, text),
                    hotfixBlocker = if (memberOfClass) null else hotfixBlockerOf(decl, isRdma),
                )
                irToNode[decl] = fqn
            }

            is IrProperty -> {
                val end = decl.endOffset
                if (end < start) return
                val isRdma = enclosingRdma || decl.hasAnnotation(RDMA_ANNOTATION)
                val fqn = decl.fqNameWhenAvailable?.asString() ?: decl.name.asString()
                out += RdmaDeclaration(
                    fqn = fqn,
                    kind = if (memberOfClass) RdmaSymbolKind.CLASS_PROPERTY else RdmaSymbolKind.TOP_LEVEL_PROPERTY,
                    hash = RdmaContentHasher.sha256(text.substring(start, end)),
                    isRdma = isRdma,
                    sourceRange = RdmaSourceRange(path, start, end),
                    isMutable = decl.isVar,
                    emulatable = nativeRefs(decl).isEmpty(),
                    nativeRefs = nativeRefs(decl),
                    valueType = propertyValueType(decl),
                    hasField = decl.backingField != null,
                    runtimeLocal = decl.hasAnnotation(RUNTIME_LOCAL_ANNOTATION),
                )
                irToNode[decl] = fqn
            }

            else -> {}
        }
    }

    private fun indexMember(
        decl: IrDeclaration,
        text: String,
        path: String,
        enclosingRdma: Boolean,
        containerFqn: String,
        out: MutableList<RdmaDeclaration>,
        irToNode: MutableMap<IrDeclaration, String>,
    ) {
        if (decl.origin != IrDeclarationOrigin.DEFINED) return
        val start = decl.startOffset
        if (start < 0) return

        when (decl) {
            is IrClass -> {
                val selfRdma = enclosingRdma || decl.hasAnnotation(RDMA_ANNOTATION)
                val end = decl.endOffset
                if (end < start) return
                val headerEnd = minOf(classHeaderEnd(text, start), end)
                val fqn = "${containerFqn}.${decl.name.asString()}"
                out += RdmaDeclaration(
                    fqn = fqn,
                    kind = RdmaSymbolKind.CLASS,
                    hash = RdmaContentHasher.sha256(text.substring(start, headerEnd)),
                    isRdma = selfRdma,
                    sourceRange = RdmaSourceRange(path, start, end),
                    deprecated = decl.hasAnnotation(DEPRECATED_ANNOTATION),
                    signature = signatureOf(constructorParameters(decl), text),
                    emulatable = classNativeRefs(decl).isEmpty(),
                    nativeRefs = classNativeRefs(decl),
                    isObject = decl.kind == ClassKind.OBJECT,
                    runtimeLocal = decl.hasAnnotation(RUNTIME_LOCAL_ANNOTATION),
                )
                irToNode[decl] = fqn
                for (member in decl.declarations) {
                    indexMember(member, text, path, selfRdma, fqn, out, irToNode)
                }
            }

            is IrSimpleFunction -> {
                if (decl.isExternal) return
                val end = decl.endOffset
                if (end < start) return
                val fqn = "${containerFqn}.${decl.name.asString()}"
                out += RdmaDeclaration(
                    fqn = fqn,
                    kind = RdmaSymbolKind.CLASS_METHOD,
                    hash = RdmaContentHasher.sha256(text.substring(start, end)),
                    isRdma = enclosingRdma,
                    sourceRange = RdmaSourceRange(path, start, end),
                    emulatable = emulatableOf(decl, nativeRefs(decl)),
                    nativeRefs = nativeRefs(decl),
                    deprecated = decl.hasAnnotation(DEPRECATED_ANNOTATION),
                    signature = functionSignatureOf(decl, text),
                )
                irToNode[decl] = containerFqn
            }

            is IrProperty -> {
                val end = decl.endOffset
                if (end < start) return
                val fqn = "${containerFqn}.${decl.name.asString()}"
                out += RdmaDeclaration(
                    fqn = fqn,
                    kind = RdmaSymbolKind.CLASS_PROPERTY,
                    hash = RdmaContentHasher.sha256(text.substring(start, end)),
                    isRdma = enclosingRdma,
                    sourceRange = RdmaSourceRange(path, start, end),
                    isMutable = decl.isVar,
                    emulatable = nativeRefs(decl).isEmpty(),
                    nativeRefs = nativeRefs(decl),
                    valueType = propertyValueType(decl),
                    hasField = decl.backingField != null,
                    runtimeLocal = decl.hasAnnotation(RUNTIME_LOCAL_ANNOTATION),
                )
                irToNode[decl] = containerFqn
            }

            else -> {}
        }
    }

    private fun collectUses(
        from: String,
        root: IrElement,
        irToNode: Map<IrDeclaration, String>,
        out: MutableList<RdmaUse>,
    ) {
        val seen = mutableSetOf<String>()
        val visitor = object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitCall(expression: IrCall) {
                expression.acceptChildrenVoid(this)
                record(expression.symbol.owner)
            }

            override fun visitConstructorCall(expression: IrConstructorCall) {
                expression.acceptChildrenVoid(this)
                record(expression.symbol.owner.parent as? IrClass)
            }

            override fun visitClassReference(expression: IrClassReference) {
                expression.acceptChildrenVoid(this)
                record(expression.symbol.owner as? IrClass)
            }

            override fun visitGetField(expression: IrGetField) {
                expression.acceptChildrenVoid(this)
                val field = expression.symbol.owner
                record(field.correspondingPropertySymbol?.owner ?: field.parent as? IrClass)
            }

            override fun visitSetField(expression: IrSetField) {
                expression.acceptChildrenVoid(this)
                val field = expression.symbol.owner
                record(field.correspondingPropertySymbol?.owner ?: field.parent as? IrClass)
            }

            override fun visitGetObjectValue(expression: IrGetObjectValue) {
                expression.acceptChildrenVoid(this)
                record(expression.symbol.owner)
            }

            private fun record(target: IrDeclaration?) {
                if (target == null) return
                // A property is reached through its accessors; the index keys the property.
                val decl = (target as? IrSimpleFunction)?.correspondingPropertySymbol?.owner ?: target
                val node = irToNode[decl] ?: return
                if (node != from && seen.add(node)) {
                    out += RdmaUse(from, node)
                }
            }
        }
        root.acceptChildrenVoid(visitor)
    }
}
