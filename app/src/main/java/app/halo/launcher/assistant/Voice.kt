package app.halo.launcher.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import app.halo.launcher.R
import app.halo.launcher.i18n.Lang
import java.io.File
import java.util.Locale
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Speech-to-text through Android's SpeechRecognizer. On Google TV this uses the
 * remote's microphone while Halo is listening (the remote's Assistant button
 * itself always goes to Google and can't be taken over by a launcher).
 * Must be used from the main thread.
 */
class VoiceInput(private val context: Context) {
    interface Listener {
        fun onListening(active: Boolean)
        fun onPartial(text: String)
        fun onLevel(level: Float)
        fun onResult(text: String)
        fun onError(message: String)
    }

    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(listener: Listener) {
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.onListening(true)
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = listener.onListening(false)
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.let(listener::onPartial)
            }
            override fun onResults(results: Bundle?) {
                listener.onListening(false)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) listener.onError(msg(R.string.voice_not_caught)) else listener.onResult(text)
            }
            override fun onError(error: Int) {
                listener.onListening(false)
                listener.onError(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> msg(R.string.voice_not_caught)
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> msg(R.string.voice_no_connection)
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> msg(R.string.voice_no_permission)
                        SpeechRecognizer.ERROR_AUDIO -> msg(R.string.voice_no_microphone)
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> msg(R.string.voice_busy)
                        else -> msg(R.string.voice_failed, error)
                    }
                )
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Listen in the language chosen for Halo (the assistant answers in it too); with "same as
            // the TV", in the TV's own language, which is what people speak to it.
            val tag = if (Lang.current(context) == "system") Locale.getDefault().toLanguageTag() else Lang.speechTag(context)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        r.startListening(intent)
    }

    private fun msg(id: Int, vararg args: Any): String = Lang.string(context, id, *args)

    fun stop() {
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }
}

/**
 * Speaks Halo's replies, always in Halo's interface language ([Lang.effective]).
 *
 * Default: Gemini's natural text-to-speech (same API key as the assistant) – 24 kHz PCM played through
 * an AudioTrack, with the language passed explicitly (speechConfig.languageCode). Falls back to the
 * TV's built-in Android voice if there is no key, the network fails, or the "System voice" is chosen;
 * that voice is re-picked for the interface language whenever the language changes.
 */
