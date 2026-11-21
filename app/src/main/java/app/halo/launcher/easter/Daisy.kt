package app.halo.launcher.easter

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * "Daisy Bell" (Harry Dacre, 1892, public domain), sung by a tiny formant synthesizer in the style of
 * the early singing computers: a buzzy sawtooth "voice" shaped by three vowel filters, with vibrato.
 * Everything is generated on the TV; no recording is used.
 */
object Daisy {
    /** One sung syllable: its text, vowel (a e i o u), MIDI note and length in beats. [line] = starts a new lyric line. */
    class Note(val text: String, val vowel: Char, val midi: Int, val beats: Float, val line: Boolean = false)

    /** Seconds per beat (3/4 time, an easy waltz). */
    const val BEAT = 0.40f
    private const val RATE = 22_050

    private const val G3 = 55; private const val A3 = 57; private const val B3 = 59; private const val C4 = 60
    private const val D4 = 62; private const val E4 = 64; private const val F4 = 65; private const val G4 = 67

    val song: List<Note> = listOf(
        Note("Dai", 'a', G4, 3f, line = true), Note("sy, ", 'i', E4, 3f),
        Note("Dai", 'a', C4, 3f), Note("sy, ", 'i', G3, 3f),
        Note("give ", 'i', A3, 1f), Note("me ", 'i', B3, 1f), Note("your ", 'o', C4, 1f),
        Note("an", 'a', A3, 2f), Note("swer ", 'e', C4, 1f), Note("do.", 'u', G3, 5f),
        Note("I'm ", 'a', D4, 3f, line = true), Note("half ", 'a', G4, 3f),
        Note("cra", 'e', E4, 3f), Note("zy, ", 'i', C4, 3f),
        Note("all ", 'o', A3, 1f), Note("for ", 'o', B3, 1f), Note("the ", 'e', C4, 1f),
        Note("love ", 'u', D4, 2f), Note("of ", 'o', E4, 1f), Note("you.", 'u', D4, 5f),
        Note("It ", 'i', E4, 1f, line = true), Note("won't ", 'o', F4, 1f), Note("be ", 'i', E4, 1f), Note("a ", 'a', D4, 1f),
        Note("sty", 'a', G4, 2f), Note("lish ", 'i', E4, 1f), Note("mar", 'a', D4, 1f), Note("riage, ", 'i', C4, 3f),
        Note("I ", 'a', D4, 1f, line = true), Note("can't ", 'a', E4, 1f), Note("af", 'a', C4, 1f),
        Note("ford ", 'o', A3, 2f), Note("a ", 'a', C4, 1f), Note("car", 'a', A3, 1f), Note("riage, ", 'i', G3, 4f),
        Note("But ", 'u', G3, 1f, line = true), Note("you'll ", 'u', C4, 2f), Note("look ", 'u', E4, 1f),
        Note("sweet ", 'i', D4, 2f), Note("up", 'u', G3, 1f), Note("on ", 'o', C4, 2f),
        Note("the ", 'e', E4, 1f), Note("seat ", 'i', D4, 2f),
        Note("of ", 'o', E4, 1f, line = true), Note("a ", 'a', F4, 1f), Note("bi", 'a', G4, 1f), Note("cy", 'i', E4, 1f),
        Note("cle ", 'e', C4, 1f), Note("built ", 'i', D4, 2f), Note("for ", 'o', G3, 1f), Note("two.", 'u', C4, 6f),
    )

    /** Start time of each note in seconds (after a short lead-in). */
    val starts: FloatArray by lazy {
        var t = LEAD_IN
        FloatArray(song.size) { i -> t.also { t += song[i].beats * BEAT } }
    }
    val duration: Float get() = starts.last() + song.last().beats * BEAT + 0.5f
    const val LEAD_IN = 0.3f

    /** Formants (Hz) of the five vowels: F1, F2, F3. */
    private val formants = mapOf(
        'a' to floatArrayOf(730f, 1090f, 2440f),
        'e' to floatArrayOf(530f, 1840f, 2480f),
        'i' to floatArrayOf(300f, 2200f, 2950f),
        'o' to floatArrayOf(570f, 840f, 2410f),
        'u' to floatArrayOf(330f, 900f, 2240f),
    )

    /** Renders the whole song as 16-bit mono PCM. */
    fun render(): ShortArray {
        val total = (duration * RATE).toInt()
        val out = FloatArray(total)
        var phase = 0.0
        // Three band-pass filters (state carried across notes for smooth vowel changes).
        val z1 = DoubleArray(3); val z2 = DoubleArray(3); val y1 = DoubleArray(3); val y2 = DoubleArray(3)
        val gains = doubleArrayOf(1.0, 0.55, 0.25)
        for ((i, n) in song.withIndex()) {
            val start = (starts[i] * RATE).toInt()
            val len = (n.beats * BEAT * RATE).toInt()
            val f0 = 440.0 * 2.0.pow((n.midi - 69) / 12.0)
            val f = formants[n.vowel] ?: formants.getValue('a')
            // RBJ band-pass coefficients for this vowel.
            val b0 = DoubleArray(3); val a1 = DoubleArray(3); val a2 = DoubleArray(3)
            for (k in 0..2) {
                val w0 = 2 * PI * f[k] / RATE
                val q = f[k] / (70.0 + 30.0 * k)
                val alpha = sin(w0) / (2 * q)
                val a0 = 1 + alpha
                b0[k] = alpha / a0
                a1[k] = -2 * cos(w0) / a0
                a2[k] = (1 - alpha) / a0
            }
            for (s in 0 until len) {
                val idx = start + s
                if (idx >= total) break
                val t = s.toDouble() / RATE
                // Vibrato that sets in after a moment, like a held sung note.
                val vib = 1.0 + 0.011 * min(1.0, t / 0.35) * sin(2 * PI * 5.4 * t)
                phase += f0 * vib / RATE
                if (phase >= 1.0) phase -= 1.0
                val src = 2.0 * phase - 1.0 // sawtooth: the buzzy "vocal cords"
                var v = 0.0
                for (k in 0..2) {
                    val y = b0[k] * src - b0[k] * z2[k] - a1[k] * y1[k] - a2[k] * y2[k]
                    z2[k] = z1[k]; z1[k] = src
                    y2[k] = y1[k]; y1[k] = y
                    v += gains[k] * y
                }
                // Envelope: soft attack, short release before the next syllable.
                val left = (len - s).toDouble() / RATE
                val env = min(1.0, t / 0.03) * min(1.0, left / 0.07)
                out[idx] += (v * env).toFloat()
            }
        }
        var peak = 1e-6f
        for (x in out) peak = maxOf(peak, abs(x))
        val k = 0.85f * Short.MAX_VALUE / peak
        return ShortArray(total) { (out[it] * k).toInt().coerceIn(-32767, 32767).toShort() }
    }

    /** Plays the song; [position] = seconds played. */
    class Player {
        private var track: AudioTrack? = null

        fun start(pcm: ShortArray) {
            stop()
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            t.write(pcm, 0, pcm.size)
            t.play()
            track = t
        }

        val position: Float get() = track?.let { runCatching { it.playbackHeadPosition.toFloat() / RATE }.getOrNull() } ?: 0f

        fun stop() {
            track?.let { runCatching { it.stop() }; it.release() }
            track = null
        }
    }
}
