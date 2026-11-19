package com.example.plugin.itest

import androidx.compose.runtime.Composable
import com.example.kernel.internal.Column
import com.example.kernel.internal.Text
import com.example.kernel.internal.registerComponent

@Composable
fun NewPlugContent() {
    Column {
        Text("GENPLUG v4")
    }
}

fun main() {
    registerComponent("main") { NewPlugContent() }
}
