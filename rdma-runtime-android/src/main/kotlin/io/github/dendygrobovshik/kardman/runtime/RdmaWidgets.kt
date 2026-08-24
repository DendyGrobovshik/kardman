package io.github.dendygrobovshik.kardman.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import android.util.Log
import com.example.kernel.Button
import com.example.kernel.Column
import com.example.kernel.Text
import com.example.kernel.TextField

// Dispatched from JNI during composition. `args` are JVM boxed values.
// Widget rendering is owned by `:kernel` (@Composable material3 implementations).
@Composable
fun rdmaDispatch(name: String, args: Array<Any?>) {
    Log.i("RdmaCompose", "dispatch widget: $name")
    when (name) {
        "Text" -> Text(args[0] as String)
        "Column" -> Column {
            RdmaComposeHost.nativeInvokeScopeBlock((args[0] as Int).toLong(), currentComposer, 0)
        }
        "Button" -> Button(
            text = args[0] as String,
            onClick = {
                RdmaComposeHost.nativeInvokeCallback((args[1] as Int).toLong(), emptyArray())
            },
        )
        "TextField" -> TextField(
            value = args[0] as String,
            onValueChange = { newValue ->
                RdmaComposeHost.nativeInvokeCallback((args[1] as Int).toLong(), arrayOf<Any?>(newValue))
            },
        )
        else -> Text("unknown widget: $name")
    }
}
