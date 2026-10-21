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
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.example

import androidx.compose.ui.window.ComposeUIViewController
import com.example.kernel.internal.services.installStore
import io.github.dendygrobovshik.kardman.runtime.RdmaBridge
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.example.ios.rdma_userBridgeInstall
import org.example.ui.DemoShell
import platform.Foundation.NSBundle
import platform.Foundation.NSTemporaryDirectory
import platform.UIKit.UIViewController
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

/**
 * iOS host entry point. Bootstraps the Hermes runtime + user bridge, evaluates the
 * shared-runtime `.hbc` bundles, then hosts the demo shell (edit/preview). Dynamic
 * plugins are delivered through the store and evaluated on re-sync.
 */
fun MainViewController(): UIViewController {
    rdma_userBridgeInstall()
    RdmaBridge.nativeInit()
    loadBundles()
    installStore("http://localhost:8080", NSTemporaryDirectory() + "rdma", 1)
    return ComposeUIViewController { DemoShell() }
}

// Load order comes from the build-generated `rdma-modules.json` manifest (shared
// dependencies first, then any baked-in plugin bundles).
private fun loadBundles() {
    for (name in readModuleManifest()) {
        evalBundle(name)
    }
}

private fun readModuleManifest(): List<String> {
    val path = NSBundle.mainBundle.pathForResource("rdma-modules", ofType = "json") ?: return emptyList()
    val file = fopen(path, "rb") ?: return emptyList()
    return try {
        fseek(file, 0L, SEEK_END)
        val size = ftell(file).toInt()
        fseek(file, 0L, SEEK_SET)
        val bytes = ByteArray(size)
        val read = bytes.usePinned { pinned ->
            fread(pinned.addressOf(0), 1UL, size.toULong(), file).toInt()
        }
        if (read == size) {
            val text = bytes.decodeToString()
            val start = text.indexOf('[')
            val end = text.indexOf(']', start)
            if (start >= 0 && end > start) {
                text.substring(start + 1, end)
                    .split(",")
                    .map { it.trim().trim('"') }
                    .filter { it.isNotEmpty() }
            } else emptyList()
        } else emptyList()
    } finally {
        fclose(file)
    }
}

private fun evalBundle(name: String) {
    val path = NSBundle.mainBundle.pathForResource(name, ofType = "hbc")
    if (path == null) {
        println("RDMA: bundle not found: $name.hbc")
        return
    }
    RdmaBridge.nativeEvalFile(path)
}
