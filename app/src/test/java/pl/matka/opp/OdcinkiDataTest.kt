package pl.matka.opp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.matka.opp.engine.OdcinkiParser
import java.io.File

class OdcinkiDataTest {
    @Test
    fun plikDanychJestPoprawny() {
        val f = listOf(File("../data/odcinki.json"), File("data/odcinki.json")).first { it.exists() }
        val txt = f.readText()
        val d = OdcinkiParser.parse(txt)
        assertTrue(d.odcinki.size > 100)
        // parser nie może po cichu odrzucać wpisów z naszego pliku
        assertEquals(org.json.JSONObject(txt).getJSONArray("odcinki").length(), d.odcinki.size)
        for (o in d.odcinki) {
            assertTrue(o.id, o.limit in 20..140)
            assertTrue(o.id, o.dlugoscM >= OdcinkiParser.MIN_DLUGOSC_M)
            // długość trasy zgodna z oficjalną z dokładnością do 10%
            assertTrue(o.id, kotlin.math.abs(o.trasa.length - o.dlugoscM) / o.dlugoscM < 0.1)
        }
    }

    @Test
    fun parserOdrzucaZdegenerowanaTrase() {
        val json = """{"wersja":1,"aktualizacja":"x","odcinki":[
            {"id":"a","limit":90,"trasa":[[52.0,19.0],[52.0,19.0]]},
            {"id":"b","limit":90,"dlugosc_m":5000,"trasa":[[52.0,19.0],[52.0452,19.0]]}]}"""
        val d = OdcinkiParser.parse(json)
        assertEquals(listOf("b"), d.odcinki.map { it.id })
    }
}
