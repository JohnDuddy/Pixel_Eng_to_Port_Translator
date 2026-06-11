package com.duddylabs.translator.realtime

import android.content.Context

class AndroidSpeechTranslatorClient(
    context: Context,
    translationRepository: TranslationRepository,
) : RealtimeTranslatorClient by ConversationController(
    context = context,
    translationRepository = translationRepository,
)
