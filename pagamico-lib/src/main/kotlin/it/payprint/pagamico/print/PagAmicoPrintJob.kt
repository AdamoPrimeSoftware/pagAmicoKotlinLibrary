package it.payprint.pagamico.print

/** Costruttore fluente di uno scontrino: produce la sequenza PTSTST ... PTSTEN. */
class PagAmicoPrintJob {
    private val commands = mutableListOf<String>()

    fun reset() = add(PagAmicoPrint.reset())
    fun bold(on: Boolean = true) = add(PagAmicoPrint.bold(on))
    fun italic(on: Boolean = true) = add(PagAmicoPrint.italic(on))
    fun doubleSize(on: Boolean = true) = add(PagAmicoPrint.doubleSize(on))
    fun codePage(codePage: Int) = add(PagAmicoPrint.codePage(codePage))
    fun align(alignment: PrintAlignment) = add(PagAmicoPrint.align(alignment))
    fun font(font: PrinterFont, mode: PrinterFontMode) = add(PagAmicoPrint.font(font, mode))
    fun underline(underline: Underline) = add(PagAmicoPrint.underline(underline))
    fun line(text: String) = add(PagAmicoPrint.writeLine(text))
    fun text(text: String) = add(PagAmicoPrint.write(text))
    fun feed(lines: Int = 1) = add(PagAmicoPrint.lineFeed(lines))
    fun separator(c: Char = '-', times: Int = 32) = add(PagAmicoPrint.repeatCharLine(c, times))
    fun qrCode(content: String, moduleSize: Int = 4, errorCorrection: Int = 2) =
        add(PagAmicoPrint.qrCode(content, moduleSize, errorCorrection))

    fun barcode(
        type: BarcodeType,
        value: String,
        heightDots: Int = 100,
        textPosition: BarcodeTextPosition = BarcodeTextPosition.BELOW,
        spacing: Int = 9
    ) = add(PagAmicoPrint.barcode(type, value, heightDots, textPosition, spacing))

    fun rawEscPos(payload: String) = add(PagAmicoPrint.rawEscPos(payload))
    fun cut(full: Boolean = true) = add(PagAmicoPrint.cut(full))

    private fun add(command: String): PagAmicoPrintJob {
        commands += command
        return this
    }

    /** Sequenza completa dei comandi, delimitata da PTSTST / PTSTEN. */
    fun build(): List<String> = buildList {
        add(PagAmicoPrint.beginPrint())
        addAll(commands)
        add(PagAmicoPrint.endPrint())
    }
}