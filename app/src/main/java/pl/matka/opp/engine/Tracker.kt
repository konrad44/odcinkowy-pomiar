package pl.matka.opp.engine

import pl.matka.opp.geo.Geo
import pl.matka.opp.geo.LatLon
import kotlin.math.max
import kotlin.math.roundToInt

/** Pojedynczy odczyt GPS. Czas w ms z zegara monotonicznego (np. elapsedRealtime). */
data class Fix(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Double? = null,
    val bearingDeg: Double? = null,
)

/** Co pokazuje wyspa. */
sealed interface StanWyspy {
    data class Czuwanie(val gps: Boolean) : StanWyspy
    data class Zapowiedz(val odcinek: Odcinek, val dystansM: Int) : StanWyspy
    data class NaOdcinku(
        val odcinek: Odcinek,
        /** null gdy nieznany wjazd. */
        val dozwolonaKmh: Int?,
        val nieznanyWjazd: Boolean,
        val szacowane: Boolean,
        /** 0..1 – przejechana część odcinka. */
        val postep: Float,
        val pozostaloM: Int,
        val kolor: Kolor,
    ) : StanWyspy
    data class Podsumowanie(val odcinek: Odcinek, val sredniaKmh: Int?) : StanWyspy
}

/** Zdarzenia dla dźwięku / głosu. */
sealed interface Zdarzenie {
    data class Zapowiedz(val odcinek: Odcinek, val dystansM: Int) : Zdarzenie
    data class Wjazd(val odcinek: Odcinek, val nieznany: Boolean) : Zdarzenie
    /** Emitowane przy każdym przeliczeniu w kolorze czerwonym – powiadamiacz sam dławi częstotliwość. */
    data class ZaSzybko(val dozwolonaKmh: Int) : Zdarzenie
    data class Zjazd(val odcinek: Odcinek, val sredniaKmh: Int?, val zTrasy: Boolean) : Zdarzenie
}

/**
 * Silnik wykrywania odcinków. Czysty Kotlin bez zależności od Androida – testowalny.
 * Wołaj [onFix] dla każdego odczytu GPS i [onTick] co ~1 s.
 */
