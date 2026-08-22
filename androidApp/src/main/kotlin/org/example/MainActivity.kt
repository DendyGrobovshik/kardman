package org.example

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.dendygrobovshik.kardman.runtime.RdmaBridge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        RdmaBridge.nativeInit(assets)
        Log.i("RDMA", "Bridge initialized")

        try {
            RdmaBridge.nativeEvalAsset("kotlin/kotlin-kotlin-stdlib.js")
            Log.i("RDMA", "Kotlin stdlib loaded")

            val pluginResult = RdmaBridge.nativeEvalAsset("kotlin/RDMAHermes-plugin.js")
            Log.i("RDMA", "Plugin: $pluginResult")
        } catch (e: Exception) {
            Log.e("RDMA", "Plugin load failed: ${e.message}")
        }

        setContent {
            RdmaUi()
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
