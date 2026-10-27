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

import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Host-side wrapper over [RdmaBridge]'s eval entry points. Bundle loading is
 * asynchronous, so a compile/load failure is surfaced synchronously here: the load
 * methods block until the runtime reports ready, and [RdmaBridge.nativeIsReady]
 * throws [BundleLoadException] if the bundle failed to compile (see design §7).
 */
@OptIn(ExperimentalForeignApi::class)
class BundleLoader {

    fun loadFile(filePath: String) {
        RdmaBridge.nativeEvalFile(filePath)
        awaitReady()
    }

    fun loadBytes(bytes: ByteArray) {
        RdmaBridge.nativeEvalBytes(bytes)
        awaitReady()
    }

    private fun awaitReady() {
        while (!RdmaBridge.nativeIsReady()) {
            // Busy-wait: nativeIsReady throws BundleLoadException on a stored error.
        }
    }
}
