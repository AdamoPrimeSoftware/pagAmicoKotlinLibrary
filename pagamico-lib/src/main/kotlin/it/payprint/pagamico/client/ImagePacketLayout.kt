package it.payprint.pagamico.client

/** Layout del pacchetto binario per l'invio di immagini (SF / SI). */
enum class ImagePacketLayout {
    /** Formato descritto a testo nel manuale: "SF" + FF FF + png + FE FE + "||". */
    DOCUMENTED,

    /** Formato dell'esempio Python del manuale: "SF" + FF FF + "||" + png + "||" + FE FE. */
    PYTHON_SAMPLE
}