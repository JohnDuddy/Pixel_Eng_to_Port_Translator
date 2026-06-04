package com.duddylabs.translator

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.duddylabs.translator.ui.DuddyTranslatorApp
import com.duddylabs.translator.ui.TranslatorViewModel
import com.duddylabs.translator.ui.TranslatorViewModelFactory
import com.duddylabs.translator.ui.theme.DuddyTranslatorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as DuddyTranslatorApplication).appContainer

        setContent {
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { }

            LaunchedEffect(Unit) {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    ),
                )
            }

            DuddyTranslatorTheme {
                val viewModel: TranslatorViewModel = viewModel(
                    factory = TranslatorViewModelFactory(container),
                )
                DuddyTranslatorApp(viewModel = viewModel)
            }
        }
    }
}
