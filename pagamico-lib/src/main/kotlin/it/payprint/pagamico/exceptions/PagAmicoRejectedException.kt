package it.payprint.pagamico.exceptions

import it.payprint.pagamico.client.PagAmicoFrame

/**
 * La macchina ha rifiutato l'incasso prima di accettarlo (testo o ER prima dell'OK):
 * **non ha incassato nulla**.
 */
open class PagAmicoRejectedException(message: String, frame: PagAmicoFrame? = null) : PagAmicoException(message, frame)