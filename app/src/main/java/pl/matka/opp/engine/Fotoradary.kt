package pl.matka.opp.engine

import org.json.JSONObject
import pl.matka.opp.geo.Geo
import pl.matka.opp.geo.LatLon
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Stały fotoradar. [kierunek] = kierunek jazdy, którego dotyczy (stopnie); null = oba. */
data class Fotoradar(val pozycja: LatLon, val limit: Int?, val kierunek: Double?)

object FotoradaryParser {
    /** Format: {"fotoradary": [[lat, lon, limit (0 = brak), kierunek (-1 = oba)], ...]} */
    fun parse(json: String): List<Fotoradar> {
        val arr = JSONObject(json).getJSONArray("fotoradary")
        val out = ArrayList<Fotoradar>(arr.length())
        for (i in 0 until arr.length()) {
            val a = arr.getJSONArray(i)
            val lat = a.getDouble(0)
            val lon = a.getDouble(1)
            if (lat !in 48.9..55.0 || lon !in 14.0..24.2) continue
            // Limit spoza 20–140 to najpewniej błąd w OSM – traktujemy jak nieznany.
            val limit = a.optInt(2, 0).takeIf { it in 20..140 }
            val k = a.optDouble(3, -1.0).takeIf { it >= 0 }
            out.add(Fotoradar(LatLon(lat, lon), limit, k))
        }
        return out
    }
}

/**
 * Wykrywa fotoradar przed autem. Ostrzega raz na fotoradar; ponownie dopiero po oddaleniu się.
 * Czysty Kotlin – testowalny.
 */
class FotoradarDetektor(private var lista: List<Fotoradar>) {
    companion object {
        const val MIN_DYSTANS_M = 300.0
        const val MAX_DYSTANS_M = 800.0
        /** Ostrzeżenie ~15 s przed fotoradarem przy obecnej prędkości. */
        const val CZAS_S = 15.0
        const val MIN_PREDKOSC_MPS = 20 / 3.6
        /** Fotoradar musi być mniej więcej na wprost. */
        const val MAX_KAT_DO_FOTORADARU = 25.0
        /** Zgodność kierunku jazdy z kierunkiem fotoradaru. */
        const val MAX_KAT_KIERUNKU = 60.0
        const val RESET_M = 1_500.0
    }

    private val ostrzezone = HashSet<Fotoradar>()

    fun ustaw(nowa: List<Fotoradar>) {
        lista = nowa
        ostrzezone.clear()
    }

    fun onFix(p: LatLon, kurs: Double?, vMps: Double?): Zdarzenie.Fotoradar? {
        if (ostrzezone.isNotEmpty()) ostrzezone.removeAll { Geo.distance(p, it.pozycja) > RESET_M }
        if (kurs == null || vMps == null || vMps < MIN_PREDKOSC_MPS) return null
        val zasieg = min(MAX_DYSTANS_M, max(MIN_DYSTANS_M, vMps * CZAS_S))
        // Szybki filtr prostokątem, zanim policzymy odległości.
        val dLat = zasieg / 110_540.0
        val dLon = zasieg / (111_320.0 * cos(Math.toRadians(p.lat)))
        var najblizszy: Fotoradar? = null
        var najblizszyD = Double.MAX_VALUE
        for (f in lista) {
            if (abs(f.pozycja.lat - p.lat) > dLat || abs(f.pozycja.lon - p.lon) > dLon) continue
            if (f in ostrzezone) continue
            val d = Geo.distance(p, f.pozycja)
            if (d > zasieg || d < 30.0) continue
            if (Geo.angleDiff(kurs, Geo.bearing(p, f.pozycja)) > MAX_KAT_DO_FOTORADARU) continue
            if (f.kierunek != null && Geo.angleDiff(kurs, f.kierunek) > MAX_KAT_KIERUNKU) continue
            if (d < najblizszyD) {
                najblizszyD = d
                najblizszy = f
            }
        }
        val f = najblizszy ?: return null
        ostrzezone.add(f)
        return Zdarzenie.Fotoradar(max(50, ((najblizszyD / 50.0).roundToInt() * 50)), f.limit)
    }
}
