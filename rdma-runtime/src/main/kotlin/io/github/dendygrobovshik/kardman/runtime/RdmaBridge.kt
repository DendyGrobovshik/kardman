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
package io.github.dendygrobovshik.kardman.runtime

import android.content.res.AssetManager

object RdmaBridge {
    init {
        System.loadLibrary("rdma_runtime")
    }

    external fun nativeInit(assetManager: AssetManager)
    external fun nativeEvalAsset(assetPath: String)
    external fun nativeEvalFile(filePath: String)
    external fun nativeEvalBytes(bytes: ByteArray)
    private external fun nativeEvalFiles(filePaths: Array<String>)
    private external fun nativeVerifyEd25519(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean
    external fun nativeIsReady(): Boolean
    external fun nativeContentVersion(): Int
    external fun nativeSetUncaughtExceptionHandler(handler: (String, RdmaException) -> Unit)

    private val staged = mutableListOf<String>()

    /**
     * Stages a downloaded bundle file (a plugin or a polyfill) for the next update point
     * (compatibility.md §8.5). Nothing changes until [applyUpdates]: plugins on screen keep
     * running the code they started with. Stage polyfills before the plugins that need them.
     * Any thread.
     */
    fun stageUpdate(filePath: String) {
        synchronized(staged) { if (filePath !in staged) staged += filePath }
    }

    /** True when bundles are waiting for [applyUpdates]. */
    fun hasStagedUpdates(): Boolean = synchronized(staged) { staged.isNotEmpty() }

    /**
     * An update point (compatibility.md §8.5): evaluates every staged bundle, in staging order,
     * as one step on the plugin thread, so nothing runs against a half-applied update. Every
     * place showing a component of an updated plugin is recreated with the new version (its
     * `remember` state starts over), and updated kernel functions switch for every caller. Call
     * it where the user does not watch plugin content: between screens, on returning from the
     * background. A cold start is the other update point: evaluate the newest bundles before
     * the first frame. Returns false when nothing was staged. Any thread.
     */
    fun applyUpdates(): Boolean {
        val paths = synchronized(staged) { staged.toTypedArray().also { staged.clear() } }
        if (paths.isEmpty()) return false
        nativeEvalFiles(paths)
        return true
    }

    /**
     * Checks a downloaded bundle before it is staged (compatibility.md §8.1): its SHA-256 is
     * [hash] (bundles are content-addressed) and, when [trustedKeys] (raw 32-byte Ed25519 public
     * keys) are given, [signature] is a valid signature of [hash] by one of them. A bundle that
     * fails is never evaluated.
     */
    fun verifyBundle(bytes: ByteArray, hash: String, signature: ByteArray?, trustedKeys: List<ByteArray>): Boolean {
        val actual = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (!actual.equals(hash, ignoreCase = true)) return false
        if (trustedKeys.isEmpty()) return true
        val sig = signature ?: return false
        val message = hash.lowercase().encodeToByteArray()
        return trustedKeys.any { key -> nativeVerifyEd25519(message, sig, key) }
    }

    /** Installs the host-side global uncaught-exception handler (see design §6). */
    fun setUncaughtExceptionHandler(handler: (String, RdmaException) -> Unit) {
        nativeSetUncaughtExceptionHandler(handler)
    }
}
