package com.example.kernel

import androidx.compose.foundation.layout.Column as M3Column
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextField as M3TextField
import androidx.compose.runtime.Composable
import io.github.dendygrobovshik.kardman.RDMAWidget

@Composable
@RDMAWidget
fun Text(text: String) {
    M3Text(text)
}

@Composable
@RDMAWidget
fun Column(content: @Composable () -> Unit) {
    M3Column {
        content()
    }
}

@Composable
@RDMAWidget
fun Button(text: String, onClick: () -> Unit) {
    M3Button(onClick = onClick) {
        Text(text)
    }
}

@Composable
@RDMAWidget
fun TextField(value: String, onValueChange: (String) -> Unit) {
    M3TextField(value = value, onValueChange = onValueChange)
}

fun runRdmaApp(content: @Composable () -> Unit) {
    // Host-side entry point. In the plugin this call is rewritten by the
    // plugin compiler plugin into RDMA.registerContent(...); on the host this
    // body is not invoked in the plugin flow.
}
