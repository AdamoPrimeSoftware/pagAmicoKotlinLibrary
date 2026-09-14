package it.payprint.pagamico.commands

/** Abilitazione di un taglio in incasso ed erogazione ([ EM] / [ EB]). */
class DenominationToggle(
    /** Prima cifra: il taglio e' accettato in incasso. */
    val acceptOnCollect: Boolean,
    /** Seconda cifra: il taglio e' erogabile come resto. */
    val dispenseAsChange: Boolean
) {
    override fun toString(): String =
        (if (acceptOnCollect) "1" else "0") + (if (dispenseAsChange) "1" else "0")

    companion object {
        val ALL = DenominationToggle(acceptOnCollect = true, dispenseAsChange = true)
        val NONE = DenominationToggle(acceptOnCollect = false, dispenseAsChange = false)
    }
}