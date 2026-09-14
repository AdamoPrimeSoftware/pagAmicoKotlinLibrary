package it.payprint.pagamico.display

/** Modalita' della dialog [ QR]. */
enum class KeyboardMode(val code: Int) {
    /** 0 = mostra la tastiera. */
    SHOW_KEYBOARD(0),

    /** 1 = non mostra la tastiera (lettura da lettore ottico/USB). */
    NO_KEYBOARD(1),

    /** 2 = legge la Tessera Sanitaria dal POS, trasferisce il solo codice fiscale. */
    HEALTH_CARD_FROM_POS(2),

    /** 3 = inserimento tessera. */
    CARD_INSERT(3),

    /** 4 = mostra tastiera numerica. */
    NUMERIC_KEYBOARD(4)
}