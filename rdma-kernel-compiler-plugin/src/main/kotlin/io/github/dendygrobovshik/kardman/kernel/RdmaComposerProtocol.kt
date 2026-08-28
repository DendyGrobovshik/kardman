package io.github.dendygrobovshik.kardman.kernel

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/**
 * The subset of the `androidx.compose.runtime.Composer` interface that the host-side
 * runtime exposes to the plugin ("base protocol").
 *
 * Everything outside this list must be rejected by the plugin compiler plugin (see
 * `ComposeAllowlist` in `rdma-plugin-compiler-plugin`); the plugin therefore can only
 * rely on structural groups (`if`/`for`/`when`), `remember` (with keys), `changed`/skip
 * and state. `movableContent`/`derivedStateOf`/`snapshotFlow`/effects are forbidden.
 *
 * The table is cross-checked against the actual `Composer` IR at build time so that a
 * compose-runtime upgrade that changes the interface fails fast (version-locked proxy).
 */
enum class ComposerReturnKind {
    /** `CallVoidMethod`, return `undefined`. */
    VOID,

    /** `CallBooleanMethod`, return `jsi::Value(bool)`. */
    BOOLEAN,

    /** `CallObjectMethod` returning `Composer` (`this`); proxy returns itself. */
    COMPOSER_SELF,

    /** `CallObjectMethod` returning `ScopeUpdateScope`; wrap in a scope proxy. */
    SCOPE_UPDATE_SCOPE,

    /** `rememberedValue` — unboxes Empty / state / JsValueHolder / primitives. */
    REMEMBERED_VALUE,

    /** `updateRememberedValue` — stores state proxies / JS objects / primitives. */
    UPDATE_REMEMBERED_VALUE,

    /** `changed` — boxes the JS key before comparing. */
    CHANGED,

    /** Diagnostics-only, not forwarded to JNI; returns `undefined`. */
    NOOP,

    /** Diagnostics-only, not forwarded to JNI; returns `null`. */
    NOOP_NULL,
}

enum class ComposerParamKind {
    /** JVM `int`, read from `args[i].getNumber()`. */
    INT,

    /** JVM `boolean`, read from `args[i].getBool()`. */
    BOOLEAN,

    /** JVM `Object` (`Any?`), boxed from JSI via `boxJsi`. */
    OBJECT,
}

data class ComposerMethod(
    /** Property name the JS side uses to look up the method on the proxy. */
    val jsName: String,

    /** JVM method name on `androidx.compose.runtime.Composer`. */
    val jniName: String,

    /** Full JNI signature `"(params)return"`. */
    val jniSignature: String,

    val returnKind: ComposerReturnKind,
    val params: List<ComposerParamKind> = emptyList(),
) {
    val needsMethodId: Boolean get() = returnKind != ComposerReturnKind.NOOP && returnKind != ComposerReturnKind.NOOP_NULL
    val arity: Int get() = params.size
}

object RdmaComposerProtocol {
    private const val COMPOSER_FQN = "androidx.compose.runtime.Composer"

