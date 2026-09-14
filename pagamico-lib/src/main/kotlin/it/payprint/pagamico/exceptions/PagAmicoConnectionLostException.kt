package it.payprint.pagamico.exceptions

/**
 * Connessione caduta mentre si attendeva una risposta. Se [mayBeCollecting] e' vero l'incasso era gia'
 * stato accettato: **la macchina potrebbe stare ancora incassando**.
 */
class PagAmicoConnectionLostException(message: String, val mayBeCollecting: Boolean) : PagAmicoException(message)