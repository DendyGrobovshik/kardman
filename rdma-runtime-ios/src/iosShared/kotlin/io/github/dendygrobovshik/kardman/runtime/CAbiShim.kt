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
@file:Suppress("FunctionName", "UNUSED_PARAMETER", "unused")
@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.experimental.ExperimentalNativeApi::class,
    androidx.compose.runtime.InternalComposeApi::class,
    kotlin.ExperimentalStdlibApi::class,
)

package io.github.dendygrobovshik.kardman.runtime

import androidx.compose.runtime.Composer
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ScopeUpdateScope
import androidx.compose.runtime.cache
import androidx.compose.runtime.mutableStateOf
import kotlin.native.EagerInitialization
import kotlinx.cinterop.Arena
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value

/**
 * The Kotlin/Native C-ABI shim surface: the set of C-compatible functions the iOS
 * C++ backend calls. Each function is registered in the C++ function-pointer
 * registry at startup (see [_register]); objects cross as StableRef handles
 * (`COpaquePointer`); boxed values (`Any?`) cross as handles and are
 * classified/unboxed via [rdma_classifyValue] + the `rdma_valueAs*` getters.
 *
 * The Composer never crosses the boundary: every composer-taking operation reads
 * the currently-composing composer from [rdmaGetCurrentComposer].
 */

private fun <T : Any> T.handle(): COpaquePointer = StableRef.create(this).asCPointer()

private inline fun <reified T : Any> COpaquePointer.obj(): T = asStableRef<Any>().get() as T

fun rdma_disposeStableRef(handle: COpaquePointer) {
    handle.asStableRef<Any>().dispose()
}

// ------------------------------------------------------------- value boxing

fun rdma_boxInt(v: Int): COpaquePointer = StableRef.create(v as Any).asCPointer()

fun rdma_boxLong(v: Long): COpaquePointer = StableRef.create(v as Any).asCPointer()

fun rdma_boxDouble(v: Double): COpaquePointer = StableRef.create(v as Any).asCPointer()

fun rdma_boxFloat(v: Float): COpaquePointer = StableRef.create(v as Any).asCPointer()

fun rdma_boxBoolean(v: Boolean): COpaquePointer = StableRef.create(v as Any).asCPointer()

fun rdma_boxString(v: CPointer<ByteVar>?): COpaquePointer =
    StableRef.create(v?.toKString() ?: "").asCPointer()

fun rdma_boxObject(): COpaquePointer = StableRef.create(Any()).asCPointer()

fun rdma_boxJsValueHolder(id: Long): COpaquePointer = StableRef.create(JsValueHolder(id)).asCPointer()

// ------------------------------------------------- classification + unboxing

fun rdma_classifyValue(handle: COpaquePointer?): Int {
    val v = handle?.obj<Any>()
    return when (v) {
        null -> 0
        is MutableState<*> -> 1
        is JsValueHolder -> 2
        is Int -> 3
        is Long -> 4
        is Double -> 5
        is Float -> 6
        is Boolean -> 7
        is String -> 8
        Composer.Empty -> 10
        else -> 9
    }
}

fun rdma_valueAsInt(handle: COpaquePointer): Int = handle.obj<Any>() as Int

fun rdma_valueAsLong(handle: COpaquePointer): Long = handle.obj<Any>() as Long

fun rdma_valueAsDouble(handle: COpaquePointer): Double = handle.obj<Any>() as Double

fun rdma_valueAsFloat(handle: COpaquePointer): Float = handle.obj<Any>() as Float

fun rdma_valueAsBoolean(handle: COpaquePointer): Boolean = handle.obj<Any>() as Boolean

// Arena for returned C strings. The C++ side copies the bytes immediately, so the
// arena (valid until the next clear/GC) is sufficient; it is never cleared for the
// demo lifetime.
private val cstringArena = Arena()

/** Converts a Kotlin String to a NUL-terminated UTF-8 C string (valid until the next arena clear). */
fun rdmaStringToCStr(s: String): CPointer<ByteVar>? = s.cstr.getPointer(cstringArena)

/** Converts a C string back to a Kotlin String (null becomes the empty string). */
fun rdmaToKString(cstr: CPointer<ByteVar>?): String = cstr?.toKString() ?: ""

fun rdma_valueAsString(handle: COpaquePointer): CPointer<ByteVar>? =
    rdmaStringToCStr(handle.obj<Any>() as String)

fun rdma_valueAsJsValueId(handle: COpaquePointer): Long = (handle.obj<Any>() as JsValueHolder).id

// ---------------------------------------------------------- array / list access

fun rdma_arraySize(arrayHandle: COpaquePointer): Int = arrayHandle.obj<Array<Any?>>().size

fun rdma_arrayGet(arrayHandle: COpaquePointer, index: Int): COpaquePointer? {
    val elem = arrayHandle.obj<Array<Any?>>()[index]
    return elem?.handle()
}

fun rdma_listSize(listHandle: COpaquePointer): Int = listHandle.obj<List<Any?>>().size