    val baseProtocol: List<ComposerMethod> = listOf(
        // Restart groups
        ComposerMethod(
            jsName = "startRestartGroup",
            jniName = "startRestartGroup",
            jniSignature = "(I)Landroidx/compose/runtime/Composer;",
            returnKind = ComposerReturnKind.COMPOSER_SELF,
            params = listOf(ComposerParamKind.INT),
        ),
        ComposerMethod(
            jsName = "endRestartGroup",
            jniName = "endRestartGroup",
            jniSignature = "()Landroidx/compose/runtime/ScopeUpdateScope;",
            returnKind = ComposerReturnKind.SCOPE_UPDATE_SCOPE,
        ),
        // Replace groups (`if` / `when`)
        ComposerMethod(
            jsName = "startReplaceGroup",
            jniName = "startReplaceGroup",
            jniSignature = "(I)V",
            returnKind = ComposerReturnKind.VOID,
            params = listOf(ComposerParamKind.INT),
        ),
        ComposerMethod(
            jsName = "endReplaceGroup",
            jniName = "endReplaceGroup",
            jniSignature = "()V",
            returnKind = ComposerReturnKind.VOID,
        ),
        // Movable groups (`for` + `key(...)`)
        ComposerMethod(
            jsName = "startMovableGroup",
            jniName = "startMovableGroup",
            jniSignature = "(ILjava/lang/Object;)V",
            returnKind = ComposerReturnKind.VOID,
            params = listOf(ComposerParamKind.INT, ComposerParamKind.OBJECT),
        ),
        ComposerMethod(
            jsName = "endMovableGroup",
            jniName = "endMovableGroup",
            jniSignature = "()V",
            returnKind = ComposerReturnKind.VOID,
        ),
        // Reusable groups
        ComposerMethod(
            jsName = "startReusableGroup",
            jniName = "startReusableGroup",
            jniSignature = "(ILjava/lang/Object;)V",
            returnKind = ComposerReturnKind.VOID,
            params = listOf(ComposerParamKind.INT, ComposerParamKind.OBJECT),
        ),
        ComposerMethod(
            jsName = "endReusableGroup",
            jniName = "endReusableGroup",
            jniSignature = "()V",
            returnKind = ComposerReturnKind.VOID,
        ),
        // Skip
        ComposerMethod(
            jsName = "skipCurrentGroup",
            jniName = "skipCurrentGroup",
            jniSignature = "()V",
            returnKind = ComposerReturnKind.VOID,
        ),
        ComposerMethod(
            jsName = "skipToGroupEnd",
            jniName = "skipToGroupEnd",
            jniSignature = "()V",
            returnKind = ComposerReturnKind.VOID,
        ),
        // Remember
        ComposerMethod(
            jsName = "rememberedValue",
            jniName = "rememberedValue",
            jniSignature = "()Ljava/lang/Object;",
            returnKind = ComposerReturnKind.REMEMBERED_VALUE,
        ),
        ComposerMethod(
            jsName = "updateRememberedValue",
            jniName = "updateRememberedValue",
            jniSignature = "(Ljava/lang/Object;)V",
            returnKind = ComposerReturnKind.UPDATE_REMEMBERED_VALUE,
            params = listOf(ComposerParamKind.OBJECT),
        ),
        ComposerMethod(
            jsName = "changed",
            jniName = "changed",
            jniSignature = "(Ljava/lang/Object;)Z",
            returnKind = ComposerReturnKind.CHANGED,
            params = listOf(ComposerParamKind.OBJECT),
        ),
        ComposerMethod(
            jsName = "shouldExecute",
            jniName = "shouldExecute",
            jniSignature = "(ZI)Z",
            returnKind = ComposerReturnKind.BOOLEAN,
            params = listOf(ComposerParamKind.BOOLEAN, ComposerParamKind.INT),
        ),
        // Source information / recompose scope (diagnostics-only no-ops)
        ComposerMethod(
            jsName = "sourceInformation",
            jniName = "sourceInformation",
            jniSignature = "(Ljava/lang/String;)V",
            returnKind = ComposerReturnKind.NOOP,
        ),
        ComposerMethod(
            jsName = "sourceInformationMarkerStart",
            jniName = "sourceInformationMarkerStart",
            jniSignature = "(ILjava/lang/String;)V",
            returnKind = ComposerReturnKind.NOOP,
        ),
        ComposerMethod(
            jsName = "sourceInformationMarkerEnd",
            jniName = "sourceInformationMarkerEnd",
            jniSignature = "()V",
            returnKind = ComposerReturnKind.NOOP,
        ),
        ComposerMethod(
            jsName = "get_recomposeScope",
            jniName = "getRecomposeScope",
            jniSignature = "()Landroidx/compose/runtime/RecomposeScope;",
            returnKind = ComposerReturnKind.NOOP_NULL,
        ),
        ComposerMethod(
            jsName = "recordUsed",
            jniName = "recordUsed",
            jniSignature = "(Landroidx/compose/runtime/RecomposeScope;)V",
            returnKind = ComposerReturnKind.NOOP,
        ),
    )

    /** Methods that require a cached `jmethodID` (everything except the no-op diagnostics). */
    val methodsNeedingCache: List<ComposerMethod> = baseProtocol.filter { it.needsMethodId }

    /**
     * Cross-checks the base protocol against the `Composer` interface resolved from the
     * compose-runtime dependency. Returns a human-readable list of mismatches (empty = OK).
     */
    @Suppress("DEPRECATION")
    fun validateAgainst(context: IrPluginContext): List<String> {
        val composer = context.referenceClass(ClassId.topLevel(FqName(COMPOSER_FQN)))?.owner
            ?: return listOf("Cannot resolve $COMPOSER_FQN from the compose runtime dependency")
        val byName = composer.functions.groupBy { it.name.asString() }
        val errors = mutableListOf<String>()
        for (m in methodsNeedingCache) {
            if (m.jniName !in byName) {
                errors += "Composer.${m.jniName}${m.jniSignature} is missing from the resolved Compose interface (compose-runtime version drift?)"
            }
        }
        return errors
    }
}
