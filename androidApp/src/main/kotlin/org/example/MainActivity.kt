package org.example

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.dendygrobovshik.kardman.runtime.RdmaBridge
import io.github.dendygrobovshik.kardman.runtime.RdmaComposeHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        RdmaBridge.nativeInit(assets)
        Log.i("RDMA", "Bridge initialized")

        try {
            val dependencies = listOf(
                "kotlin/kotlin-kotlin-stdlib.js",
                "kotlin/kotlinx-atomicfu.js",
                "kotlin/kotlinx-coroutines-core.js",
                "kotlin/androidx-collection-collection.js",
                "kotlin/androidx-compose-runtime-runtime.js",
            )
            for (dep in dependencies) {
                RdmaBridge.nativeEvalAsset(dep)
            }
            Log.i("RDMA", "Dependencies loaded")

            val pluginResult = RdmaBridge.nativeEvalAsset("kotlin/RDMAHermes-plugin.js")
            Log.i("RDMA", "Plugin: $pluginResult")
        } catch (e: Exception) {
            Log.e("RDMA", "Plugin load failed: ${e.message}")
        }

        setContent {
            RdmaComposeHost.Content()
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