class Speaker(
    context: Context,
    private val key: () -> String = { "" },
    /** Current Gemini voice name, or [SYSTEM_VOICE]. */
    private val voice: () -> String = { SYSTEM_VOICE },
) {
    companion object {
        const val SYSTEM_VOICE = "system"

        /**
         * Gemini voices offered in Settings: name (a proper name, never translated) to its
         * character as a string resource ("Warm", "Breezy" …, localized).
         */
        val VOICES: List<Pair<String, Int>> = listOf(
            "Sulafat" to R.string.voice_char_warm,
            "Aoede" to R.string.voice_char_breezy,
            "Achernar" to R.string.voice_char_soft,
            "Algieba" to R.string.voice_char_smooth,
            "Kore" to R.string.voice_char_firm,
            "Charon" to R.string.voice_char_informative,
            "Puck" to R.string.voice_char_upbeat,
            "Fenrir" to R.string.voice_char_excitable,
            "Leda" to R.string.voice_char_youthful,
            SYSTEM_VOICE to R.string.voice_char_system,
        )

        private val MODELS = listOf("gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts")
        private const val SAMPLE_RATE = 24_000
    }

    private val appContext = context.applicationContext
    private val apiKey: String get() = key()
    private var ready = false
    /** Language the TV voice is currently set up for ("de" / "en"), null = not yet. */
    @Volatile private var systemLang: String? = null
    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) useSystemLanguage(language())
    }

    /** Halo's interface language: "de" or "en". */
    private fun language(): String = Lang.effective(appContext)

    /** Gemini's free tier has a small TTS quota: after a 429 we use the TV voice for a while. */
    @Volatile private var quotaUntil = 0L

    /** Synthesised replies are cached (memory + disk), so repeats and voice samples cost nothing. */
    private val cacheDir = java.io.File(appContext.cacheDir, "tts").apply { mkdirs() }
    private val memCache = object : android.util.LruCache<String, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    /**
     * Sets the TV voice up for [lang]: the language itself, then the best installed voice for it
     * (highest quality, preferring the configured country, e.g. de-DE / en-US, and Google's
     * network voices). Runs again whenever the interface language changes.
     */
    private fun useSystemLanguage(lang: String) {
        if (systemLang == lang) return
        runCatching {
            val locale = Lang.locale(appContext).takeIf { it.language == lang }
                ?: if (lang == "de") Locale.GERMANY else Locale.US
            tts.setLanguage(locale)
            val best = tts.voices.orEmpty()
                .filter { it.locale.language == lang && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                .maxWithOrNull(
                    compareBy(
                        { it.quality },
                        { if (it.locale.country == locale.country) 1 else 0 },
                        { if (it.isNetworkConnectionRequired) 1 else 0 },
                    )
                )
            if (best != null) tts.voice = best
        }
        systemLang = lang
    }
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private var job: kotlinx.coroutines.Job? = null
    @Volatile private var track: android.media.AudioTrack? = null

    fun say(text: String, voiceOverride: String? = null) {
        if (text.isBlank()) return
        stop()
        val v = voiceOverride ?: voice()
        val lang = language()
        if (apiKey.isBlank() || v == SYSTEM_VOICE) {
            systemSay(text)
            return
        }
        val key = cacheKey(v, lang, text)
        memCache.get(key)?.let { pcm -> job = scope.launch { play(pcm) }; return }
        if (System.currentTimeMillis() < quotaUntil && !File(cacheDir, key).exists()) {
            android.util.Log.w("HaloVoice", "rate-limited for ${(quotaUntil - System.currentTimeMillis()) / 1000}s more → TV voice")
            systemSay(text)
            return
        }
        job = scope.launch {
            val pcm = runCatching { cached(key) ?: synthesize(text, v, lang)?.also { store(key, it) } }
                .onFailure { android.util.Log.w("HaloVoice", "Gemini TTS failed: ${it.message}") }
                .getOrNull()
            if (!isActive) return@launch
            if (pcm == null || pcm.isEmpty()) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { systemSay(text) }
            } else {
                play(pcm)
            }
        }
    }

    private fun systemSay(text: String) {
        if (!ready) return
        useSystemLanguage(language())
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "halo")
    }

    private fun cacheKey(voice: String, lang: String, text: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest("$voice|$lang|$text".toByteArray())
            .joinToString("") { "%02x".format(it) } + ".pcm"

    private fun cached(key: String): ByteArray? =
        File(cacheDir, key).takeIf { it.exists() }?.readBytes()?.also { memCache.put(key, it) }

    private fun store(key: String, pcm: ByteArray) {
        memCache.put(key, pcm)
        runCatching {
            File(cacheDir, key).writeBytes(pcm)
            // Keep the disk cache under ~40 MB.
            val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return@runCatching
            var total = files.sumOf { it.length() }
            for (f in files) { if (total < 40L shl 20) break; total -= f.length(); f.delete() }
        }
    }

    /**
     * A gentle style hint in the interface language keeps the delivery relaxed rather than
     * announcer-like (model-facing, so it stays in code rather than in string resources).
     */
    private fun styled(text: String, lang: String): String =
        if (lang == "de") "Sprich warm, entspannt und natürlich: $text"
        else "Say in a warm, relaxed, natural tone: $text"

    /** Set once a TTS model rejected speechConfig.languageCode; later requests leave it out. */
    @Volatile private var languageCodeUnsupported = false

    /**
     * Gemini TTS: returns raw 16-bit mono PCM at 24 kHz, spoken in [lang] ("de" → de-DE, else en-US).
     * The language is passed explicitly so short or mixed replies (app names, titles) are never read
     * with the wrong accent.
     */
    private fun synthesize(text: String, voiceName: String, lang: String): ByteArray? {
        val languageCode = if (lang == "de") "de-DE" else "en-US"
        fun body(withLanguage: Boolean): String = kotlinx.serialization.json.buildJsonObject {
            put("contents", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.buildJsonObject {
                    put("parts", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.buildJsonObject {
                            // A light style hint keeps it relaxed and natural, not announcer-like.
                            put("text", kotlinx.serialization.json.JsonPrimitive(styled(text, lang)))
                        })
                    })
                })
            })
            put("generationConfig", kotlinx.serialization.json.buildJsonObject {
                put("responseModalities", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("AUDIO")) })
                put("speechConfig", kotlinx.serialization.json.buildJsonObject {
                    if (withLanguage) put("languageCode", kotlinx.serialization.json.JsonPrimitive(languageCode))
                    put("voiceConfig", kotlinx.serialization.json.buildJsonObject {
                        put("prebuiltVoiceConfig", kotlinx.serialization.json.buildJsonObject {
                            put("voiceName", kotlinx.serialization.json.JsonPrimitive(voiceName))
                        })
                    })
                })
            })
        }.toString()
        var withLanguage = !languageCodeUnsupported
        var i = 0
        var quotaHits = 0
        var waitMs = 0L          // longest retry delay Google asked for
        var daily = false        // the per-day quota (not just per-minute) is used up
        var retried = false
        while (i < MODELS.size) {
            val model = MODELS[i]
            val c = java.net.URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .openConnection() as java.net.HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = 10_000
                c.readTimeout = 30_000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("x-goog-api-key", apiKey)
                c.outputStream.use { it.write(body(withLanguage).toByteArray()) }
                val code = c.responseCode
                // A model that doesn't take languageCode: retry the same model without it
                // (the text and the style hint are already in the right language).
                if (code == 400 && withLanguage) {
                    val err = runCatching { (c.errorStream ?: c.inputStream).bufferedReader().use { it.readText() } }.getOrDefault("")
                    if (err.contains("languageCode", ignoreCase = true) || err.contains("language_code", ignoreCase = true)) {
                        android.util.Log.w("HaloVoice", "$model: languageCode not accepted, retrying without it")
                        withLanguage = false
                        languageCodeUnsupported = true
                        continue
                    }
                    error("HTTP 400 ${err.take(200)}")
                }
                // Unknown model, or that model's quota is used up → the next model has its own quota.
                if (code == 404 || code == 429) {
                    if (code == 429) {
                        quotaHits++
                        val err = runCatching { (c.errorStream ?: c.inputStream).bufferedReader().use { it.readText() } }.getOrDefault("")
                        val delay = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)(?:\\.\\d+)?s\"").find(err)?.groupValues?.get(1)?.toLongOrNull()
                        if (delay != null) waitMs = maxOf(waitMs, delay * 1000)
                        if (err.contains("PerDay", ignoreCase = true)) daily = true
                        android.util.Log.w("HaloVoice", "$model: HTTP 429 (retry in ${delay ?: "?"}s, daily=${err.contains("PerDay", true)})")
                    } else {
                        android.util.Log.w("HaloVoice", "$model: HTTP 404, trying next model")
                    }
                    i++
                    // Only a short per-minute limit everywhere: wait once and retry the first model.
                    if (i == MODELS.size && quotaHits > 0 && !daily && !retried && waitMs in 1..9_000) {
                        retried = true
                        Thread.sleep(waitMs + 300)
                        i = 0
                        quotaHits = 0
                    }
                    continue
                }
                val stream = (if (code in 200..299) c.inputStream else c.errorStream) ?: return null
                val text = stream.bufferedReader().use { it.readText() }
                if (code !in 200..299) error("HTTP $code ${text.take(200)}")
                val json = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
                val parts = json["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject?.get("parts")?.jsonArray ?: return null
                val data = parts.firstNotNullOfOrNull { p ->
                    p.jsonObject["inlineData"]?.jsonObject?.get("data")?.jsonPrimitive?.content
                } ?: return null
                return android.util.Base64.decode(data, android.util.Base64.DEFAULT)
            } finally {
                c.disconnect()
            }
        }
        if (quotaHits > 0) {
            // Per-minute limits clear quickly; only a used-up daily quota pauses the natural voice longer.
            val pause = if (daily) 30 * 60_000L else maxOf(waitMs, 15_000L)
            quotaUntil = System.currentTimeMillis() + pause
            error("Gemini TTS rate-limited; TV voice for ${pause / 1000}s")
        }
        return null
    }

    private fun play(pcm: ByteArray) {
        val t = android.media.AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                android.media.AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(android.media.AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size)
            .build()
        t.write(pcm, 0, pcm.size)
        track = t
        t.play()
        // Release once finished (2 bytes per sample).
        val ms = pcm.size / 2 * 1000L / SAMPLE_RATE + 500
        scope.launch {
            kotlinx.coroutines.delay(ms)
            if (track === t) track = null
            runCatching { t.stop() }
            runCatching { t.release() }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        track?.let { t ->
            track = null
            runCatching { t.stop() }
            runCatching { t.release() }
        }
        runCatching { tts.stop() }
    }

    fun shutdown() {
        stop()
        scope.cancel()
        runCatching { tts.shutdown() }
    }
}
