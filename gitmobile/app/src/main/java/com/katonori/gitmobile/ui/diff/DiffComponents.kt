package com.katonori.gitmobile.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.katonori.gitmobile.core.git.DiffLine
import com.katonori.gitmobile.core.git.DiffLineType
import com.katonori.gitmobile.core.git.FileDiff

private val addedColor = Color(0xFF1B5E20)
private val addedBackground = Color(0x332E7D32)
private val removedColor = Color(0xFFB71C1C)
private val removedBackground = Color(0x33C62828)

@Composable
fun DiffList(diffs: List<FileDiff>, modifier: Modifier = Modifier) {
    if (diffs.isEmpty()) {
        Text("変更はありません。", modifier = modifier.padding(16.dp))
        return
    }
    Column(modifier = modifier.padding(8.dp)) {
        diffs.forEach { diff -> FileDiffCard(diff) }
    }
}

@Composable
private fun FileDiffCard(diff: FileDiff) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(diff.displayPath, style = MaterialTheme.typography.titleSmall)
            if (diff.isBinary) {
                Text(
                    "バイナリファイルが変更されました。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    diff.lines.forEach { line -> DiffLineRow(line) }
                }
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffLine) {
    val (background, textColor) = when (line.type) {
        DiffLineType.ADDED -> addedBackground to addedColor
        DiffLineType.REMOVED -> removedBackground to removedColor
        DiffLineType.HEADER -> Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
        DiffLineType.CONTEXT -> Color.Transparent to MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = line.text,
        color = textColor,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 4.dp),
    )
}