fun rdma_listGet(listHandle: COpaquePointer, index: Int): COpaquePointer? {
    val elem = listHandle.obj<List<Any?>>()[index]
    return elem?.handle()
}

fun rdma_listCreate(handles: CPointer<LongVar>?, count: Int): COpaquePointer {
    val list = ArrayList<Any?>(count)
    var p = handles
    for (i in 0 until count) {
        val raw = p?.pointed?.value ?: 0L
        val h: COpaquePointer? = if (raw == 0L) null else raw.toCPointer<CPointed>()
        list.add(h?.let { it.obj<Any>() })
        p = p?.plus(1)
    }
    return list.handle()
}

fun rdma_arrayCreate(handles: CPointer<LongVar>?, count: Int): COpaquePointer {
    val arr = arrayOfNulls<Any?>(count)
    var p = handles
    for (i in 0 until count) {
        val raw = p?.pointed?.value ?: 0L
        val h: COpaquePointer? = if (raw == 0L) null else raw.toCPointer<CPointed>()
        arr[i] = h?.let { it.obj<Any>() }
        p = p?.plus(1)
    }
    return arr.handle()
}

// ------------------------------------------------------------ composer proxy
// The composer is read from rdmaGetCurrentComposer() (set by RdmaComposeHost
// during Content/nativeInvokeScopeBlock); these take no composer argument.

fun rdma_composer_startRestartGroup(key: Int) {
    rdmaGetCurrentComposer()?.startRestartGroup(key)
}

fun rdma_composer_endRestartGroup(): COpaquePointer? =
    rdmaGetCurrentComposer()?.endRestartGroup()?.handle()

fun rdma_composer_startReplaceGroup(key: Int) {
    rdmaGetCurrentComposer()?.startReplaceGroup(key)
}

fun rdma_composer_endReplaceGroup() {
    rdmaGetCurrentComposer()?.endReplaceGroup()
}

fun rdma_composer_startMovableGroup(key: Int, dataKey: COpaquePointer?) {
    rdmaGetCurrentComposer()?.startMovableGroup(key, dataKey?.obj<Any>())
}

fun rdma_composer_endMovableGroup() {
    rdmaGetCurrentComposer()?.endMovableGroup()
}

fun rdma_composer_startReusableGroup(key: Int, dataKey: COpaquePointer?) {
    rdmaGetCurrentComposer()?.startReusableGroup(key, dataKey?.obj<Any>())
}

fun rdma_composer_endReusableGroup() {
    rdmaGetCurrentComposer()?.endReusableGroup()
}

fun rdma_composer_skipCurrentGroup() {
    rdmaGetCurrentComposer()?.skipCurrentGroup()
}

fun rdma_composer_skipToGroupEnd() {
    rdmaGetCurrentComposer()?.skipToGroupEnd()
}

fun rdma_composer_shouldExecute(cond: Boolean, value: Int): Boolean =
    rdmaGetCurrentComposer()?.shouldExecute(cond, value) ?: false

fun rdma_composer_changed(key: COpaquePointer?): Boolean =
    rdmaGetCurrentComposer()?.changed(key?.obj<Any>()) ?: false

fun rdma_composer_rememberedValue(): COpaquePointer? =
    rdmaGetCurrentComposer()?.rememberedValue()?.handle()

fun rdma_composer_updateRememberedValue(value: COpaquePointer?) {
    rdmaGetCurrentComposer()?.updateRememberedValue(value?.obj<Any>())
}

// --------------------------------------------------------------------- state

fun rdma_mutableStateOf(initialValue: COpaquePointer?): COpaquePointer =
    mutableStateOf(initialValue?.obj<Any>()).handle()

fun rdma_stateGetValue(stateHandle: COpaquePointer): COpaquePointer? =
    stateHandle.obj<MutableState<*>>().value?.handle()

fun rdma_stateSetValue(stateHandle: COpaquePointer, value: COpaquePointer?) {
    @Suppress("UNCHECKED_CAST")
    val state = stateHandle.obj<MutableState<Any?>>()
    state.value = value?.obj<Any>()
}

// --------------------------------------------------------------------- scope

fun rdma_scopeUpdateScope(scopeHandle: COpaquePointer, blockId: Long) {
    scopeHandle.obj<ScopeUpdateScope>().updateScope(ComposerScopeBlock(blockId))
}

// ------------------------------------------------------------------- effects

fun rdma_sideEffect(blockId: Long) {
    rdmaGetCurrentComposer()?.recordSideEffect {
        RdmaComposeHost.nativeInvokeCallback(blockId, emptyArray<Any?>())
    }
}

fun rdma_disposableEffect(keysHandle: COpaquePointer, blockId: Long): Boolean {
    val keys = keysHandle.obj<Array<Any?>>()
    val composer = rdmaGetCurrentComposer() ?: return false
    var invalid = false
    for (key in keys) invalid = invalid || composer.changed(key)
    val observer = composer.cache(invalid) { RdmaDisposableEffectObserver(blockId) }
    return observer.effectBlockId == blockId
}

// --------------------------------------------------------------- registration

