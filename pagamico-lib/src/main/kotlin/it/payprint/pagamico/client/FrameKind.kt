package it.payprint.pagamico.client

/** Tipo di messaggio ricevuto dal pagAmico. */
enum class FrameKind {
    /** Oggetto JSON completo (risposta standard del protocollo). */
    JSON,

    /** Risposta testuale: "CMD ERROR", "OK comando", "ER BUSY", "BT1", "AN", "EX", barcode letto, ... */
    TEXT
}