class Tracker(
    odcinki: List<Odcinek>,
    private val zapasKmh: () -> Int,
) {
    companion object {
        const val ZAPOWIEDZ_M = 500.0
        const val ZAPOWIEDZ_OKNO_M = 550.0
        const val MAX_CROSS_WJAZD_M = 40.0
        const val MAX_KAT_WJAZD = 45.0
        const val ZJAZD_CROSS_M = 150.0
        const val ZJAZD_CZAS_MS = 20_000L
        const val GPS_STARY_MS = 3_000L
        const val GPS_BRAK_MS = 5_000L
        const val PODSUMOWANIE_MS = 10_000L
        const val MIN_PREDKOSC_NIEZNANY_MPS = 20 / 3.6
        /** Bez GPS nie kończymy odcinka na szacunku – dopiero gdy brak sygnału trwa tyle po szacowanym końcu. */
        const val KONIEC_BEZ_GPS_MS = 60_000L
        /** Jak długo pamiętamy zapowiedź do odtworzenia czasu wjazdu. */
        const val PAMIEC_ZAPOWIEDZI_MS = 15_000L
        const val KAT_ZAWROCENIA = 135.0
        const val COFNIECIE_M = 100.0
    }

    private class Aktywny(
        val odcinek: Odcinek,
        /** null = nieznany wjazd. */
        val wjazdMs: Long?,
        var along: Double,
        var alongMs: Long,
        var pozaTrasaOd: Long? = null,
    ) {
        var maxAlong = along
        var wstecz = 0
    }

    private var odcinki: List<Odcinek> = odcinki
    private var aktywny: Aktywny? = null
    private var zapowiadany: Odcinek? = null
    private var zapowiadanyDystans = 0.0
    private val zapowiedziane = HashSet<String>()
    /** Ostatnio zapowiedziany odcinek i czas – nie kasowany przez pojedynczy zły odczyt. */
    private var ostatnioZapowiedziany: Pair<String, Long>? = null
    private var podsumowanie: StanWyspy.Podsumowanie? = null
    private var podsumowanieDo = 0L

    private var ostatniFix: Fix? = null
    private var predkoscMps: Double? = null
    private var kurs: Double? = null

    var stan: StanWyspy = StanWyspy.Czuwanie(false)
        private set

    val naOdcinku: Boolean get() = aktywny != null

    /** Podmiana listy odcinków (np. po aktualizacji). Ignorowana w trakcie odcinka. */
    fun ustawOdcinki(nowe: List<Odcinek>): Boolean {
        if (aktywny != null) return false
        odcinki = nowe
        zapowiadany = null
        return true
    }

    fun onFix(fix: Fix): List<Zdarzenie> {
        val ev = ArrayList<Zdarzenie>()
        val p = LatLon(fix.lat, fix.lon)
        val prev = ostatniFix
        // Odczyt starszy niż poprzedni (np. z pamięci podręcznej) – pomijamy.
        if (prev != null && fix.timeMs <= prev.timeMs) return emptyList()

        // Prędkość i kurs – z GPS, a gdy brak, z dwóch ostatnich pozycji.
        var v = fix.speedMps
        var k = if (fix.bearingDeg != null && (fix.speedMps ?: 0.0) >= 2.0) fix.bearingDeg else null
        if (prev != null) {
            val dt = (fix.timeMs - prev.timeMs) / 1000.0
            val pp = LatLon(prev.lat, prev.lon)
            val d = Geo.distance(pp, p)
            if (v == null && dt > 0 && dt < 10) v = d / dt
            if (k == null && d >= 8.0) k = Geo.bearing(pp, p)
        }
        if (v != null) predkoscMps = v
        if (k != null) kurs = k

        val a = aktywny
        if (a != null) {
            if (obsluzAktywny(a, fix, p, ev)) wykryj(fix, p, prev, ev)
        } else {
            wykryj(fix, p, prev, ev)
        }
        ostatniFix = fix
        przelicz(fix.timeMs, ev)
        return ev
    }

    fun onTick(nowMs: Long): List<Zdarzenie> {
        val ev = ArrayList<Zdarzenie>()
        val a = aktywny
        val lf = ostatniFix
        if (a != null && (lf == null || nowMs - lf.timeMs > GPS_STARY_MS)) {
            // Brak GPS – na samym szacunku nie kończymy (może to być korek w tunelu).
            // Dopiero długo po szacowanym końcu zamykamy odcinek, bez wymyślonej średniej.
            val koniecSzac = a.alongMs + (((a.odcinek.trasa.length - a.along) /
                max(predkoscMps ?: 0.1, 0.1)) * 1000).toLong()
            if (nowMs - koniecSzac >= KONIEC_BEZ_GPS_MS) zakoncz(a, null, nowMs, ev)
        }
        przelicz(nowMs, ev)
        return ev
    }

    /** @return true, gdy odcinek zakończono przez zawrócenie – trzeba od razu szukać nowego. */
    private fun obsluzAktywny(a: Aktywny, fix: Fix, p: LatLon, ev: MutableList<Zdarzenie>): Boolean {
        val trasa = a.odcinek.trasa
        // Po luce w GPS szukamy wokół szacowanej pozycji, a gdy daleko – na całej trasie.
        val hint = a.along + (predkoscMps ?: 0.0) * (fix.timeMs - a.alongMs) / 1000.0
        var pr = trasa.project(p, hint)
        if (pr.crossTrack > ZJAZD_CROSS_M) pr = trasa.project(p, null)
        if (pr.crossTrack > ZJAZD_CROSS_M) {
            val od = a.pozaTrasaOd ?: fix.timeMs.also { a.pozaTrasaOd = it }
            if (fix.timeMs - od >= ZJAZD_CZAS_MS) {
                aktywny = null
                zapowiedziane.add(a.odcinek.id)
                ev.add(Zdarzenie.Zjazd(a.odcinek, null, zTrasy = true))
            }
            return false
        }
        a.pozaTrasaOd = null
        val along = pr.along.coerceAtLeast(0.0)
        // Zawrócenie: kurs przeciwny do trasy przez kilka odczytów albo wyraźne cofnięcie się.
        val k = kurs
        a.wstecz = if (k != null && Geo.angleDiff(k, pr.segmentBearing) > KAT_ZAWROCENIA) a.wstecz + 1 else 0
        if (a.wstecz >= 3 || along < a.maxAlong - COFNIECIE_M) {
            aktywny = null
            zapowiedziane.add(a.odcinek.id)
            ev.add(Zdarzenie.Zjazd(a.odcinek, null, zTrasy = true))
            return true
        }
        if (along >= trasa.length) {
            // Interpolacja momentu przejechania punktu końcowego.
            val dAlong = along - a.along
            val koniecMs = if (dAlong > 0) {
                a.alongMs + ((trasa.length - a.along) / dAlong * (fix.timeMs - a.alongMs)).toLong()
            } else fix.timeMs
            zakoncz(a, koniecMs, fix.timeMs, ev)
            return false
        }
        a.along = along
        a.alongMs = fix.timeMs
        if (along > a.maxAlong) a.maxAlong = along
        return false
    }

    /** @param koniecMs null = moment wyjazdu nieznany (brak GPS) – bez średniej. */
    private fun zakoncz(a: Aktywny, koniecMs: Long?, nowMs: Long, ev: MutableList<Zdarzenie>) {
        aktywny = null
        zapowiedziane.add(a.odcinek.id)
        val sr = if (koniecMs == null) null else a.wjazdMs?.let { w ->
            val s = (koniecMs - w) / 1000.0
            if (s > 0) (a.odcinek.dlugoscM / s * 3.6).roundToInt() else null
        }
        ev.add(Zdarzenie.Zjazd(a.odcinek, sr, zTrasy = false))
        podsumowanie = StanWyspy.Podsumowanie(a.odcinek, sr)
        podsumowanieDo = nowMs + PODSUMOWANIE_MS
    }

    private fun wykryj(fix: Fix, p: LatLon, prev: Fix?, ev: MutableList<Zdarzenie>) {
        val k = kurs
        var najblizszaZapowiedz: Odcinek? = null
        var najblizszaDyst = Double.MAX_VALUE

        for (o in odcinki) {
            val trasa = o.trasa
            if (!trasa.boxContains(p, 1000.0)) continue
            val pr = trasa.project(p)
            val kierunekOk = k != null && Geo.angleDiff(k, pr.segmentBearing) <= MAX_KAT_WJAZD

            if (pr.along >= 0 && pr.along < trasa.length && pr.crossTrack <= MAX_CROSS_WJAZD_M && kierunekOk) {
                // Jesteśmy na odcinku. Czy znamy moment wjazdu?
                var wjazdMs: Long? = null
                if (prev != null && fix.timeMs - prev.timeMs <= 5_000) {
                    val pp = trasa.project(LatLon(prev.lat, prev.lon))
                    if (pp.along < 0 && pp.crossTrack <= 80.0) {
                        val f = -pp.along / (pr.along - pp.along)
                        wjazdMs = prev.timeMs + (f * (fix.timeMs - prev.timeMs)).toLong()
                    }
                }
                val v = predkoscMps ?: 0.0
                val zap = ostatnioZapowiedziany
                val zapId = if (zap != null && fix.timeMs - zap.second <= PAMIEC_ZAPOWIEDZI_MS) zap.first else null
                if (wjazdMs == null && zapId == o.id && v > 1.0 && pr.along <= v * 6 + 50) {
                    // Krótka luka w GPS tuż przy bramce – odtwarzamy czas wjazdu z prędkości.
                    wjazdMs = fix.timeMs - (pr.along / v * 1000).toLong()
                }
                if (wjazdMs == null && v < MIN_PREDKOSC_NIEZNANY_MPS) continue

                aktywny = Aktywny(o, wjazdMs, pr.along, fix.timeMs)
                zapowiadany = null
                ostatnioZapowiedziany = null
                podsumowanie = null
                ev.add(Zdarzenie.Wjazd(o, nieznany = wjazdMs == null))
                return
            }

            // Zapowiedź: zbliżamy się do punktu początkowego, jadąc w jego stronę.
            if (k != null && pr.along < 0) {
                val d = Geo.distance(p, trasa.start)
                if (d <= ZAPOWIEDZ_OKNO_M &&
                    Geo.angleDiff(k, Geo.bearing(p, trasa.start)) <= 50.0 &&
                    Geo.angleDiff(k, trasa.startBearing) <= 90.0 &&
                    d < najblizszaDyst
                ) {
                    najblizszaDyst = d
                    najblizszaZapowiedz = o
                }
            }
        }

        // Odcinki, od których się oddaliliśmy, można znów zapowiedzieć.
        if (zapowiedziane.isNotEmpty()) {
            zapowiedziane.removeAll { id ->
                val o = odcinki.firstOrNull { it.id == id } ?: return@removeAll true
                Geo.distance(p, o.trasa.start) > 1500.0 && Geo.distance(p, o.trasa.end) > 1500.0
            }
        }

        val z = najblizszaZapowiedz
        if (z != null) {
            zapowiadany = z
            zapowiadanyDystans = najblizszaDyst
            ostatnioZapowiedziany = z.id to fix.timeMs
            if (zapowiedziane.add(z.id)) {
                ev.add(Zdarzenie.Zapowiedz(z, roundTo50(najblizszaDyst)))
            }
        } else {
            zapowiadany = null
        }
    }

    private fun przelicz(nowMs: Long, ev: MutableList<Zdarzenie>) {
        val lf = ostatniFix
        val gps = lf != null && nowMs - lf.timeMs <= GPS_BRAK_MS
        val a = aktywny
        if (a != null) {
            val szac = lf == null || nowMs - lf.timeMs > GPS_STARY_MS
            val trasa = a.odcinek.trasa
            val along = if (szac) {
                a.along + (predkoscMps ?: 0.0) * (nowMs - a.alongMs) / 1000.0
            } else a.along
            val alongC = along.coerceIn(0.0, trasa.length)
            val pozostaloOficjalnie = a.odcinek.dlugoscM * (trasa.length - alongC) / trasa.length
            val dozw: Double? = a.wjazdMs?.let { w ->
                Calc.dozwolonaKmh(
                    a.odcinek.dlugoscM, a.odcinek.limit, zapasKmh(),
                    (nowMs - w) / 1000.0, pozostaloOficjalnie,
                )
            }
            // Kolor liczony z tej samej (obciętej) wartości, którą widzi kierowca.
            val dozwInt = dozw?.toInt()
            val kolor = if (a.wjazdMs == null) Kolor.SZARY
            else Calc.kolor(predkoscMps?.times(3.6), dozwInt?.toDouble(), a.odcinek.limit, szac)
            stan = StanWyspy.NaOdcinku(
                odcinek = a.odcinek,
                dozwolonaKmh = dozwInt,
                nieznanyWjazd = a.wjazdMs == null,
                szacowane = szac,
                postep = (alongC / trasa.length).toFloat(),
                pozostaloM = pozostaloOficjalnie.roundToInt(),
                kolor = kolor,
            )
            if (kolor == Kolor.CZERWONY && dozwInt != null) ev.add(Zdarzenie.ZaSzybko(dozwInt))
            return
        }
        val ps = podsumowanie
        if (ps != null && nowMs < podsumowanieDo) {
            stan = ps
            return
        }
        podsumowanie = null
        val z = zapowiadany
        stan = if (z != null && gps) {
            val d = if (lf != null) Geo.distance(LatLon(lf.lat, lf.lon), z.trasa.start) else zapowiadanyDystans
            StanWyspy.Zapowiedz(z, roundTo50(d))
        } else StanWyspy.Czuwanie(gps)
    }

    private fun roundTo50(d: Double): Int = max(50, ((d / 50.0).roundToInt() * 50))
}
