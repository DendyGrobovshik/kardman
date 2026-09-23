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
package org.example

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.kernel.UserBridge
import io.github.dendygrobovshik.kardman.runtime.RdmaBridge
import io.github.dendygrobovshik.kardman.runtime.RdmaComposeHost
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        UserBridge.nativeInstall()

        // Async init: JNI caches on this thread, then Hermes thread + runtime.
        RdmaBridge.nativeInit(assets)

        val dependencies = listOf(
            "kotlin/kotlin-kotlin-stdlib.hbc",
            "kotlin/kotlinx-atomicfu.hbc",
            "kotlin/kotlinx-coroutines-core.hbc",
            "kotlin/androidx-collection-collection.hbc",
            "kotlin/androidx-compose-runtime-runtime.hbc",
        )
        for (dep in dependencies) {
            RdmaBridge.nativeEvalAsset(dep)
        }
        RdmaBridge.nativeEvalAsset("kotlin/RDMAHermes-plugin-alice-counter.hbc")
        RdmaBridge.nativeEvalAsset("kotlin/RDMAHermes-plugin-bob-services.hbc")

        loadDevPolyfills()

        setContent {
            var ready by remember { mutableStateOf(RdmaBridge.nativeIsReady()) }
            LaunchedEffect(Unit) {
                while (!ready) {
                    delay(16)
                    ready = RdmaBridge.nativeIsReady()
                }
                Log.i("RDMA", "Runtime ready")
            }
            if (ready) {
                RdmaComposeHost.Content()
            }
        }
    }

    /**
     * Dev-only polyfill loader. Evaluates any `.hbc` bundles dropped into
     * `filesDir/rdma/` (via `adb push`) after the built-in bundles, so they
     * override the native `@RDMA` implementations without rebuilding the app.
     * This is the local stand-in for the future network bundle source.
     */
    private fun loadDevPolyfills() {
        val dir = File(filesDir, "rdma")
        if (!dir.isDirectory) return
        dir.listFiles { f -> f.isFile && f.extension == "hbc" }
            ?.sortedBy { it.name }
            ?.forEach { RdmaBridge.nativeEvalFile(it.absolutePath) }
    }
}
