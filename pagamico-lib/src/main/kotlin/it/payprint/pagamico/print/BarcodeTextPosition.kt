package it.payprint.pagamico.print

/** Posizione del testo rispetto al barcode. */
enum class BarcodeTextPosition(val code: Int) {
    NONE(0),
    ABOVE(1),
    BELOW(2),
    BOTH(3)
}