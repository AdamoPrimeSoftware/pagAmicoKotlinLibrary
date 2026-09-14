package it.payprint.pagamico.commands

/** Causali dei movimenti per il comando [ MV]. */
object MovementCause {
    const val ALL = "000"
    const val COLLECTION = "001" // Incasso
    const val PAYMENT = "010" // Pagamento
    const val COIN_UNLOAD = "011" // Scarico Monete
    const val COIN_RELOAD = "021" // Ricarica Monete
    const val BANKNOTE_RELOAD = "031" // Ricarica Banconote
    const val BANKNOTE_UNLOAD = "041" // Scarico Banconote
    const val BANKNOTE_UNLOAD_BTA = "051" // Scarico Banconote in BTA
    const val BANKNOTE_UNLOAD_ALT = "061" // Scarico Banconote (2a codifica a manuale)
    const val COIN_UNLOAD_ALT = "071" // Scarico Monete (2a codifica a manuale)
    const val POS_COLLECTION = "090" // Incasso POS
}