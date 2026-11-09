package pl.matka.opp.engine

enum class Kolor { ZIELONY, ZOLTY, CZERWONY, NIEBIESKI, SZARY }

object Calc {
    /** Maksymalna wyświetlana wartość – powyżej tego liczba i tak nic nie mówi. */
    const val MAX_KMH = 199

    /**
     * Maksymalna prędkość (km/h) na resztę odcinka, żeby średnia nie przekroczyła (limit − zapas).
     * Gdy minął już minimalny czas przejazdu, średnia na pewno zmieści się w limicie – zwraca [MAX_KMH].
     */
    fun dozwolonaKmh(
        dlugoscM: Double,
        limitKmh: Int,
        zapasKmh: Int,
        uplynaloS: Double,
        pozostaloM: Double,
    ): Double {
        if (pozostaloM <= 0.0) return MAX_KMH.toDouble()
        val vEff = (limitKmh - zapasKmh).coerceAtLeast(5) / 3.6
        val tMin = dlugoscM / vEff
        val tLeft = tMin - uplynaloS
        if (tLeft <= 0.0) return MAX_KMH.toDouble()
        return (pozostaloM / tLeft * 3.6).coerceAtMost(MAX_KMH.toDouble())
    }

    /** Kolor wg relacji aktualnej prędkości do wyliczonej dozwolonej. */
    fun kolor(aktualnaKmh: Double?, dozwolonaKmh: Double?, limitKmh: Int, szacowane: Boolean): Kolor {
        if (szacowane) return Kolor.SZARY
        if (dozwolonaKmh == null) return Kolor.CZERWONY
        if (aktualnaKmh != null) {
            val diff = aktualnaKmh - dozwolonaKmh
            if (diff > 5.0) return Kolor.CZERWONY
            if (diff >= 1.0) return Kolor.ZOLTY
        }
        return if (dozwolonaKmh > limitKmh) Kolor.NIEBIESKI else Kolor.ZIELONY
    }
}
