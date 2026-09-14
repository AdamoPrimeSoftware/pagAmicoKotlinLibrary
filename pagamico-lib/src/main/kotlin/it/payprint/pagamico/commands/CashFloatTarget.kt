package it.payprint.pagamico.commands

/** Fondo cassa da aggiornare con il comando [ AF]. */
enum class CashFloatTarget(val code: Int) {
    COINS(1),
    BANKNOTES(2),
    BOTH(9)
}