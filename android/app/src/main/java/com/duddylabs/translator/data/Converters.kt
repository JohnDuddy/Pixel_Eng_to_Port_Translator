package com.duddylabs.translator.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun toTranslatorMode(value: String): TranslatorMode = TranslatorMode.valueOf(value)

    @TypeConverter
    fun fromTranslatorMode(value: TranslatorMode): String = value.name

    @TypeConverter
    fun toSpeakerLanguage(value: String): SpeakerLanguage = SpeakerLanguage.valueOf(value)

    @TypeConverter
    fun fromSpeakerLanguage(value: SpeakerLanguage): String = value.name
}