@EagerInitialization
private val _register = run {
    rdmaRegisterFunction("rdma_disposeStableRef", staticCFunction(::rdma_disposeStableRef))
    rdmaRegisterFunction("rdma_boxInt", staticCFunction(::rdma_boxInt))
    rdmaRegisterFunction("rdma_boxLong", staticCFunction(::rdma_boxLong))
    rdmaRegisterFunction("rdma_boxDouble", staticCFunction(::rdma_boxDouble))
    rdmaRegisterFunction("rdma_boxFloat", staticCFunction(::rdma_boxFloat))
    rdmaRegisterFunction("rdma_boxBoolean", staticCFunction(::rdma_boxBoolean))
    rdmaRegisterFunction("rdma_boxString", staticCFunction(::rdma_boxString))
    rdmaRegisterFunction("rdma_boxObject", staticCFunction(::rdma_boxObject))
    rdmaRegisterFunction("rdma_boxJsValueHolder", staticCFunction(::rdma_boxJsValueHolder))
    rdmaRegisterFunction("rdma_classifyValue", staticCFunction(::rdma_classifyValue))
    rdmaRegisterFunction("rdma_valueAsInt", staticCFunction(::rdma_valueAsInt))
    rdmaRegisterFunction("rdma_valueAsLong", staticCFunction(::rdma_valueAsLong))
    rdmaRegisterFunction("rdma_valueAsDouble", staticCFunction(::rdma_valueAsDouble))
    rdmaRegisterFunction("rdma_valueAsFloat", staticCFunction(::rdma_valueAsFloat))
    rdmaRegisterFunction("rdma_valueAsBoolean", staticCFunction(::rdma_valueAsBoolean))
    rdmaRegisterFunction("rdma_valueAsString", staticCFunction(::rdma_valueAsString))
    rdmaRegisterFunction("rdma_valueAsJsValueId", staticCFunction(::rdma_valueAsJsValueId))
    rdmaRegisterFunction("rdma_arraySize", staticCFunction(::rdma_arraySize))
    rdmaRegisterFunction("rdma_arrayGet", staticCFunction(::rdma_arrayGet))
    rdmaRegisterFunction("rdma_listSize", staticCFunction(::rdma_listSize))
    rdmaRegisterFunction("rdma_listGet", staticCFunction(::rdma_listGet))
    rdmaRegisterFunction("rdma_listCreate", staticCFunction(::rdma_listCreate))
    rdmaRegisterFunction("rdma_arrayCreate", staticCFunction(::rdma_arrayCreate))
    rdmaRegisterFunction("rdma_composer_startRestartGroup", staticCFunction(::rdma_composer_startRestartGroup))
    rdmaRegisterFunction("rdma_composer_endRestartGroup", staticCFunction(::rdma_composer_endRestartGroup))
    rdmaRegisterFunction("rdma_composer_startReplaceGroup", staticCFunction(::rdma_composer_startReplaceGroup))
    rdmaRegisterFunction("rdma_composer_endReplaceGroup", staticCFunction(::rdma_composer_endReplaceGroup))
    rdmaRegisterFunction("rdma_composer_startMovableGroup", staticCFunction(::rdma_composer_startMovableGroup))
    rdmaRegisterFunction("rdma_composer_endMovableGroup", staticCFunction(::rdma_composer_endMovableGroup))
    rdmaRegisterFunction("rdma_composer_startReusableGroup", staticCFunction(::rdma_composer_startReusableGroup))
    rdmaRegisterFunction("rdma_composer_endReusableGroup", staticCFunction(::rdma_composer_endReusableGroup))
    rdmaRegisterFunction("rdma_composer_skipCurrentGroup", staticCFunction(::rdma_composer_skipCurrentGroup))
    rdmaRegisterFunction("rdma_composer_skipToGroupEnd", staticCFunction(::rdma_composer_skipToGroupEnd))
    rdmaRegisterFunction("rdma_composer_shouldExecute", staticCFunction(::rdma_composer_shouldExecute))
    rdmaRegisterFunction("rdma_composer_changed", staticCFunction(::rdma_composer_changed))
    rdmaRegisterFunction("rdma_composer_rememberedValue", staticCFunction(::rdma_composer_rememberedValue))
    rdmaRegisterFunction("rdma_composer_updateRememberedValue", staticCFunction(::rdma_composer_updateRememberedValue))
    rdmaRegisterFunction("rdma_mutableStateOf", staticCFunction(::rdma_mutableStateOf))
    rdmaRegisterFunction("rdma_stateGetValue", staticCFunction(::rdma_stateGetValue))
    rdmaRegisterFunction("rdma_stateSetValue", staticCFunction(::rdma_stateSetValue))
    rdmaRegisterFunction("rdma_scopeUpdateScope", staticCFunction(::rdma_scopeUpdateScope))
    rdmaRegisterFunction("rdma_sideEffect", staticCFunction(::rdma_sideEffect))
    rdmaRegisterFunction("rdma_disposableEffect", staticCFunction(::rdma_disposableEffect))
    Unit
}
