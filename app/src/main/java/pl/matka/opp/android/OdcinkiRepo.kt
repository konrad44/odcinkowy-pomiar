package pl.matka.opp.android

import android.content.Context
import android.util.Log
import pl.matka.opp.engine.OdcinkiDane
import pl.matka.opp.engine.OdcinkiParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Odcinki: kopia wbudowana (assets) + lokalny plik pobierany raz w tygodniu z repo.
 */
class OdcinkiRepo(private val ctx: Context) {
    companion object {
        const val URL_DANYCH = "https://raw.githubusercontent.com/konrad44/odcinkowy-pomiar/master/data/odcinki.json"
        const val CO_ILE_MS = 7L * 24 * 3600 * 1000
        private const val TAG = "OdcinkiRepo"
    }

    private val prefs = Prefs(ctx)
    private val plik get() = File(ctx.filesDir, "odcinki.json")

    private fun tekstWbudowany() = ctx.assets.open("odcinki.json").bufferedReader().use { it.readText() }

    fun wczytaj(): OdcinkiDane {
        val wbudowane = OdcinkiParser.parse(tekstWbudowany())
        val f = plik
        if (f.exists()) {
            runCatching { OdcinkiParser.parse(f.readText()) }
                .onSuccess { lok ->
                    // Bierzemy nowszy z dwóch (np. po aktualizacji aplikacji wbudowany może być świeższy).
                    if (lok.odcinki.isNotEmpty() && lok.aktualizacja >= wbudowane.aktualizacja) return lok
                }
                .onFailure { Log.w(TAG, "Uszkodzony plik lokalny", it) }
        }
        return wbudowane
    }

    fun czyPora(): Boolean = System.currentTimeMillis() - prefs.ostatnieSprawdzenie >= CO_ILE_MS

    /**
     * Pobiera plik z repo. Wołać poza wątkiem głównym.
     * @return nowe dane, jeśli się zmieniły; null gdy bez zmian lub błąd.
     */
    @Synchronized
    fun aktualizuj(): Result<OdcinkiDane?> = runCatching {
        val c = URL(URL_DANYCH).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "odcinkowy-pomiar")
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            val txt = c.inputStream.bufferedReader().use { it.readText() }
            val nowe = OdcinkiParser.parse(txt)
            val wbudowaneTxt = tekstWbudowany()
            val wbudowane = OdcinkiParser.parse(wbudowaneTxt)
            sprawdz(nowe, wbudowane)
            val lokalnyTxt = plik.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
            val wynik = when {
                // Ten sam plik co obecny – porównujemy całą treść, nie tylko datę i liczbę odcinków.
                txt == lokalnyTxt || (lokalnyTxt == null && txt == wbudowaneTxt) -> null
                // Starszy niż wbudowany w APK (np. nieodświeżony cache serwera) – nie cofamy danych.
                nowe.aktualizacja < wbudowane.aktualizacja -> null
                else -> {
                    val tmp = File(ctx.filesDir, "odcinki.json.tmp")
                    tmp.writeText(txt)
                    if (!tmp.renameTo(plik)) error("Nie udało się zapisać pliku")
                    nowe
                }
            }
            prefs.ostatnieSprawdzenie = System.currentTimeMillis()
            wynik
        } finally {
            c.disconnect()
        }
    }

    /** Odrzuca pobrany plik, który wygląda na zepsuty – zanim nadpisze dobrą kopię. */
    private fun sprawdz(nowe: OdcinkiDane, wbudowane: OdcinkiDane) {
        if (nowe.wersja > 1) error("Nieobsługiwana wersja danych ${nowe.wersja} – zaktualizuj aplikację")
        if (nowe.odcinki.size < wbudowane.odcinki.size / 2) error("Podejrzanie mało odcinków: ${nowe.odcinki.size}")
        val pozaPolska = nowe.odcinki.count { o ->
            o.trasa.points.any { it.lat !in 48.9..55.0 || it.lon !in 14.0..24.2 }
        }
        if (pozaPolska > 0) error("$pozaPolska odcinków poza Polską – zły format danych")
    }
}
