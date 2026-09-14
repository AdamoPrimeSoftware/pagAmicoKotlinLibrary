package it.payprint.pagamico.client

/**
 * Risposte che il pagAmico invia in chiaro, non incapsulate in un JSON
 * (guida pagAmico Dev Kit 1.0, cap. 10.4 e manuale protocollo di stampa).
 */
object PagAmicoTextResponses {
    const val COMMAND_ERROR = "CMD ERROR"
    const val MV_DATE_INVALID = "CMD ERROR, DATE INVALID"
    const val MV_CODE_OPERATION_INVALID = "CMD ERROR, CODE OPERATION INVALID"
    const val MV_LENGTH_ERROR = "CMD LENGHT ERROR"
    const val MI_FORMAT_ERROR = "CMD ERROR, FORMAT ERROR"
    const val POS_DISABLED = "POS DISABLED"
    const val PRINTER_BUSY = "ER BUSY"
    const val PRINTER_NOTHING_TO_CANCEL = "ER NO-PRINT"
    const val PRINTER_COMMAND_ERROR = "ER CMD-ERROR"

    /** Macchina impegnata: risposta a un comando che non puo' eseguire (per esempio durante un incasso). */
    const val BUSY = "BUSY"

    /** Risposta del display al MessageBox [ DM]: bottone premuto. */
    const val BUTTON_PREFIX = "BT"

    /** Risposta di annullo di [ DM], [ DI] e [ QR]. */
    const val CANCELLED = "AN"

    /** Uscita dalla lista [ ID]. */
    const val LIST_EXIT = "EX"

    private val descriptions = mapOf(
        COMMAND_ERROR to "Comando inesistente o formato non valido",
        MV_DATE_INVALID to "Date non valide nel comando MV",
        MV_CODE_OPERATION_INVALID to "Causale non valida nel comando MV",
        MV_LENGTH_ERROR to "Lunghezza del comando MV non corretta",
        MI_FORMAT_ERROR to "Formato dell'Id non valido nel comando MI",
        POS_DISABLED to "POS disabilitato nel setup del pagAmico",
        PRINTER_BUSY to "Stampa gia' in corso: riprovare",
        PRINTER_NOTHING_TO_CANCEL to "Ricevuto PTSTAN ma nessuna stampa era in corso",
        PRINTER_COMMAND_ERROR to "Comando di stampa non riconosciuto",
        BUSY to "Macchina impegnata: comando non eseguito"
    )

    fun describe(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val t = raw.trim()
        descriptions[t]?.let { return it }
        return descriptions.entries.firstOrNull { t.startsWith(it.key, ignoreCase = true) }?.value ?: t
    }
}