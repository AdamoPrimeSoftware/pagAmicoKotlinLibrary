package it.payprint.pagamico.exceptions

/** Timeout in attesa della risposta del pagAmico. */
class PagAmicoTimeoutException(message: String) : PagAmicoException(message)