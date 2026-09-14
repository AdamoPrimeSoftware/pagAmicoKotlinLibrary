package it.payprint.pagamico.display

/**
 * Layout della tastiera on-screen della finestra di input [ DI]
 * (manuale INTEGRAZIONE 1.2, cap. 3 "Layout Tastiera").
 */
enum class KeyboardLayout(val code: Int) {
    NONE(0),
    STANDARD(1),
    NUMERIC_NO_DECIMALS(2),
    NUMERIC_WITH_DECIMALS(3)
}