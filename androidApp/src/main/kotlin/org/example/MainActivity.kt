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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        UserBridge.nativeInstall()

        // Async init: JNI caches on this thread, then Hermes thread + runtime.
        RdmaBridge.nativeInit(assets)

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
        RdmaBridge.nativeEvalAsset("kotlin/RDMAHermes-plugin.js")

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
}
