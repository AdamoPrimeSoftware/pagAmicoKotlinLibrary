package it.payprint.pagamico.print

/**
 * Comandi del protocollo di stampa pagAmico (manuale "Protocollo di Stampa" rev. 2.00).
 * Ogni comando inizia con l'header "PT" seguito da comando e parametri, senza spazi.
 */
object PagAmicoPrint {

    fun beginPrint(): String = "PTSTST"
    fun endPrint(): String = "PTSTEN"
    fun cancelPrint(): String = "PTSTAN"
    fun reset(): String = "PTRSET"

    fun bold(on: Boolean): String = if (on) "PTBDON" else "PTBDOF"
    fun italic(on: Boolean): String = if (on) "PTITON" else "PTITOF"
    fun doubleSize(on: Boolean): String = if (on) "PTDBON" else "PTDBOF"

    fun codePage(codePage: Int): String {
        require(codePage in 0..99) { "Code page a 2 cifre (00..99)" }
        return "PTCP" + "%02d".format(codePage)
    }

    fun cut(full: Boolean): String = if (full) "PTCUTL" else "PTCUPT"

    fun align(alignment: PrintAlignment): String = when (alignment) {
        PrintAlignment.CENTER -> "PTJTCE"
        PrintAlignment.RIGHT -> "PTJTRI"
        PrintAlignment.LEFT -> "PTJTLE"
    }

    fun font(font: PrinterFont, mode: PrinterFontMode): String {
        val f = if (font == PrinterFont.A) "PTFA" else "PTFB"
        val m = when (mode) {
            PrinterFontMode.BOLD -> "BD"
            PrinterFontMode.DOUBLE_HEIGHT -> "H2"
            PrinterFontMode.DOUBLE_WIDTH -> "W2"
            PrinterFontMode.DOUBLE_HEIGHT_WIDTH -> "HW"
            PrinterFontMode.NORMAL -> "NO"
        }
        return f + m
    }

    fun underline(underline: Underline): String = "PTUN" + "%02d".format(underline.code)

    /** [ PTLFNRxx] avanzamento di xx righe. */
    fun lineFeed(lines: Int): String {
        require(lines in 0..99) { "0..99 righe" }
        return "PTLFNR" + "%02d".format(lines)
    }

    /** [ PTPRWL] stampa testo seguito da CR LF. */
    fun writeLine(text: String): String = "PTPRWL$text"

    /** [ PTPRWR] stampa testo senza avanzamento riga. */
    fun write(text: String): String = "PTPRWR$text"

    /** [ PTPRRExx] stampa un carattere ripetuto xx volte. */
    fun repeatChar(c: Char, times: Int): String = "PTPRRE" + times(times) + c

    /** [ PTPRRLxx] stampa un carattere ripetuto xx volte seguito da CR LF. */
    fun repeatCharLine(c: Char, times: Int): String = "PTPRRL" + times(times) + c

    private fun times(times: Int): String {
        require(times in 0..99) { "0..99 ripetizioni" }
        return "%02d".format(times)
    }

    /**
     * [PTPRDT_ASCII:] invio diretto di sequenze ESC/POS.
     * NB: il manuale usa in punti diversi "PTPRDT_ASCII:", "PTSTDT_ASCII:" e "PTSTAT_ASCII:": da confermare con PayPrint.
     */
    fun rawEscPos(asciiPayload: String): String = "PTPRDT_ASCII:$asciiPayload"

    /**
     * [PTQRQRsl ...] stampa un QR code.
     * s = dimensione modulo in dot (1 cifra), l = livello di correzione errore (1 cifra).
     */
    fun qrCode(content: String, moduleSize: Int = 4, errorCorrection: Int = 2): String {
        require(moduleSize in 1..9) { "moduleSize 1..9" }
        require(errorCorrection in 0..9) { "errorCorrection 0..9" }
        return "PTQRQR$moduleSize$errorCorrection$content"
    }

    /**
     * [PTBCBCthhhps ...] stampa un barcode.
     * t = tipo, hhh = altezza in dot, p = posizione testo, s = spaziatura (1 cifra).
     */
    fun barcode(
        type: BarcodeType,
        value: String,
        heightDots: Int = 100,
        textPosition: BarcodeTextPosition = BarcodeTextPosition.BELOW,
        spacing: Int = 9
    ): String {
        require(heightDots in 0..999) { "altezza 0..999 dot" }
        require(spacing in 0..9) { "spaziatura 0..9" }
        return "PTBCBC${type.code}${"%03d".format(heightDots)}${textPosition.code}$spacing$value"
    }

    /** [ PTSTAT] lettura stato stampante. */
    fun status(): String = "PTSTAT"
}

