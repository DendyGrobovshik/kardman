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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.kernel.UserBridge
import com.example.kernel.internal.services.installStore
import io.github.dendygrobovshik.kardman.runtime.RdmaBridge
import org.example.ui.DemoShell
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        UserBridge.nativeInstall()
        RdmaBridge.nativeInit(assets)

        for (module in readModuleManifest()) {
            RdmaBridge.nativeEvalAsset("kotlin/$module.hbc")
        }

        loadDevPolyfills()

        installStore(STORE_URL, File(cacheDir, "rdma").absolutePath, DEMO_HOST_VERSION)

        setContent {
            DemoShell()
        }
    }

    /**
     * Dev-only polyfill loader. Evaluates any `.hbc` bundles dropped into
     * `filesDir/rdma/` (via `adb push`) after the built-in bundles, so they
     * override the native `@RDMA` implementations without rebuilding the app.
     */
    private fun loadDevPolyfills() {
        val dir = File(filesDir, "rdma")
        if (!dir.isDirectory) return
        dir.listFiles { f -> f.isFile && f.extension == "hbc" }
            ?.sortedBy { it.name }
            ?.forEach { RdmaBridge.nativeEvalFile(it.absolutePath) }
    }

    /** Reads the build-generated load manifest (`assets/kotlin/rdma-modules.json`). */
    private fun readModuleManifest(): List<String> {
        return assets.open("kotlin/rdma-modules.json").use { stream ->
            val arr = JSONObject(stream.bufferedReader().readText()).getJSONArray("modules")
            List(arr.length()) { arr.getString(it) }
        }
    }

    private companion object {
        // Emulator loopback to the host's localhost.
        const val STORE_URL = "http://10.0.2.2:8080"
        // The kernel native version (`h`) baked by the static release in demo.sh.
        const val DEMO_HOST_VERSION = 1
    }
}
