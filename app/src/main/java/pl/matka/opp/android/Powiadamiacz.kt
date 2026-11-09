package pl.matka.opp.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import pl.matka.opp.engine.Zdarzenie
import java.util.Locale
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Dźwięki i komunikaty głosowe. Ścisza muzykę na czas komunikatu. */
class Powiadamiacz(ctx: Context, private val prefs: Prefs) : TextToSpeech.OnInitListener {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()

    private var tts: TextToSpeech? = TextToSpeech(ctx.applicationContext, this)
    /** Silnik gotowy i ma polski głos. Bez polskiego głosu grają sygnały. */
    @Volatile private var ttsGotowy = false
    @Volatile private var ttsZainicjowany = false
    /** Tekst testu zamówiony przed końcem inicjalizacji TTS. */
    private var czekajacyTest = false
    private var ostatnieOstrzezenie = 0L
    /** Ile dźwięków/komunikatów trzyma teraz fokus audio (ściszenie muzyki). Tylko wątek główny. */
    private var aktywne = 0

    override fun onInit(status: Int) {
        // onInit bywa wołany z innego wątku – resztę robimy na głównym.
        handler.post {
            val t = tts ?: return@post
            if (status == TextToSpeech.SUCCESS) {
                val r = t.setLanguage(Locale("pl", "PL"))
                t.setAudioAttributes(attrs)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { handler.post { zwolnijFokus() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { handler.post { zwolnijFokus() } }
                    override fun onStop(id: String?, interrupted: Boolean) { handler.post { zwolnijFokus() } }
                })
                ttsGotowy = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            }
            ttsZainicjowany = true
            if (czekajacyTest) {
                czekajacyTest = false
                test()
            }
        }
    }

    /** null = jeszcze nie wiadomo (TTS się inicjuje). */
    val polskiGlos: Boolean? get() = if (ttsZainicjowany) ttsGotowy else null

    private fun wezFokus() {
        if (aktywne++ == 0) am.requestAudioFocus(focus)
    }

    private fun zwolnijFokus() {
        if (aktywne == 0) return
        if (--aktywne == 0) am.abandonAudioFocusRequest(focus)
    }

    fun obsluz(z: Zdarzenie) {
        val tryb = prefs.tryb
        if (tryb == Tryb.CICHY) return
        val glos = tryb == Tryb.GLOS && ttsGotowy
        if (z is Zdarzenie.ZaSzybko) {
            // Sygnał co 10 s, głos rzadziej – powtarzane zdanie bardziej rozprasza.
            val now = SystemClock.elapsedRealtime()
            if (now - ostatnieOstrzezenie < if (glos) 30_000 else 10_000) return
            ostatnieOstrzezenie = now
        }
        if (z is Zdarzenie.Wjazd) ostatnieOstrzezenie = SystemClock.elapsedRealtime() // nie ostrzegaj tuż po wjeździe
        if (glos) mow(tekst(z), przerwij = z !is Zdarzenie.ZaSzybko) else sygnal(z)
    }

    fun test() {
        if (prefs.tryb == Tryb.GLOS && !ttsZainicjowany) {
            czekajacyTest = true
            return
        }
        if (prefs.tryb == Tryb.GLOS && ttsGotowy) mow("Odcinkowy pomiar prędkości, limit 90, 5 kilometrów", true)
        else if (prefs.tryb != Tryb.CICHY) graj(listOf(880 to 150, 0 to 80, 1320 to 200))
    }

    private fun tekst(z: Zdarzenie): String = when (z) {
        is Zdarzenie.Zapowiedz -> "Za ${z.dystansM} metrów odcinkowy pomiar prędkości, limit ${z.odcinek.limit}"
        is Zdarzenie.Wjazd -> if (z.nieznany) {
            "Odcinkowy pomiar, limit ${z.odcinek.limit}. Brak danych od wjazdu, trzymaj limit"
        } else {
            "Odcinkowy pomiar, limit ${z.odcinek.limit}, ${km(z.odcinek.dlugoscM)}"
        }
        is Zdarzenie.ZaSzybko -> "Zwolnij, maksymalnie ${z.dozwolonaKmh}"
        is Zdarzenie.Fotoradar -> "Fotoradar za ${z.dystansM} metrów" + (z.limit?.let { ", limit $it" } ?: "")
        is Zdarzenie.Zjazd -> when {
            z.zTrasy -> "Opuszczono odcinek"
            z.sredniaKmh != null -> "Koniec odcinka, średnia ${z.sredniaKmh}"
            else -> "Koniec odcinka"
        }
    }

    private fun km(m: Double): String {
        val k = m / 1000.0
        if (k < 10) {
            val r = (k * 10).roundToInt() / 10.0
            if (r != r.toInt().toDouble()) return r.toString().replace('.', ',') + " kilometra"
        }
        val n = k.roundToInt().coerceAtLeast(1)
        val o = when {
            n == 1 -> "kilometr"
            n % 10 in 2..4 && n % 100 !in 12..14 -> "kilometry"
            else -> "kilometrów"
        }
        return "$n $o"
    }

    /** @param przerwij true = komunikat ważniejszy od bieżącego (zapowiedź, wjazd, zjazd). */
    private fun mow(t: String, przerwij: Boolean) {
        val e = tts ?: return
        // Przy QUEUE_FLUSH przerwany komunikat zwolni fokus przez onStop.
        wezFokus()
        val q = if (przerwij) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (e.speak(t, q, null, "opp-${SystemClock.elapsedRealtime()}") != TextToSpeech.SUCCESS) zwolnijFokus()
    }

    private fun sygnal(z: Zdarzenie) = graj(
        when (z) {
            is Zdarzenie.Zapowiedz -> listOf(880 to 180)
            is Zdarzenie.Wjazd -> listOf(880 to 150, 0 to 80, 1320 to 200)
            is Zdarzenie.ZaSzybko -> listOf(1500 to 110, 0 to 70, 1500 to 110, 0 to 70, 1500 to 110)
            is Zdarzenie.Zjazd -> listOf(1320 to 150, 0 to 80, 880 to 220)
            // Inny niż zapowiedź OPP: trzy szybkie niskie + długi, żeby rozróżnić na ucho.
            is Zdarzenie.Fotoradar -> listOf(660 to 90, 0 to 50, 660 to 90, 0 to 50, 660 to 90, 0 to 80, 990 to 300)
        }
    )

    /** Sekwencja (częstotliwość Hz, czas ms); 0 Hz = cisza. */
    private fun graj(seq: List<Pair<Int, Int>>) {
        val rate = 22_050
        val total = seq.sumOf { it.second } * rate / 1000
        val buf = ShortArray(total)
        var i = 0
        for ((f, ms) in seq) {
            val n = ms * rate / 1000
            val fade = (rate * 0.008).toInt()
            for (j in 0 until n) {
                if (i >= total) break
                val env = minOf(1.0, j / fade.toDouble(), (n - j) / fade.toDouble())
                buf[i++] = if (f == 0) 0 else (sin(2 * PI * f * j / rate) * env * 0.6 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buf.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(buf, 0, buf.size)
        wezFokus()
        track.play()
        handler.postDelayed({
            runCatching { track.stop() }
            track.release()
            zwolnijFokus()
        }, seq.sumOf { it.second } + 150L)
    }

    fun zamknij() {
        ttsGotowy = false
        tts?.stop()
        tts?.shutdown()
        tts = null
        if (aktywne > 0) am.abandonAudioFocusRequest(focus)
        aktywne = 0
    }
}
