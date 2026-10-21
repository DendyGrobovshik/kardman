package com.example.plugin.generated

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.kernel.internal.ButtonList
import com.example.kernel.internal.Column
import com.example.kernel.internal.Text
import com.example.kernel.internal.runRdmaApp

@Composable
fun GeneratedContent() {
    var count by remember { mutableStateOf(0) }
    Column {
        Text("Count: " + count)
        ButtonList(
            labels = listOf("decrement", "increment"),
            actions = listOf(
                { count-- },
                { count++ },
            ),
            selectedIndex = -1,
            icons = listOf("minus", "plus"),
        )
    }
}

fun main() {
    runRdmaApp { GeneratedContent() }
}
