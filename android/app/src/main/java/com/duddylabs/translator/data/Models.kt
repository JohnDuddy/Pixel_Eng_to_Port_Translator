package com.duddylabs.translator.data

enum class TranslatorMode(val label: String) {
    PUSH_TO_TALK("Push-to-Talk"),
    CONTINUOUS("Continuous"),
    TRAVEL("Travel"),
    MEDICAL("Medical Precision"),
}

enum class SpeakerLanguage(val label: String, val locale: String) {
    ENGLISH("English", "en-US"),
    PORTUGUESE_BRAZIL("Portuguese (Brazil)", "pt-BR"),
}

enum class VoiceSpeed(val label: String) {
    SLOW("Slow"),
    NORMAL("Normal"),
    FAST("Fast"),
}

enum class VoiceGender(val label: String, val realtimeVoice: String) {
    FEMALE("Female", "marin"),
    MALE("Male", "cedar"),
}

enum class TranslationStyle(val label: String) {
    LITERAL("Literal"),
    BALANCED("Balanced"),
    NATURAL("Natural"),
}

enum class ThemePreference(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

enum class RealtimeEngine(val label: String) {
    // On-device speech recognition + backend text translation + Android text-to-speech.
    ANDROID_SPEECH("On-Device Speech"),

    // Streams microphone audio to the OpenAI Realtime API over a WebSocket and plays the
    // translated voice back directly. Lower latency, requires a live backend + network.
    OPENAI_REALTIME("OpenAI Realtime Voice"),
}

fun SpeakerLanguage.opposite(): SpeakerLanguage =
    if (this == SpeakerLanguage.ENGLISH) SpeakerLanguage.PORTUGUESE_BRAZIL else SpeakerLanguage.ENGLISH
