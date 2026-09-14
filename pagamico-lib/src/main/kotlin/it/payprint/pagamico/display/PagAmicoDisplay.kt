package it.payprint.pagamico.display

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Comandi di interazione con il display (manuale supplementare INTEGRAZIONE rev. 1.2).
 * Il separatore dei parametri e' la pipe "|".
 */
object PagAmicoDisplay {

    /** Lunghezza massima del JSON dei comandi [ TS] / [ ID]. */
    const val MAX_LIST_JSON_LENGTH = 3999

    /** Un bottone assente si invia come "[]". */
    const val HIDDEN_BUTTON = "[]"

    /** [ DT] / [ DG] finestra di testo. Formato: `DT|{testo}|a|b|c|` */
    fun showText(
        text: String,
        position: DisplayPosition = DisplayPosition.TOP,
        fontSize: Int = 38,
        style: FontStyle = FontStyle.BOLD,
        color: FontColor = FontColor.BLUE
    ): String {
        val prefix = if (position == DisplayPosition.TOP) "DT" else "DG"
        return "$prefix|${sanitize(text)}|$fontSize|${style.code}|${color.code}|"
    }

    /** [ DS] chiude la finestra di testo. */
    fun closeText(): String = "DS"

    /**
     * [ DM] MessageBox: `DM|a|b|c|{testo}|{bottone1}|{bottone2}|{bottone3}`.
     * I bottoni vuoti vengono inviati come "[]". La risposta e' "BT1" | "BT2" | "BT3".
     */
    fun messageBox(
        text: String,
        button1: String,
        button2: String = "",
        button3: String = "",
        fontSize: Int = 29,
        style: FontStyle = FontStyle.NORMAL,
        color: FontColor = FontColor.GREEN
    ): String {
        fun b(value: String) = if (value.isBlank()) HIDDEN_BUTTON else sanitize(value)
        return "DM|$fontSize|${style.code}|${color.code}|${sanitize(text)}|${b(button1)}|${b(button2)}|${b(button3)}"
    }

    /** [ DC] chiude il MessageBox o la finestra di input. */
    fun closeMessageBox(): String = "DC"

    /**
     * [ DI] finestra di input da tastiera on-screen. Risponde con il testo digitato, oppure "AN" se annullato.
     *
     * ATTENZIONE: il comando non e' documentato nei manuali TCP-IP 2.33 / INTEGRAZIONE 1.2; la guida
     * pagAmico Dev Kit riporta `DI|dim|stile|colore|titolo|...|tastiera` lasciando indeterminato il campo
     * intermedio. Qui e' implementato come testo predefinito della casella. Da verificare sul simulatore.
     */
    fun inputBox(
        title: String,
        initialText: String = "",
        keyboard: KeyboardLayout = KeyboardLayout.STANDARD,
        fontSize: Int = 29,
        style: FontStyle = FontStyle.NORMAL,
        color: FontColor = FontColor.BLUE
    ): String = "DI|$fontSize|${style.code}|${color.code}|${sanitize(title)}|${sanitize(initialText)}|${keyboard.code}"

    /** [ DC] chiude la finestra di input (stesso comando del MessageBox). */
    fun closeInputBox(): String = "DC"

    /**
     * [ QR] lettura barcode / QrCode / Tessera Sanitaria / input da tastiera:
     * `QR|{testo}|a|b|c|d` (a=font size, b=stile, c=colore, d=modalita' tastiera).
     * NB: il manuale riporta `QR|{testo}|a|b|c|` ma l'esempio ufficiale ha 4 parametri numerici.
     */
    fun readCode(
        prompt: String,
        mode: KeyboardMode = KeyboardMode.NO_KEYBOARD,
        fontSize: Int = 16,
        style: FontStyle = FontStyle.BOLD,
        color: FontColor = FontColor.GREEN
    ): String = "QR|${sanitize(prompt)}|$fontSize|${style.code}|${color.code}|${mode.code}"

    /** [ QA] chiude la richiesta di lettura codice. */
    fun closeCodeReader(): String = "QA"

    /** [ TS] struttura della lista (JSON compatto). */
    fun listLayout(compactJson: String): String {
        ensureJsonSize(compactJson)
        return "TS$compactJson"
    }

    /** [ ID] dati della lista (JSON compatto). */
    fun listData(compactJson: String): String {
        ensureJsonSize(compactJson)
        return "ID$compactJson"
    }

    /** [ CO] chiude la lista. */
    fun closeList(): String = "CO"

    private fun ensureJsonSize(json: String) {
        require(json.length <= MAX_LIST_JSON_LENGTH) {
            "Il JSON supera i $MAX_LIST_JSON_LENGTH caratteri ammessi dal protocollo"
        }
    }

    /** La pipe e' il separatore del protocollo: va rimossa dai testi utente. */
    private fun sanitize(text: String?): String =
        (text ?: "").replace('|', '/').replace("\r", " ").replace("\n", " ")
}


