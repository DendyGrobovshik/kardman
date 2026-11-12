package com.example.plugin.generated

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.kernel.internal.Button
import com.example.kernel.internal.Column
import com.example.kernel.internal.Row
import com.example.kernel.internal.Spacer
import com.example.kernel.internal.Text
import com.example.kernel.internal.runRdmaApp
import io.github.dendygrobovshik.kardman.compose.Color
import io.github.dendygrobovshik.kardman.compose.Dp
import io.github.dendygrobovshik.kardman.compose.HorizontalAlignment
import io.github.dendygrobovshik.kardman.compose.Modifier
import io.github.dendygrobovshik.kardman.compose.Shape

@Composable
fun GeneratedContent() {
    var picked by remember { mutableStateOf("none") }
    Column(modifier = Modifier().paddingAll(Dp(16.0)).fillMaxWidth()) {
        Text(
            "Modifiers",
            modifier = Modifier().padding(Dp(0.0), Dp(0.0), Dp(0.0), Dp(8.0)).testTag("title"),
        )
        Text(
            "Picked: " + picked,
            modifier = Modifier().alpha(0.7).alignHorizontally(HorizontalAlignment.CenterHorizontally),
        )
        Spacer(0.0, 12.0)
        Row(modifier = Modifier().fillMaxWidth()) {
            Button(
                "Left",
                modifier = Modifier().weight(1.0).paddingAll(Dp(4.0)),
                onClick = { picked = "left" },
            )
            Spacer(8.0, 0.0)
            Button(
                "Right",
                modifier = Modifier().weight(1.0).paddingAll(Dp(4.0)),
                onClick = { picked = "right" },
            )
        }
        Spacer(0.0, 12.0)
        Row(modifier = Modifier().fillMaxWidth()) {
            Text(
                "Red",
                modifier = Modifier()
                    .weight(1.0)
                    .background(Color.Red, Shape.Rectangle)
                    .paddingAll(Dp(8.0)),
            )
            Spacer(8.0, 0.0)
            Text(
                "Blue",
                modifier = Modifier()
                    .weight(1.0)
                    .background(Color.Blue, Shape.Circle)
                    .paddingSymmetric(Dp(12.0), Dp(8.0))
                    .offset(Dp(0.0), Dp(4.0)),
            )
        }
    }
}

fun main() {
    runRdmaApp { GeneratedContent() }
}
