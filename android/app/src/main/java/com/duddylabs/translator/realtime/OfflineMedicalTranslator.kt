package com.duddylabs.translator.realtime

import com.duddylabs.translator.data.SpeakerLanguage
import com.duddylabs.translator.data.TranslatorMode
import com.duddylabs.translator.network.TextTranslationResult
import java.text.Normalizer
import java.util.Locale

class OfflineMedicalTranslator {
    fun translate(
        text: String,
        sourceLanguage: SpeakerLanguage,
        targetLanguage: SpeakerLanguage,
        mode: TranslatorMode,
    ): TextTranslationResult? {
        if (mode != TranslatorMode.MEDICAL && mode != TranslatorMode.TRAVEL) {
            return null
        }

        val cleanText = text.trim()
        if (cleanText.isBlank()) {
            return null
        }

        val exact = entries.firstOrNull { entry ->
            normalize(entry.textFor(sourceLanguage)) == normalize(cleanText)
        }
        if (exact != null) {
            val translation = exact.textFor(targetLanguage)
            return offlineResult(cleanText, literal = translation, polished = translation)
        }

        val matchedTerms = terms.filter { term ->
            normalize(cleanText).contains(normalize(term.textFor(sourceLanguage)))
        }
        if (matchedTerms.isNotEmpty()) {
            val literal = matchedTerms.joinToString(separator = "; ") { term ->
                "${term.textFor(sourceLanguage)} = ${term.textFor(targetLanguage)}"
            }
            val polished = when (targetLanguage) {
                SpeakerLanguage.PORTUGUESE_BRAZIL ->
                    "Tradução offline limitada. Termos reconhecidos: $literal. Preciso de um intérprete médico qualificado para confirmar a frase completa."
                SpeakerLanguage.ENGLISH ->
                    "Limited offline translation. Recognized terms: $literal. I need a qualified medical interpreter to confirm the full sentence."
            }
            return offlineResult(cleanText, literal = literal, polished = polished)
        }

        val fallback = when (targetLanguage) {
            SpeakerLanguage.PORTUGUESE_BRAZIL ->
                "Preciso de um intérprete médico qualificado agora. Não consigo traduzir esta frase offline com segurança."
            SpeakerLanguage.ENGLISH ->
                "I need a qualified medical interpreter now. I cannot translate this sentence safely offline."
        }
        return offlineResult(cleanText, literal = fallback, polished = fallback)
    }

    fun entriesFor(sourceLanguage: SpeakerLanguage): List<String> =
        entries.map { it.textFor(sourceLanguage) }

    private fun offlineResult(
        original: String,
        literal: String,
        polished: String,
    ): TextTranslationResult =
        TextTranslationResult(
            originalText = original,
            literalTranslation = literal,
            polishedTranslation = polished,
            model = "offline-medical-phrasebook",
        )

    private data class Entry(
        val english: String,
        val portuguese: String,
    ) {
        fun textFor(language: SpeakerLanguage): String =
            when (language) {
                SpeakerLanguage.ENGLISH -> english
                SpeakerLanguage.PORTUGUESE_BRAZIL -> portuguese
            }
    }

    private companion object {
        private val terms = listOf(
            Entry("Squamous cell carcinoma", "Carcinoma espinocelular (CEC)"),
            Entry("Mohs surgery", "Cirurgia de Mohs"),
            Entry("Biopsy", "Biópsia"),
            Entry("Surgical margin", "Margem cirúrgica"),
            Entry("Clear margins", "Margens livres"),
            Entry("Recurrence", "Recidiva"),
            Entry("Metastasis", "Metástase"),
            Entry("Perineural invasion", "Invasão perineural"),
            Entry("Well differentiated", "Bem diferenciado"),
            Entry("Moderately differentiated", "Moderadamente diferenciado"),
            Entry("Poorly differentiated", "Pouco diferenciado"),
            Entry("Reconstruction", "Reconstrução"),
            Entry("Skin graft", "Enxerto de pele"),
            Entry("Flap", "Retalho"),
        )

        private val entries = terms + listOf(
            Entry("I need a qualified medical interpreter.", "Preciso de um intérprete médico qualificado."),
            Entry("Please speak slowly.", "Por favor, fale devagar."),
            Entry("Please write that down.", "Por favor, escreva isso."),
            Entry("I have squamous cell carcinoma.", "Tenho carcinoma espinocelular."),
            Entry("Do I have squamous cell carcinoma?", "Eu tenho carcinoma espinocelular?"),
            Entry("What did the biopsy show?", "O que a biópsia mostrou?"),
            Entry("Do I need Mohs surgery?", "Eu preciso de cirurgia de Mohs?"),
            Entry("Were the surgical margins clear?", "As margens cirúrgicas estão livres?"),
            Entry("Is there perineural invasion?", "Há invasão perineural?"),
            Entry("Is there metastasis?", "Há metástase?"),
            Entry("What is the risk of recurrence?", "Qual é o risco de recidiva?"),
            Entry("Is the tumor well differentiated?", "O tumor é bem diferenciado?"),
            Entry("Is the tumor moderately differentiated?", "O tumor é moderadamente diferenciado?"),
            Entry("Is the tumor poorly differentiated?", "O tumor é pouco diferenciado?"),
            Entry("Will I need reconstruction?", "Vou precisar de reconstrução?"),
            Entry("Will I need a skin graft?", "Vou precisar de enxerto de pele?"),
            Entry("Will I need a flap?", "Vou precisar de retalho?"),
            Entry("What are the risks of surgery?", "Quais são os riscos da cirurgia?"),
            Entry("What medicine should I take?", "Que remédio devo tomar?"),
            Entry("I am allergic to this medicine.", "Sou alérgico a este remédio."),
            Entry("Do I need antibiotics?", "Preciso de antibióticos?"),
            Entry("When should I return?", "Quando devo voltar?"),
            Entry("I am in pain.", "Estou com dor."),
            Entry("This is urgent.", "Isso é urgente."),
            Entry("Please confirm the diagnosis.", "Por favor, confirme o diagnóstico."),
            Entry("Please confirm the treatment plan.", "Por favor, confirme o plano de tratamento."),
            Entry("I consent to the procedure.", "Eu autorizo o procedimento."),
            Entry("I do not consent yet.", "Ainda não autorizo."),
        )

        private fun normalize(value: String): String {
            val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
            return decomposed
                .replace("\\p{Mn}+".toRegex(), "")
                .lowercase(Locale.US)
                .replace("[^a-z0-9 ]".toRegex(), " ")
                .replace("\\s+".toRegex(), " ")
                .trim()
        }
    }
}
