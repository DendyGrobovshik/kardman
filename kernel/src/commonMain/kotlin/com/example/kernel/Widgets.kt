package com.example.kernel

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column as M3Column
import androidx.compose.foundation.layout.Row as M3Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.Card as M3Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextField as M3TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.dendygrobovshik.kardman.RDMA

@Composable
@RDMA
fun Text(text: String) {
    M3Text(text)
}

@Composable
@RDMA
fun TitleText(text: String) {
    M3Text(
        text = text,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp),
    )
}

@Composable
@RDMA
fun PriceText(text: String) {
    M3Text(
        text = text,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp),
    )
}

@Composable
@RDMA
fun OldPriceText(text: String) {
    M3Text(
        text = text,
        textDecoration = TextDecoration.LineThrough,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = 10.dp, end = 10.dp),
    )
}

@Composable
@RDMA
fun CaptionText(text: String) {
    M3Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 8.dp),
    )
}

@Composable
@RDMA
fun SectionTitle(text: String) {
    M3Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
    )
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
fun Row(content: @Composable () -> Unit) {
    M3Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
@RDMA
fun Card(content: @Composable () -> Unit) {
    M3Card(Modifier.width(160.dp)) {
        content()
    }
}

@Composable
@RDMA
fun HorizontalScrollRow(content: @Composable () -> Unit) {
    M3Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
@RDMA
fun VerticalScrollColumn(content: @Composable () -> Unit) {
    M3Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        content()
    }
}

@Composable
@RDMA
fun Image(url: String) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        placeholder = ColorPainter(Color(0xFFEEEEEE)),
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
    )
}

@Composable
@RDMA
fun Button(text: String, onClick: () -> Unit) {
    M3Button(onClick = onClick) {
        Text(text)
    }
}

@Composable
@RDMA
fun TextField(value: String, onValueChange: (String) -> Unit) {
    M3TextField(value = value, onValueChange = onValueChange)
}

@Composable
@RDMA
fun SearchBar(value: String, onValueChange: (String) -> Unit, onClear: () -> Unit) {
    M3Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        M3TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        Spacer(Modifier.width(8.dp))
        M3Button(onClick = onClear) {
            Text("Clear")
        }
    }
}

fun runRdmaApp(content: @Composable () -> Unit) {
    // Host-side entry point. In the plugin this call is rewritten by the
    // plugin compiler plugin into RDMA.registerContent(...); on the host this
    // body is not invoked in the plugin flow.
}
