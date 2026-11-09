package pl.matka.opp

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.matka.opp.engine.Calc
import pl.matka.opp.engine.Kolor

class CalcTest {
    @Test
    fun naStarcieDozwolonaToLimitMinusZapas() {
        val v = Calc.dozwolonaKmh(10_000.0, 100, 2, 0.0, 10_000.0)
        assertEquals(98.0, v, 0.01)
    }

    @Test
    fun wolniejszaJazdaDajeZapasPowyzejLimitu() {
        // 10 km, limit 100 (bez zapasu): t_min = 360 s. Połowa w 200 s (90 km/h) -> zostaje 160 s na 5 km.
        val v = Calc.dozwolonaKmh(10_000.0, 100, 0, 200.0, 5_000.0)
        assertEquals(112.5, v, 0.01)
    }

    @Test
    fun szybszaJazdaObnizaDozwolona() {
        // Połowa w 150 s (120 km/h) -> 5 km w 210 s = 85.7 km/h
        val v = Calc.dozwolonaKmh(10_000.0, 100, 0, 150.0, 5_000.0)
        assertEquals(85.71, v, 0.01)
    }

    @Test
    fun poMinimalnymCzasieDowolnaPredkosc() {
        // 9,9 km w 361 s = 98,7 km/h – średnia już na pewno poniżej limitu, niezależnie od reszty.
        assertEquals(Calc.MAX_KMH.toDouble(), Calc.dozwolonaKmh(10_000.0, 100, 0, 361.0, 100.0), 0.0)
    }

    @Test
    fun zapasWiekszyOdLimituNieDzieliPrzezZero() {
        val v = Calc.dozwolonaKmh(1_000.0, 5, 20, 0.0, 1_000.0)
        assertEquals(5.0, v, 0.01)
    }

    @Test
    fun kolory() {
        assertEquals(Kolor.ZIELONY, Calc.kolor(80.0, 88.0, 90, false))
        assertEquals(Kolor.ZOLTY, Calc.kolor(91.0, 88.0, 90, false))
        assertEquals(Kolor.CZERWONY, Calc.kolor(94.0, 88.0, 90, false))
        assertEquals(Kolor.NIEBIESKI, Calc.kolor(80.0, 95.0, 90, false))
        assertEquals(Kolor.ZOLTY, Calc.kolor(97.0, 95.0, 90, false))
        assertEquals(Kolor.SZARY, Calc.kolor(80.0, 88.0, 90, true))
        assertEquals(Kolor.CZERWONY, Calc.kolor(80.0, null, 90, false))
    }
}
