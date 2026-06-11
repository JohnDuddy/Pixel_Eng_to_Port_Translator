@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.duddylabs.translator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun TranscriptCard(
    row: TranscriptRow,
    medical: Boolean,
    onReplay: () -> Unit,
    onRetry: () -> Unit,
    onCorrect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(14.dp),
        ) {
            val spokenLanguage = row.target.label.substringBefore(" (")
            val heardLanguage = row.speaker.label.substringBefore(" (")
            Text("$spokenLanguage voice played", fontWeight = FontWeight.Bold)
            Text("$heardLanguage heard: ${row.original}")
            if (medical) {
                Text("Literal: ${row.literal}")
                Text("Polished: ${row.polished}")
            } else {
                Text("$spokenLanguage: ${row.polished.ifBlank { row.literal }}")
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onReplay) {
                    Text("Replay")
                }
                OutlinedButton(onClick = onRetry) {
                    Text("Retry")
                }
                OutlinedButton(onClick = onCorrect) {
                    Text("Correct")
                }
            }
            Text("Timestamp: ${row.timestamp}", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
        }
    }
}
