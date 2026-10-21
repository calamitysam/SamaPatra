package com.samapatra.app.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

private fun localeOf(language: String, region: String): Locale =
    Locale.Builder().setLanguage(language).setRegion(region).build()

/** Cuts long text into pieces the voice engine will accept, at the end of a sentence where possible. */
private fun splitForSpeech(text: String, max: Int): List<String> {
    val parts = mutableListOf<String>()
    var rest = text.trim()
    while (rest.length > max) {
        var cut = rest.lastIndexOf(". ", max)
        if (cut < max / 2) cut = rest.lastIndexOf(' ', max)
        if (cut <= 0) cut = max
        parts.add(rest.substring(0, cut + 1).trim())
        rest = rest.substring(cut + 1).trim()
    }
    if (rest.isNotBlank()) parts.add(rest)
    return parts
}

/** Reads text aloud in the student's language using the tablet's own voice. */
class Speaker(context: Context, private val languageCode: String) {
    var ready by mutableStateOf(false)
        private set

    /** True when this tablet has no voice for the language. */
    var voiceMissing by mutableStateOf(false)
        private set

    private var engine: TextToSpeech? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            val tts = engine
            if (status != TextToSpeech.SUCCESS || tts == null) {
                voiceMissing = true
            } else if (chooseVoice(tts)) {
                ready = true
            } else {
                voiceMissing = true
            }
        }
    }

    private fun chooseVoice(tts: TextToSpeech): Boolean {
        val wanted = when (languageCode) {
            "hi" -> listOf(localeOf("hi", "IN"))
            "kn" -> listOf(localeOf("kn", "IN"))
            else -> listOf(localeOf("en", "IN"), Locale.ENGLISH)
        }
        for (locale in wanted) {
            val result = tts.setLanguage(locale)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                return true
            }
        }
        return false
    }

    fun speak(text: String) {
        val tts = engine
        if (!ready || tts == null) return
        val limit = TextToSpeech.getMaxSpeechInputLength() - 100
        splitForSpeech(text, limit).forEachIndexed { i, part ->
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(part, mode, null, "samapatra-$i")
        }
    }

    fun stop() {
        engine?.stop()
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
    }
}

@Composable
fun rememberSpeaker(languageCode: String): Speaker {
    val context = LocalContext.current
    val speaker = remember(languageCode) { Speaker(context, languageCode) }
    DisposableEffect(speaker) {
        onDispose { speaker.shutdown() }
    }
    return speaker
}