package it.payprint.pagamico.client

import it.payprint.pagamico.response.PagAmicoResponse

/**
 * Un singolo messaggio ricevuto dal pagAmico.
 *
 * Il protocollo e' asincrono: ad un comando possono seguire piu' frame
 * (es. IN -> `{"response":"OK"}` -> `{"response":"p"}` ... -> `{"response":"IN"}`).
 */
data class PagAmicoFrame(
    val kind: FrameKind,
    val raw: String,
    val json: PagAmicoResponse? = null,
    val receivedAt: Long = System.currentTimeMillis()
) {
    val isJson: Boolean get() = kind == FrameKind.JSON
    val isText: Boolean get() = kind == FrameKind.TEXT

    /** Valore del campo "response" se il frame e' JSON. */
    val response: String? get() = json?.response

    /** Comando accettato dal pagAmico (`{"response":"OK"}`). */
    val isAck: Boolean get() = response == "OK"

    /** Incasso/ricarica parziale in corso (`{"response":"p"}`). */
    val isPartial: Boolean get() = response == "p"

    /** Errore, sia JSON (`{"response":"ER"}`) sia testuale ("CMD ERROR", "ER BUSY", "BUSY", "POS DISABLED"). */
    val isError: Boolean
        get() = response == "ER" || isBusy || (
            isText && (
                raw.startsWith("ER", ignoreCase = true) ||
                    raw.contains("CMD ERROR", ignoreCase = true) ||
                    raw.startsWith("CMD ", ignoreCase = true) ||
                    raw.contains("POS DISABLED", ignoreCase = true)
                )
            )

    /**
     * Macchina impegnata: il testo "BUSY" (anche "ER BUSY"), oppure un ER con E100 ed errorType 99 (manuale p. 61).
     */
    val isBusy: Boolean
        get() = (isText && raw.contains(PagAmicoTextResponses.BUSY, ignoreCase = true)) || (
            response == "ER" &&
                json?.errorCode.equals(PagAmicoErrorCodes.COMMAND_NOT_EXECUTABLE, ignoreCase = true) &&
                PagAmicoErrorCodes.parseState(json?.errorType) == MachineState.BUSY
            )

    override fun toString(): String = "[$kind] $raw"
}
