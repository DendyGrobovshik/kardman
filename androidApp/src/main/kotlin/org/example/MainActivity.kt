package org.example

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.dendygrobovshik.kardman.runtime.RdmaBridge
import io.github.dendygrobovshik.kardman.runtime.RdmaPluginLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        RdmaBridge.nativeInit()
        Log.i("RDMA", "Bridge initialized")

        try {
            val kotlinStdlib = assets.open("kotlin/kotlin-kotlin-stdlib.js")
                .bufferedReader().readText()
            RdmaPluginLoader.loadAndEval(kotlinStdlib)
            Log.i("RDMA", "Kotlin stdlib loaded")

            val pluginCode = assets.open("kotlin/RDMAHermes-plugin.js")
                .bufferedReader().readText()
            val pluginResult = RdmaPluginLoader.loadAndEval(pluginCode)
            Log.i("RDMA", "Plugin: $pluginResult")

            // Runtime vtable demo
            val vtableDemo = """
                var normal = RDMA.createPerson("Normal", 30);
                var g1 = normal.greet();
                
                var child = RDMA.createWithOverrides("Person", ["Child", 7], {
                    greet: function() { return "Overridden: I am a child!"; }
                });
                var g2 = child.greet();
                var name = child.getName();
                'normal=' + g1 + ' | child=' + g2 + ' | name=' + name;
            """.trimIndent()
            val vtableResult = RdmaPluginLoader.loadAndEval(vtableDemo)
            Log.i("RDMA", "Vtable: $vtableResult")
        } catch (e: Exception) {
            Log.e("RDMA", "Plugin load failed: ${e.message}")
        }

        setContent {
            App()
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
