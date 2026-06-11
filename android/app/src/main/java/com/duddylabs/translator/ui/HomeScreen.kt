package com.duddylabs.translator.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.duddylabs.translator.R
import com.duddylabs.translator.data.TranslatorMode

@Composable
fun HomeScreen(viewModel: TranslatorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(
            painter = painterResource(id = R.drawable.menu_portrait),
            contentDescription = "Duddy Translator menu artwork",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Text(
            "English and Brazilian Portuguese voice translator",
            style = MaterialTheme.typography.titleMedium,
        )
        Button(
            onClick = { viewModel.startMode(TranslatorMode.PUSH_TO_TALK) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Start Conversation")
        }
        OutlinedButton(
            onClick = { viewModel.startMode(TranslatorMode.TRAVEL) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Travel Mode")
        }
        OutlinedButton(
            onClick = { viewModel.startMode(TranslatorMode.MEDICAL) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Medical Mode")
        }
        OutlinedButton(
            onClick = { viewModel.navigate(Screen.SETTINGS) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Settings")
        }
        OutlinedButton(
            onClick = { viewModel.navigate(Screen.HISTORY) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Conversation History")
        }
    }
}
