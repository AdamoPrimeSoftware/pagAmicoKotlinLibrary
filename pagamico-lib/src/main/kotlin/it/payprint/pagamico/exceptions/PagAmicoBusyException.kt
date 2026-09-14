package it.payprint.pagamico.exceptions

import it.payprint.pagamico.client.PagAmicoFrame

/**
 * La macchina ha rifiutato l'incasso perche' impegnata (BUSY, oppure ER con E100 / errorType 99):
 * **non ha incassato nulla**.
 */
class PagAmicoBusyException(message: String, frame: PagAmicoFrame? = null) : PagAmicoRejectedException(message, frame)