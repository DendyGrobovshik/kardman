package com.example.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.kernel.Button
import com.example.kernel.Column
import com.example.kernel.Text
import com.example.kernel.TextField
import com.example.kernel.runRdmaApp

@Composable
fun App() {
    var text by remember { mutableStateOf("") }
    Column {
        TextField(text, onValueChange = { text = it })
        Text("You typed: $text")
        Button("Clear", onClick = { text = "" })
    }
}

fun main() {
    runPersonDemo()
    runRdmaApp { App() }
}
