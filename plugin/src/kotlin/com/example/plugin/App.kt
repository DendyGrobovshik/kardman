package com.example.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.kernel.Button
import com.example.kernel.Column
import com.example.kernel.Text
import com.example.kernel.runRdmaApp

@Composable
fun Counter() {
    var count by remember { mutableStateOf(0) }
    Column {
        Text("Count: $count")
        Button("Increment", onClick = { count++ })
    }
}

fun main() {
    runPersonDemo()
    runRdmaApp { Counter() }
}
