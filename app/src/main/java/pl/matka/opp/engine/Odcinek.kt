package pl.matka.opp.engine

import org.json.JSONObject
import pl.matka.opp.geo.LatLon
import pl.matka.opp.geo.Polyline

/** Jeden kierunek odcinkowego pomiaru prędkości. */
class Odcinek(
    val id: String,
    val nazwa: String,
    val droga: String,
    val limit: Int,
    /** Oficjalna długość (do liczenia czasu); jeśli brak – długość trasy. */
    val dlugoscM: Double,
    val trasa: Polyline,
)

class OdcinkiDane(val wersja: Int, val aktualizacja: String, val odcinki: List<Odcinek>)

object OdcinkiParser {
    const val MIN_DLUGOSC_M = 500.0

    fun parse(json: String): OdcinkiDane {
        val root = JSONObject(json)
        val arr = root.getJSONArray("odcinki")
        val list = ArrayList<Odcinek>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val t = o.getJSONArray("trasa")
            val pts = ArrayList<LatLon>(t.length())
            for (j in 0 until t.length()) {
                val p = t.getJSONArray(j)
                pts.add(LatLon(p.getDouble(0), p.getDouble(1)))
            }
            if (pts.size < 2) continue
            val poly = Polyline(pts)
            val limit = o.getInt("limit")
            if (limit <= 0) continue
            // Zdegenerowana lub podejrzanie krótka trasa – pomijamy (chroni też przed dzieleniem przez zero).
            if (poly.length < MIN_DLUGOSC_M) continue
            val dl = o.optDouble("dlugosc_m", Double.NaN).let { if (it.isNaN() || it <= 0) poly.length else it }
            if (dl / poly.length !in 0.8..1.25) continue
            list.add(
                Odcinek(
                    id = o.getString("id"),
                    nazwa = o.optString("nazwa", ""),
                    droga = o.optString("droga", ""),
                    limit = limit,
                    dlugoscM = dl,
                    trasa = poly,
                )
            )
        }
        return OdcinkiDane(root.optInt("wersja", 1), root.optString("aktualizacja", ""), list)
    }
}
