package it.payprint.pagamico.exceptions

/**
 * Comando non inviato perche' c'e' un incasso aperto: durante [IN] la macchina accetta solo [AN] e [CM]
 * (risposta PayPrint dell'11/09/2026). Nulla e' stato trasmesso.
 */
class PagAmicoCollectionOpenException(val command: String) :
    PagAmicoException("Incasso aperto: '$command' non inviato, durante l'incasso la macchina accetta solo AN e CM")