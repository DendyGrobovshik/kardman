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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.kernel.UserBridge
import io.github.dendygrobovshik.kardman.runtime.RdmaBridge
import io.github.dendygrobovshik.kardman.runtime.RdmaComposeHost
import io.github.dendygrobovshik.kardman.types.ResyncRequest
import io.github.dendygrobovshik.kardman.types.ResyncStatus
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class MainActivity : ComponentActivity() {

    // Loaded plugin versions (id -> version). Drives the re-sync `version` so the store can
    // tell CURRENT from UPDATE. The built-in bundles are the baseline install.
    private val knownPlugins = ConcurrentHashMap<String, Int>().apply {
        put("alice:counter", 1)
        put("bob:services", 1)
    }

    private val resyncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val pluginId = intent?.getStringExtra(EXTRA_PLUGIN_ID) ?: return
            val version = intent.getIntExtra(EXTRA_VERSION, knownPlugins[pluginId] ?: 1)
            val builtAgainst = intent.getIntExtra(EXTRA_BUILT_AGAINST, DEMO_HOST_VERSION)
            val hostGeneration = intent.getStringExtra(EXTRA_HOST_GENERATION) ?: ""
            Log.i("RDMA", "re-sync requested for $pluginId v$version (builtAgainst=$builtAgainst, generation=$hostGeneration)")
            knownPlugins[pluginId] = version
            syncPlugins(listOf(Triple(pluginId, version, builtAgainst)), hostGeneration)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        UserBridge.nativeInstall()

        // Async init: JNI caches on this thread, then Hermes thread + runtime.
        RdmaBridge.nativeInit(assets)

        // Global uncaught-exception handler (design §6). Receives every plugin
        // exception together with the owning pluginId.
        RdmaBridge.setUncaughtExceptionHandler { pluginId, e ->
            Log.i("RDMA_TEST", "H2_UNCAUGHT:$pluginId:${e.originalExceptionType}:${e.message}")
        }

        for (module in readModuleManifest()) {
            RdmaBridge.nativeEvalAsset("kotlin/$module.hbc")
        }

        loadDevPolyfills()

        registerReceiver(resyncReceiver, IntentFilter(RESYNC_ACTION), Context.RECEIVER_EXPORTED)

        setContent {
            // Start unready so the LaunchedEffect below logs "Runtime ready" before the
            // first content composition (a throwing content would otherwise crash the app
            // before the ready marker appears).
            var ready by remember { mutableStateOf(false) }
            var contentVersion by remember { mutableStateOf(RdmaBridge.nativeContentVersion()) }
            LaunchedEffect(Unit) {
                var readyLogged = false
                while (true) {
                    val r = checkReady()
                    if (r != ready) ready = r
                    if (r && !readyLogged) {
                        readyLogged = true
                        Log.i("RDMA", "Runtime ready")
                    }
                    val cv = RdmaBridge.nativeContentVersion()
                    if (cv != contentVersion) contentVersion = cv
                    delay(16)
                }
            }
            if (ready) {
                key(contentVersion) {
                    RdmaComposeHost.Content()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // "Screen opened" re-sync (§8.2): drive every known plugin toward its newest version.
        val plugins = knownPlugins.map { (id, version) -> Triple(id, version, DEMO_HOST_VERSION) }
        syncPlugins(plugins)
    }

    override fun onDestroy() {
        unregisterReceiver(resyncReceiver)
        super.onDestroy()
    }

    /**
     * Dev-only polyfill loader. Evaluates any `.hbc` bundles dropped into
     * `filesDir/rdma/` (via `adb push`) after the built-in bundles, so they
     * override the native `@RDMA` implementations without rebuilding the app.
     * This is the local stand-in for the network bundle source when no store is reachable.
     */
    private fun loadDevPolyfills() {
        val dir = File(filesDir, "rdma")
        if (!dir.isDirectory) return
        dir.listFiles { f -> f.isFile && f.extension == "hbc" }
            ?.sortedBy { it.name }
            ?.forEach { RdmaBridge.nativeEvalFile(it.absolutePath) }
    }

    /**
     * Reads the build-generated load manifest (`assets/kotlin/rdma-modules.json`) and returns
     * the module base names in dependency order (shared dependencies first, then plugins).
     */
    private fun readModuleManifest(): List<String> {
        return assets.open("kotlin/rdma-modules.json").use { stream ->
            val arr = JSONObject(stream.bufferedReader().readText()).getJSONArray("modules")
            List(arr.length()) { arr.getString(it) }
        }
    }

    /**
     * Polls runtime readiness, catching BundleLoadException (A2, design §7): a
     * corrupt bundle is surfaced from nativeIsReady() exactly once and logged here.
     */
    private fun checkReady(): Boolean = runCatching { RdmaBridge.nativeIsReady() }
        .onFailure { e -> Log.i("RDMA_TEST", "A2_CAUGHT:${e.message}") }
        .getOrDefault(false)

    /**
     * Re-sync against the store (§8.2). Runs off the UI thread; polls the runtime until ready,
     * then for each plugin asks the store for the newest compatible version and evaluates any
     * downloaded bundle/polyfill. On UPDATE the tracked version is bumped so the next re-sync
     * reports CURRENT. Unreachable store → falls back to local dev loading (already applied).
     */
    private fun syncPlugins(plugins: List<Triple<String, Int, Int>>, hostGeneration: String = "") {
        if (plugins.isEmpty()) return
        val client = RdmaSyncClient(STORE_URL)
        val hostVersion = DEMO_HOST_VERSION
        Thread {
            try {
                while (!RdmaBridge.nativeIsReady()) Thread.sleep(16)
                for ((id, version, builtAgainst) in plugins) {
                    val resp = client.resync(ResyncRequest(id, version, builtAgainst, hostVersion, hostGeneration))
                    when (resp.status) {
                        ResyncStatus.UPDATE -> {
                            resp.bundleHash?.let { h ->
                                client.download(h)?.let { cacheAndEvalBundle(h, it) }
                            }
                            evalPolyfills(client, resp.polyfills)
                            knownPlugins[id] = resp.targetVersion
                            Log.i("RDMA", "updated $id -> v${resp.targetVersion}")
                        }
                        ResyncStatus.CURRENT -> {
                            // A current plugin can still be at-risk (§7.2): its symbols may have
                            // been removed, so the R polyfills must be fetched even without a new
                            // plugin bundle.
                            evalPolyfills(client, resp.polyfills)
                            Log.i("RDMA", "current $id v${resp.targetVersion}")
                        }
                        ResyncStatus.UNAVAILABLE -> Log.i("RDMA", "unavailable $id on this host")
                        ResyncStatus.REFUSED -> Log.i("RDMA", "refused $id (mandatory update)")
                    }
                }
            } catch (e: Exception) {
                Log.i("RDMA", "re-sync unavailable: ${e.message}")
            }
        }.start()
    }

    private fun evalPolyfills(client: RdmaSyncClient, keys: List<String>) {
        for (key in keys) {
            client.download(key)?.let { bytes ->
                RdmaBridge.nativeEvalBytes(bytes)
                Log.i("RDMA", "polyfill $key evaluated")
            }
        }
    }

    /**
     * Caches a downloaded plugin bundle to `cacheDir/rdma/<hash>.hbc` (content-addressed, so a
     * repeated download is skipped) and mmap-loads it through the native runtime.
     */
    private fun cacheAndEvalBundle(hash: String, bytes: ByteArray) {
        val dir = File(cacheDir, "rdma")
        if (!dir.isDirectory) dir.mkdirs()
        val file = File(dir, "$hash.hbc")
        if (!file.isFile) file.writeBytes(bytes)
        RdmaBridge.nativeEvalFile(file.absolutePath)
    }

    private companion object {
        // Emulator loopback to the host's localhost.
        const val STORE_URL = "http://10.0.2.2:8080"
        // The kernel native version (`h`) baked by the initial static release in the
        // integration test's step 0 (internal + user:alice).
        const val DEMO_HOST_VERSION = 2
        const val RESYNC_ACTION = "org.example.rdma.RESYNC"
        const val EXTRA_PLUGIN_ID = "pluginId"
        const val EXTRA_VERSION = "version"
        const val EXTRA_BUILT_AGAINST = "builtAgainst"
        const val EXTRA_HOST_GENERATION = "hostGeneration"
    }
}
