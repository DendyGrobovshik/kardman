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

import com.example.kernel.RdmaHost
import com.example.kernel.internal.services.TextBox
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.kernel.UserBridge
import io.github.dendygrobovshik.kardman.runtime.RdmaBridge
import io.github.dendygrobovshik.kardman.runtime.RdmaComposeHost
import io.github.dendygrobovshik.kardman.runtime.RdmaDiagnostics
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
            val builtAgainst = intent.getIntExtra(EXTRA_BUILT_AGAINST, RdmaHost.VERSION)
            val hostGeneration = intent.getStringExtra(EXTRA_HOST_GENERATION) ?: RdmaHost.GENERATION
            Log.i("RDMA", "re-sync requested for $pluginId v$version (builtAgainst=$builtAgainst, generation=$hostGeneration)")
            knownPlugins[pluginId] = version
            syncPlugins(listOf(Triple(pluginId, version, builtAgainst)), hostGeneration)
        }
    }

    // Block-lifetime probe (docs/design/runtime_rework.md §4): collects garbage so the
    // holders of dropped plugin lambdas are finalized, lets the release queue drain on
    // the Hermes thread, then reports how many JS blocks the registry still holds.
    private val blocksReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Thread {
                repeat(3) {
                    Runtime.getRuntime().gc()
                    System.runFinalization()
                    Thread.sleep(300)
                }
                Log.i("RDMA_TEST", "BLOCKS=${RdmaComposeHost.nativeBlockCount()}")
            }.start()
        }
    }

    private val traceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            RdmaDiagnostics.logRecompositionReport()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        UserBridge.nativeInstall()

        // Async init: JNI caches on this thread, then Hermes thread + runtime.
        RdmaBridge.nativeInit(assets)

        // Recomposition tracer (e2e: `am start ... --ez rdmaTrace true`, report on TRACE_ACTION).
        if (intent.getBooleanExtra(EXTRA_TRACE, false)) RdmaDiagnostics.traceRecompositions(true)

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
        registerReceiver(blocksReceiver, IntentFilter(BLOCKS_ACTION), Context.RECEIVER_EXPORTED)
        registerReceiver(traceReceiver, IntentFilter(TRACE_ACTION), Context.RECEIVER_EXPORTED)

        setContent {
            // Start unready so the LaunchedEffect below logs "Runtime ready" before the
            // first content composition (a throwing content would otherwise crash the app
            // before the ready marker appears).
            var ready by remember { mutableStateOf(false) }
            // A component argument that changes after the first composition must reach the plugin.
            var live by remember { mutableStateOf(0) }
            var liveBox by remember { mutableStateOf(TextBox("one")) }
            var liveTitle by remember { mutableStateOf("s1") }
            LaunchedEffect(ready) {
                if (ready) {
                    delay(1500)
                    live = 5
                    liveBox = TextBox("two")
                    liveTitle = "s2"
                }
            }
            LaunchedEffect(Unit) {
                var readyLogged = false
                while (true) {
                    val r = checkReady()
                    if (r != ready) ready = r
                    if (r && !readyLogged) {
                        readyLogged = true
                        Log.i("RDMA", "Runtime ready")
                    }
                    delay(16)
                }
            }
            if (ready) {
                // Every stand component; a component not registered (plugin not loaded)
                // composes nothing. A new plugin version replaces every place at the update
                // point (`RdmaBridge.applyUpdates` at the end of a re-sync).
                run {
                    Column {
                        RdmaComposeHost.Component("alice:counter/main")
                        RdmaComposeHost.Component("itest:newplug/main")
                        RdmaComposeHost.Component("itest:exceptions/main")
                        RdmaComposeHost.Component("itest:exceptions2/main")
                        RdmaComposeHost.Component("bob:services/card", "a", 1)
                        RdmaComposeHost.Component("bob:services/card", "b", 2)
                        RdmaComposeHost.Component("bob:services/card", "c", live)
                        RdmaComposeHost.Component("bob:services/box", liveBox)
                        RdmaComposeHost.Component("bob:services/card", liveTitle, 7)
                        RdmaComposeHost.Component("bob:services/plain", liveTitle)
                        RdmaComposeHost.Component("bob:services/modifiers")
                        RdmaComposeHost.Component("bob:services/keys")
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // "Screen opened" re-sync (§8.2): drive every known plugin toward its newest version.
        val plugins = knownPlugins.map { (id, version) -> Triple(id, version, RdmaHost.VERSION) }
        syncPlugins(plugins)
    }

    override fun onDestroy() {
        unregisterReceiver(resyncReceiver)
        unregisterReceiver(blocksReceiver)
        unregisterReceiver(traceReceiver)
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
        val hostVersion = RdmaHost.VERSION
        Thread {
            try {
                while (!RdmaBridge.nativeIsReady()) Thread.sleep(16)
                val updated = mutableListOf<String>()
                for ((id, version, builtAgainst) in plugins) {
                    val resp = client.resync(ResyncRequest(id, version, builtAgainst, hostVersion, hostGeneration))
                    when (resp.status) {
                        ResyncStatus.UPDATE -> {
                            // Polyfills first: the new plugin version may call what they add.
                            updated += stagePolyfills(client, resp.polyfills)
                            resp.bundleHash?.let { h -> client.download(h)?.let { stage(client, h, it) } }
                            knownPlugins[id] = resp.targetVersion
                            updated += "updated $id -> v${resp.targetVersion}"
                        }
                        ResyncStatus.CURRENT -> {
                            // A current plugin can still be at-risk (§7.2): its symbols may have
                            // been removed, so the R polyfills must be fetched even without a new
                            // plugin bundle.
                            updated += stagePolyfills(client, resp.polyfills)
                            Log.i("RDMA", "current $id v${resp.targetVersion}")
                        }
                        ResyncStatus.UNAVAILABLE -> Log.i("RDMA", "unavailable $id on this host")
                        ResyncStatus.REFUSED -> Log.i("RDMA", "refused $id (mandatory update)")
                    }
                }
                // The stand's update point (compatibility.md §8.5): right after a re-sync, so the
                // tests see a new version at once. A real app picks a moment the user does not
                // watch plugin content (between screens, back from the background).
                if (RdmaBridge.applyUpdates()) {
                    while (!RdmaBridge.nativeIsReady()) Thread.sleep(16)
                }
                updated.forEach { Log.i("RDMA", it) }
            } catch (e: Exception) {
                Log.i("RDMA", "re-sync unavailable: ${e.message}")
            }
        }.start()
    }

    /** Stages the polyfills; returns the log lines to print once they are applied. */
    private fun stagePolyfills(client: RdmaSyncClient, keys: List<String>): List<String> =
        keys.mapNotNull { key ->
            client.download(key)?.let { bytes ->
                stage(client, key, bytes)
                "polyfill $key evaluated"
            }
        }

    /**
     * Checks a downloaded bundle — its hash is its key, and it is signed by a trusted key when
     * the app has any (`RdmaHost.PUBLIC_KEYS`) — then caches it to `cacheDir/rdma/<hash>.hbc`
     * (content-addressed, so a repeated download is skipped) and stages it for the next update
     * point. A bundle that fails the check is dropped.
     */
    private fun stage(client: RdmaSyncClient, key: String, bytes: ByteArray) {
        val trusted = RdmaHost.PUBLIC_KEYS.map { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
        val signature = if (trusted.isEmpty()) null else client.download("$key.sig")
        if (!RdmaBridge.verifyBundle(bytes, key, signature, trusted)) {
            Log.i("RDMA", "rejected bundle $key (hash or signature check failed)")
            return
        }
        val dir = File(cacheDir, "rdma")
        if (!dir.isDirectory) dir.mkdirs()
        val file = File(dir, "$key.hbc")
        if (!file.isFile) file.writeBytes(bytes)
        RdmaBridge.stageUpdate(file.absolutePath)
    }

    private companion object {
        // Emulator loopback to the host's localhost.
        const val STORE_URL = "http://10.0.2.2:8080"
        const val RESYNC_ACTION = "org.example.rdma.RESYNC"
        const val BLOCKS_ACTION = "org.example.rdma.BLOCKS"
        const val TRACE_ACTION = "org.example.rdma.TRACE"
        const val EXTRA_TRACE = "rdmaTrace"
        const val EXTRA_PLUGIN_ID = "pluginId"
        const val EXTRA_VERSION = "version"
        const val EXTRA_BUILT_AGAINST = "builtAgainst"
        const val EXTRA_HOST_GENERATION = "hostGeneration"
    }
}
