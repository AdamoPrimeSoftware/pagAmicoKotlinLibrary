package it.payprint.pagamico.client

/** Codici di errore restituiti nel campo "errorCode" (manuale cap. 4). */
object PagAmicoErrorCodes {
    const val COMMAND_NOT_EXECUTABLE = "E100" // vedi errorType per lo stato macchina
    const val ACCEPTORS_EMPTY = "E200"
    const val BANKNOTE_STACKER_EMPTY = "E201"
    const val COIN_HOPPER_EMPTY = "E202"
    const val AMOUNT_OVER_DISPENSE_LIMIT = "E300"
    const val AMOUNT_OVER_AVAILABILITY = "E301"
    const val NOT_ENOUGH_BANKNOTES = "E302"
    const val NOT_ENOUGH_COINS = "E303"
    const val POS_NOT_RESPONDING = "E500"

    const val DISPENSE_DISABLED = "DISPAG"
    const val WRONG_PASSWORD = "ERRPWDPAG"
    const val WRONG_PASSWORD_ALT = "ERPWDPAG" // variante presente sui comandi P2 / M2
    const val WRONG_LENGTH = "ERRLUNGHEZZA"
    const val BANKNOTE_QTY = "QTABANCONOTE"
    const val BANKNOTE_QTY_DISPENSER_1 = "QTABANCONOTE1"
    const val BANKNOTE_QTY_DISPENSER_2 = "QTABANCONOTE2"
    const val COIN_QTY = "QTAMONETE"
    const val MAX_DISPENSABLE = "MASSIMOEROGABILE"
    const val NOT_AVAILABLE = "ERRNONDISP"
    const val MAX_STOCK_ERROR = "ERRSCOMAX"
    const val MIN_OVER_MAX_STOCK = "ERRSCOMIN>SCOMAX"

    /** FW 8.71+: modalita' EMERGENZA, solo POS operativo. */
    const val ACCEPTORS_NOT_STARTED = "NOT STARTED"
    const val POS_ONLY = "SOLO POS"

    /** Esito del comando IM (incasso automatico contanti/POS). */
    const val PAID_WITH_CASH = "CONT"
    const val PAID_WITH_POS = "POS"
    const val POS_TRANSACTION_REFUSED = "POS ERROR"

    private val descriptions = mapOf(
        COMMAND_NOT_EXECUTABLE to "Comando non eseguibile (vedi errorType per lo stato macchina)",
        ACCEPTORS_EMPTY to "Accettatori vuoti",
        BANKNOTE_STACKER_EMPTY to "Stacker banconote vuoto",
        COIN_HOPPER_EMPTY to "Hopper monete vuoto",
        AMOUNT_OVER_DISPENSE_LIMIT to "Importo superiore al limite erogabile",
        AMOUNT_OVER_AVAILABILITY to "Importo superiore alla disponibilita'",
        NOT_ENOUGH_BANKNOTES to "Banconote insufficienti per effettuare il pagamento",
        NOT_ENOUGH_COINS to "Monete insufficienti per effettuare il pagamento",
        POS_NOT_RESPONDING to "POS non risponde",
        DISPENSE_DISABLED to "Erogazione disabilitata nel setup",
        WRONG_PASSWORD to "Password di erogazione errata",
        WRONG_PASSWORD_ALT to "Password di erogazione errata",
        WRONG_LENGTH to "Formato/lunghezza del comando errata",
        BANKNOTE_QTY to "Quantita' banconote errata o superiore alla disponibilita'",
        BANKNOTE_QTY_DISPENSER_1 to "Banconote insufficienti nel primo erogatore",
        BANKNOTE_QTY_DISPENSER_2 to "Banconote insufficienti nel secondo erogatore",
        COIN_QTY to "Quantita' monete errata",
        MAX_DISPENSABLE to "Importo superiore al massimo erogabile",
        NOT_AVAILABLE to "pagAmico non disponibile",
        MAX_STOCK_ERROR to "Scorta massima errata",
        MIN_OVER_MAX_STOCK to "Scorta minima superiore alla scorta massima",
        ACCEPTORS_NOT_STARTED to "Accettatori non operativi: macchina in modalita' EMERGENZA (solo POS)",
        POS_ONLY to "Macchina operativa solo con POS"
    )

    fun describe(code: String?): String {
        if (code.isNullOrBlank()) return ""
        descriptions[code]?.let { return it }
        descriptions.entries.firstOrNull { it.key.equals(code, ignoreCase = true) }?.let { return it.value }
        if (code.startsWith("QTAMONETE", ignoreCase = true)) {
            return "Quantita' monete insufficiente per il taglio ${code.substring("QTAMONETE".length)}"
        }
        return code
    }

    /** Interpreta errorType quando errorCode = "E100". */
    fun parseState(errorType: String?): MachineState =
        MachineState.entries.firstOrNull { it.code == errorType?.trim()?.toIntOrNull() } ?: MachineState.UNKNOWN
}