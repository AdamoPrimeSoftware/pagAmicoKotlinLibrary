package it.payprint.pagamico.response

/**
 * Riga dell'array "bankNotes": un cassetto/riciclatore banconote.
 * Layout (guida pagAmico Dev Kit 1.0, cap. 9.2):
 * `[0]` valore del taglio, `[1]` quantita' in riciclo, `[2]`-`[4]` uso interno,
 * `[5]` soglia minima, `[6]` soglia massima, `[7]`-`[9]` uso interno.
 */
class BanknoteDrawer(val index: Int, val raw: IntArray) {
    private fun at(i: Int) = if (i < raw.size) raw[i] else 0

    /** Taglio in euro contenuto nel cassetto (0 = cassetto non configurato). */
    val value: Int get() = at(0)

    /** Quantita' presente nel riciclatore. */
    val quantity: Int get() = at(1)

    /**
     * Posizione [4]. Negli esempi del manuale contiene il numero progressivo del cassetto, ma la guida
     * Dev Kit la classifica come "uso interno": non farci affidamento, usare [index].
     */
    val raw4: Int get() = at(4)
    val minStock: Int get() = at(5)
    val maxStock: Int get() = at(6)
    val isConfigured: Boolean get() = value > 0

    override fun toString(): String =
        "cassetto#$index taglio=${value}EUR qta=$quantity min=$minStock max=$maxStock"
}