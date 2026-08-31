package io.github.dendygrobovshik.kardman.runtime

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.Modifier
import android.util.Log
import com.example.kernel.Button
import com.example.kernel.CaptionText
import com.example.kernel.Card
import com.example.kernel.Column
import com.example.kernel.HorizontalScrollRow
import com.example.kernel.Image
import com.example.kernel.OldPriceText
import com.example.kernel.PriceText
import com.example.kernel.Row
import com.example.kernel.SearchBar
import com.example.kernel.SectionTitle
import com.example.kernel.Text
import com.example.kernel.TextField
import com.example.kernel.TitleText
import com.example.kernel.VerticalScrollColumn

// Dispatched from JNI during composition. `args` are JVM boxed values.
// Widget rendering is owned by `:kernel` (@Composable material3 implementations).
@Composable
fun rdmaDispatch(name: String, args: Array<Any?>) {
    Log.i("RdmaCompose", "dispatch widget: $name")
    when (name) {
        "Text" -> Text(args[0] as String)
        "TitleText" -> TitleText(args[0] as String)
        "PriceText" -> PriceText(args[0] as String)
        "OldPriceText" -> OldPriceText(args[0] as String)
        "CaptionText" -> CaptionText(args[0] as String)
        "SectionTitle" -> SectionTitle(args[0] as String)
        "Image" -> Image(args[0] as String)
        "Row" -> Row {
            RdmaComposeHost.nativeInvokeScopeBlock((args[0] as Int).toLong(), currentComposer, 0)
        }
        "Column" -> Column {
            RdmaComposeHost.nativeInvokeScopeBlock((args[0] as Int).toLong(), currentComposer, 0)
        }
        "Card" -> Card {
            RdmaComposeHost.nativeInvokeScopeBlock((args[0] as Int).toLong(), currentComposer, 0)
        }
        "HorizontalScrollRow" -> HorizontalScrollRow {
            RdmaComposeHost.nativeInvokeScopeBlock((args[0] as Int).toLong(), currentComposer, 0)
        }
        "VerticalScrollColumn" -> VerticalScrollColumn {
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
        "SearchBar" -> SearchBar(
            value = args[0] as String,
            onValueChange = { newValue ->
                RdmaComposeHost.nativeInvokeCallback((args[1] as Int).toLong(), arrayOf<Any?>(newValue))
            },
            onClear = {
                RdmaComposeHost.nativeInvokeCallback((args[2] as Int).toLong(), emptyArray())
            },
        )
        else -> Text("unknown widget: $name")
    }
}
