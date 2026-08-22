package org.example

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.kernel.Button
import com.example.kernel.RdmaUiRoot
import com.example.kernel.Text
import com.example.kernel.Widget

@Composable
fun WidgetHost(widget: Widget) {
    when (widget) {
        is Button -> M3Button(onClick = { widget.onClick() }) { M3Text(widget.text) }
        is Text -> M3Text(widget.text)
        else -> Column(Modifier.padding(8.dp)) {
            widget.children.forEach { child -> WidgetHost(child) }
        }
    }
}

@Composable
fun RdmaUi() {
    val root = RdmaUiRoot.root ?: return
    WidgetHost(root)
}
