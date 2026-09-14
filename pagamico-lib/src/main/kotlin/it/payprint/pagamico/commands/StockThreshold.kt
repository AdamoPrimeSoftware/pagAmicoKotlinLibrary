package it.payprint.pagamico.commands

/** Soglia di scorta minima/massima per un taglio ([ SM] / [ SB]). */
class StockThreshold(val min: Int, val max: Int) {
    init {
        require(min in 0..999) { "Scorta minima fuori range 0..999" }
        require(max in 0..999) { "Scorta massima fuori range 0..999" }
        require(min <= max) { "La scorta minima non puo' superare la massima (ERRSCOMIN>SCOMAX)" }
    }

    override fun toString(): String = "%03d%03d".format(min, max)
}