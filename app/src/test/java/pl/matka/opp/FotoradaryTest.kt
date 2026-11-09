package pl.matka.opp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.matka.opp.engine.Fix
import pl.matka.opp.engine.Fotoradar
import pl.matka.opp.engine.FotoradarDetektor
import pl.matka.opp.engine.FotoradaryParser
import pl.matka.opp.engine.Tracker
import pl.matka.opp.engine.Zdarzenie
import pl.matka.opp.geo.LatLon
import java.io.File

class FotoradaryTest {
    private val lat0 = 52.0
    private val lon0 = 19.0
    private fun p(northM: Double, eastM: Double = 0.0) =
        LatLon(lat0 + northM / 110_540.0, lon0 + eastM / (111_320.0 * Math.cos(Math.toRadians(lat0))))

    private val v90 = 25.0

    @Test
    fun ostrzegaRazPrzedFotoradaremNaWprost() {
        val d = FotoradarDetektor(listOf(Fotoradar(p(1_000.0), 70, 0.0)))
        val ostrzezenia = (0..40).mapNotNull { i -> d.onFix(p(i * 25.0), 0.0, v90) }
        val o = ostrzezenia.single()
        assertEquals(70, o.limit)
        // przy 90 km/h zasięg 15 s = 375 m
        assertTrue(o.dystansM in 350..400)
    }

    @Test
    fun nieOstrzegaWPrzeciwnymKierunku() {
        // fotoradar mierzy jadących na północ; jedziemy na południe
        val d = FotoradarDetektor(listOf(Fotoradar(p(0.0), 70, 0.0)))
        val ost = (0..40).mapNotNull { i -> d.onFix(p(1_000.0 - i * 25.0), 180.0, v90) }
        assertTrue(ost.isEmpty())
    }

    @Test
    fun bezKierunkuOstrzegaWObieStrony() {
        val d = FotoradarDetektor(listOf(Fotoradar(p(0.0), null, null)))
        assertNotNull((0..40).firstNotNullOfOrNull { i -> d.onFix(p(1_000.0 - i * 25.0), 180.0, v90) })
    }

    @Test
    fun nieOstrzegaOFotoradarzeZBoku() {
        // fotoradar 300 m na wschód od drogi
        val d = FotoradarDetektor(listOf(Fotoradar(p(1_000.0, 300.0), 70, null)))
        val ost = (0..40).mapNotNull { i -> d.onFix(p(i * 25.0), 0.0, v90) }
        assertTrue(ost.isEmpty())
    }

    @Test
    fun ponowneOstrzezeniePoOddaleniuSie() {
        val f = Fotoradar(p(500.0), 50, null)
        val d = FotoradarDetektor(listOf(f))
        assertNotNull(d.onFix(p(200.0), 0.0, v90))
        assertNull(d.onFix(p(250.0), 0.0, v90))
        d.onFix(p(5_000.0), 0.0, v90) // daleko – reset
        assertNotNull(d.onFix(p(200.0), 0.0, v90))
    }

    @Test
    fun wolnaJazdaBezOstrzezen() {
        val d = FotoradarDetektor(listOf(Fotoradar(p(200.0), 50, null)))
        assertNull(d.onFix(p(0.0), 0.0, 2.0))
    }

    @Test
    fun trackerEmitujeFotoradarIMoznaWylaczyc() {
        var wlaczone = true
        val t = Tracker(emptyList(), { 2 }, listOf(Fotoradar(p(1_000.0), 70, 0.0)), { wlaczone })
        val ev = (0..40).flatMap { i -> t.onFix(Fix(i * 1000L, p(i * 25.0).lat, lon0, v90, 0.0)) }
        assertEquals(1, ev.count { it is Zdarzenie.Fotoradar })

        wlaczone = false
        val t2 = Tracker(emptyList(), { 2 }, listOf(Fotoradar(p(1_000.0), 70, 0.0)), { wlaczone })
        val ev2 = (0..40).flatMap { i -> t2.onFix(Fix(i * 1000L, p(i * 25.0).lat, lon0, v90, 0.0)) }
        assertTrue(ev2.none { it is Zdarzenie.Fotoradar })
    }

    @Test
    fun plikDanychJestPoprawny() {
        val f = listOf(File("../data/fotoradary.json"), File("data/fotoradary.json")).first { it.exists() }
        val txt = f.readText()
        val l = FotoradaryParser.parse(txt)
        assertTrue(l.size > 300)
        assertEquals(org.json.JSONObject(txt).getJSONArray("fotoradary").length(), l.size)
        assertTrue(l.all { it.limit == null || it.limit in 20..140 })
    }
}
