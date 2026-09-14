package it.payprint.pagamico.print

import it.payprint.pagamico.response.PagAmicoResponse

/** Stato stampante restituito da [ PTSTAT]. */
class PrinterStatus private constructor(
    /** false quando errorType = "NO PRINTER". */
    val installed: Boolean,
    val raw: String?,
    /** b7 - stampa avviata. */
    val printing: Boolean,
    /** b2 - carta esaurita. */
    val paperEmpty: Boolean,
    /** b1 - coperchio aperto. */
    val coverOpen: Boolean,
    /** b0 - carta quasi finita. */
    val paperLow: Boolean
) {
    val canPrint: Boolean get() = installed && !paperEmpty && !coverOpen

    override fun toString(): String = if (installed) {
        "stampante ok=$canPrint raw=$raw inStampa=$printing cartaEsaurita=$paperEmpty " +
            "coperchioAperto=$coverOpen cartaQuasiFinita=$paperLow"
    } else {
        "stampante non installata"
    }

    companion object {
        fun parse(response: PagAmicoResponse): PrinterStatus {
            val raw = response.errorType?.trim()

            if (raw.equals("NO PRINTER", ignoreCase = true) || response.response.equals("ER", ignoreCase = true)) {
                return PrinterStatus(false, raw, false, false, false, false)
            }
            if (raw == null || raw.length != 8) {
                return PrinterStatus(true, raw, false, false, false, false)
            }

            // stringa "b7 b6 b5 b4 b3 b2 b1 b0": indice 0 = b7
            fun bit(b: Int) = raw[7 - b] == '1'
            return PrinterStatus(true, raw, bit(7), bit(2), bit(1), bit(0))
        }
    }
}