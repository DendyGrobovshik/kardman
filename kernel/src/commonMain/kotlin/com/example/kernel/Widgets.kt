package com.example.kernel

import androidx.compose.foundation.layout.Column as M3Column
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import io.github.dendygrobovshik.kardman.RDMA

@Composable
@RDMA
fun Text(text: String) {
    M3Text(text)
}

@Composable
@RDMA
fun Column(content: @Composable () -> Unit) {
    M3Column {
        content()
    }
}

@Composable
@RDMA
fun Button(text: String, onClick: () -> Unit) {
    M3Button(onClick = onClick) {
        M3Text(text)
    }
}

fun runRdmaApp(content: @Composable () -> Unit) {
    // Host-side entry point. In the plugin this call is rewritten by the
    // plugin compiler plugin into RDMA.registerContent(...).
}
