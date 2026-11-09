package pl.matka.opp.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import pl.matka.opp.engine.Fix
import pl.matka.opp.engine.Fotoradar
import pl.matka.opp.engine.Kolor
import pl.matka.opp.engine.Odcinek
import pl.matka.opp.engine.StanWyspy
import pl.matka.opp.engine.Tracker
import pl.matka.opp.engine.Zdarzenie
import pl.matka.opp.geo.LatLon
import pl.matka.opp.geo.Polyline
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tryb demo: symulowany przejazd bez auta i bez GPS. Ten sam silnik, wyspa i dźwięki co w jeździe,
 * tylko pozycje są wymyślone, a czas leci [PRZYSPIESZENIE] razy szybciej.
 *
 * Scenariusz (odległości względem początku odcinka, oś na północ):
 *  -2500 start, 90 km/h → fotoradar 70 na -1500 (zwalniamy do 70) → zapowiedź odcinka →
 *  odcinek 3 km, limit 90: spokojnie 85 → za szybko 108 (czerwony) → wolniej 72 (odrabia średnią) →
 *  krótka utrata GPS (szary) → 85 do końca → podsumowanie.
 */
object Demo {
    const val PRZYSPIESZENIE = 3
    private const val KROK_MS = 1000L

    private val lat0 = 52.0
    private val lon0 = 19.0
    private fun lat(m: Double) = lat0 + m / 110_540.0

    private val odcinek = Odcinek(
        "demo", "Odcinek demonstracyjny", "DK demo", 90, 3_000.0,
        Polyline(listOf(LatLon(lat0, lon0), LatLon(lat(1_500.0), lon0), LatLon(lat(3_000.0), lon0))),
    )
    private val fotoradar = Fotoradar(LatLon(lat(-1_500.0), lon0), 70, 0.0)

    /** Docelowa prędkość (km/h) w danym miejscu trasy. */
    private fun cel(m: Double): Double = when {
        m < -1_850 -> 90.0
        m < -1_450 -> 68.0
        m < 0 -> 88.0
        m < 700 -> 85.0
        m < 1_500 -> 108.0
        m < 2_300 -> 72.0
        else -> 85.0
    }

    private fun etap(m: Double): String = when {
        m < -1_500 -> "zbliżanie do fotoradaru"
        m < 0 -> "dojazd do odcinka"
        m < 700 -> "na odcinku – spokojnie"
        m < 1_500 -> "na odcinku – za szybko"
        m < 2_300 -> "na odcinku – odrabianie średniej"
        m < 2_550 -> "na odcinku – utrata GPS (np. tunel)"
        m < 3_000 -> "na odcinku – końcówka"
        else -> "za odcinkiem – podsumowanie"
    }

    private const val START_M = -2_500.0
    private const val KONIEC_M = 3_100.0
    private const val BRAK_GPS_OD = 2_300.0
    private const val BRAK_GPS_DO = 2_550.0

    private val handler = Handler(Looper.getMainLooper())
    private var wyspa: WyspaOverlay? = null
    private var glos: Powiadamiacz? = null
    private var tracker: Tracker? = null
    private var pozycja = START_M
    private var vMps = 90 / 3.6
    private var czasMs = 0L

    /** Wołane po każdym kroku: tekst stanu do pokazania w aplikacji; null = demo zakończone. */
    var sluchacz: ((String?) -> Unit)? = null

    val dziala: Boolean get() = tracker != null

    fun start(ctx: Context) {
        if (dziala) return
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        tracker = Tracker(listOf(odcinek), { prefs.zapasKmh }, listOf(fotoradar), { prefs.fotoradary })
        wyspa = WyspaOverlay(app, prefs).also { it.pokaz() }
        glos = Powiadamiacz(app, prefs)
        pozycja = START_M
        vMps = cel(START_M) / 3.6
        czasMs = 0L
        handler.post(krok)
    }

    fun stop() {
        handler.removeCallbacks(krok)
        wyspa?.ukryj()
        glos?.zamknij()
        wyspa = null
        glos = null
        tracker = null
        sluchacz?.invoke(null)
    }

    private val krok = object : Runnable {
        override fun run() {
            val t = tracker ?: return
            czasMs += KROK_MS
            // Płynna zmiana prędkości: ~2,5 m/s² w obie strony.
            val docel = cel(pozycja) / 3.6
            val dv = docel - vMps
            vMps += if (abs(dv) < 2.5) dv else 2.5 * Math.signum(dv)
            pozycja += vMps * KROK_MS / 1000.0

            val ev = ArrayList<Zdarzenie>()
            val bezGps = pozycja in BRAK_GPS_OD..BRAK_GPS_DO
            if (!bezGps) ev += t.onFix(Fix(czasMs, lat(pozycja), lon0, vMps, 0.0))
            ev += t.onTick(czasMs)
            ev.forEach { glos?.obsluz(it) }
            val st = t.stan
            wyspa?.ustaw(st)
            sluchacz?.invoke(opis(st, bezGps))

            // Koniec, gdy zniknie podsumowanie (Tracker trzyma je 10 s).
            if (pozycja >= KONIEC_M && st !is StanWyspy.Podsumowanie) {
                stop()
                return
            }
            // Za odcinkiem czas w normalnym tempie – żeby podsumowanie dało się przeczytać.
            handler.postDelayed(this, if (pozycja > odcinek.dlugoscM) KROK_MS else KROK_MS / PRZYSPIESZENIE)
        }
    }

    private fun opis(st: StanWyspy, bezGps: Boolean): String {
        val kmh = (vMps * 3.6).roundToInt()
        val gdzie = when {
            pozycja < 0 -> "${(-pozycja).roundToInt()} m przed odcinkiem"
            pozycja <= odcinek.dlugoscM -> "${pozycja.roundToInt()} / ${odcinek.dlugoscM.roundToInt()} m odcinka"
            else -> "za odcinkiem"
        }
        val wyspaTxt = when (st) {
            is StanWyspy.Czuwanie -> "czuwanie"
            is StanWyspy.Zapowiedz -> "zapowiedź: za ${st.dystansM} m, limit ${st.odcinek.limit}"
            is StanWyspy.NaOdcinku -> "dozwolona ${st.dozwolonaKmh ?: "—"} km/h, kolor: " + when (st.kolor) {
                Kolor.ZIELONY -> "zielony"
                Kolor.ZOLTY -> "żółty"
                Kolor.CZERWONY -> "czerwony"
                Kolor.NIEBIESKI -> "niebieski"
                Kolor.SZARY -> "szary"
            }
            is StanWyspy.Podsumowanie -> "koniec, średnia ${st.sredniaKmh ?: "—"} km/h"
        }
        val postep = min(100, ((pozycja - START_M) / (KONIEC_M - START_M) * 100).roundToInt())
        return "DEMO ($postep%) – ${etap(pozycja)}\n" +
            "Jedziesz $kmh km/h, $gdzie${if (bezGps) ", brak GPS" else ""}\n" +
            "Wyspa: $wyspaTxt"
    }
}
