package it.payprint.pagamico.test

import it.payprint.pagamico.client.MachineState
import it.payprint.pagamico.client.PagAmicoErrorCodes
import it.payprint.pagamico.client.PagAmicoFrameParser
import it.payprint.pagamico.commands.CashFloatTarget
import it.payprint.pagamico.commands.DenominationToggle
import it.payprint.pagamico.commands.PagAmicoCommands
import it.payprint.pagamico.commands.StockThreshold
import it.payprint.pagamico.display.DisplayPosition
import it.payprint.pagamico.display.FontColor
import it.payprint.pagamico.display.FontStyle
import it.payprint.pagamico.display.KeyboardMode
import it.payprint.pagamico.display.ListCell
import it.payprint.pagamico.display.ListData
import it.payprint.pagamico.display.ListFooter
import it.payprint.pagamico.display.ListLayout
import it.payprint.pagamico.display.ListRow
import it.payprint.pagamico.display.ListTextProperties
import it.payprint.pagamico.display.PagAmicoDisplay
import it.payprint.pagamico.display.TextAlignment
import it.payprint.pagamico.print.BarcodeTextPosition
import it.payprint.pagamico.print.BarcodeType
import it.payprint.pagamico.print.PagAmicoPrint
import it.payprint.pagamico.print.PagAmicoPrintJob
import it.payprint.pagamico.print.PrintAlignment
import it.payprint.pagamico.print.PrinterFont
import it.payprint.pagamico.print.PrinterFontMode
import it.payprint.pagamico.print.PrinterStatus
import it.payprint.pagamico.print.Underline
import it.payprint.pagamico.response.PagAmicoResponse
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.system.exitProcess

/**
 * Test di autoverifica senza dipendenze di test: confronta i comandi generati con gli esempi
 * letterali dei manuali, verifica il framer sulle risposte documentate e, con un finto pagAmico
 * su 127.0.0.1, le sequenze di incasso (SequenceTest.kt).
 *
 * Esecuzione: `gradle run` (mainClass = it.payprint.pagamico.SelfTestKt)
 */
private var passed = 0
private var failed = 0

fun main() {
    commandTests()
    parserTests()
    responseTests()
    printerTests()
    displayTests()
    sequenceTests()
    loggerAndErrorTests()

    println()
    println("$passed test superati, $failed falliti")
    exitProcess(if (failed == 0) 0 else 1)
}

// ---------------------------------------------------------------- comandi

private fun commandTests() {
    section("Comandi")

    eq("IN001050", PagAmicoCommands.collect(BigDecimal("10.50")), "IN incasso 10,50 EUR")
    eq("IN000150", PagAmicoCommands.collect(BigDecimal("1.50")), "IN incasso 1,50 EUR")
    eq("PO001050", PagAmicoCommands.collectPos(BigDecimal("10.50")), "PO incasso POS 10,50 EUR")
    eq("IM001050", PagAmicoCommands.collectAuto(BigDecimal("10.50")), "IM incasso automatico 10,50 EUR")
    eq("I2020000100", PagAmicoCommands.collectWithTimeout(20, BigDecimal("1.00")), "I2 incasso 1 EUR timeout 20s")
    eq("PA0000001050", PagAmicoCommands.dispense(BigDecimal("10.50")), "PA erogazione 10,50 EUR")
    eq("PA0000001050mypassword", PagAmicoCommands.dispense(BigDecimal("10.50"), "mypassword"), "PA con password")
    eq("PM001001000000000000", PagAmicoCommands.dispenseCoins(1, 1, 0, 0, 0, 0), "PM 1x0,05 + 1x0,10")
    eq("P2001000000000000000myPassword", PagAmicoCommands.dispenseBanknotes(1, 0, 0, 0, 0, 0, "myPassword"), "P2 1 banconota da 5")
    eq("M2001000000000000000myPassword", PagAmicoCommands.moveBanknotesToBta(1, 0, 0, 0, 0, 0, "myPassword"), "M2 1 banconota in BTA")
    eq("MF001000000000000000myPassword", PagAmicoCommands.moveCoinsToCashbox(1, 0, 0, 0, 0, 0, "myPassword"), "MF 1 moneta da 0,05")
    eq("AF1myPassword", PagAmicoCommands.updateCashFloat(CashFloatTarget.COINS, "myPassword"), "AF fondo cassa monete")
    eq("AF9", PagAmicoCommands.updateCashFloat(CashFloatTarget.BOTH), "AF fondo cassa monete + banconote")
    eq("AZ2myPassword", PagAmicoCommands.resetBanknotes("myPassword"), "AZ azzera banconote")
    eq("BTmyPassword", PagAmicoCommands.resetBta("myPassword"), "BT azzera BTA")

    val stock = List(6) { StockThreshold(5, 100) }
    eq("SM005100005100005100005100005100005100", PagAmicoCommands.setCoinStock(stock), "SM scorte monete 5/100")
    eq("SB005100005100005100005100005100005100", PagAmicoCommands.setBanknoteStock(stock), "SB scorte banconote 5/100")

    val toggles = listOf(
        DenominationToggle(false, true), DenominationToggle(false, true),
        DenominationToggle(false, true), DenominationToggle(false, true),
        DenominationToggle(true, true), DenominationToggle(true, true)
    )
    eq("EM010101011111", PagAmicoCommands.enableCoins(toggles), "EM abilitazione tagli monete")
    eq("EB010101011111", PagAmicoCommands.enableBanknotes(toggles), "EB abilitazione tagli banconote")

    eq(
        "MV2025/01/01 10:002025/04/22 18:59000",
        PagAmicoCommands.movements(LocalDateTime.of(2025, 1, 1, 10, 0), LocalDateTime.of(2025, 4, 22, 18, 59)),
        "MV elenco movimenti"
    )
    eq("MI000007222", PagAmicoCommands.movementById(7222), "MI movimento per Id")
    eq("PLT", PagAmicoCommands.posLastTransaction(true), "PL ultima transazione con ristampa")
    eq("PLF", PagAmicoCommands.posLastTransaction(false), "PL ultima transazione senza ristampa")
    eq("RS", PagAmicoCommands.reloadMixed(true), "RS ricarica mista con fondo cassa")
    eq("VC", PagAmicoCommands.reloadMixed(false, sendPartials = true), "VC ricarica mista con parziali")

    throws("IN oltre 6 cifre deve fallire") { PagAmicoCommands.collect(BigDecimal("10000")) }
    throws("password oltre 19 caratteri deve fallire") { PagAmicoCommands.dispense(BigDecimal.ONE, "x".repeat(20)) }
    throws("scorta minima > massima deve fallire") { StockThreshold(50, 10) }
}

// ---------------------------------------------------------------- framer

private fun parserTests() {
    section("Framer")

    var p = PagAmicoFrameParser()
    p.append("{\"response\":\"I")
    check(p.readFrame(false) == null, "JSON incompleto non deve produrre frame")
    p.append("N\",\"collectedAmount\":1.5}")
    check(p.readFrame(false)?.response == "IN", "JSON riassemblato su due segmenti")

    p = PagAmicoFrameParser()
    p.append("{\"response\":\"OK\"}{\"response\":\"p\"}")
    check(p.readFrame(false)?.response == "OK", "primo JSON di un segmento doppio")
    check(p.readFrame(false)?.response == "p", "secondo JSON di un segmento doppio")

    p = PagAmicoFrameParser()
    p.append("{\"root\":[{\"dataora\":\"2025-07-01 11:30:15\",\"codice_operazione\":\"090\",\"importo_pos\":101.55}]}|\\")
    val mv = p.readFrame(false)
    check(mv?.json?.movements?.size == 1, "risposta MV con terminatore |\\")
    check(p.bufferedLength == 0, "il terminatore |\\ viene consumato")
    check(mv!!.json!!.movements[0].posAmount.compareTo(BigDecimal("101.55")) == 0, "importo_pos del movimento")

    p = PagAmicoFrameParser()
    p.append("{\"response\":\"PO\",\"posFinancialTransactionEndResponseMessage\":\"\\u0002{ciao} \\\"x\\\" }\\u0003\"}")
    check(p.readFrame(false)?.response == "PO", "graffe dentro stringa JSON")

    p = PagAmicoFrameParser(textIdleMs = 1)
    p.append("CMD ERROR")
    check(p.readFrame(false) == null, "testo senza silenzio resta in buffer")
    Thread.sleep(10)
    val txt = p.readFrame(true)
    check(txt != null && txt.isText && txt.raw == "CMD ERROR" && txt.isError, "testo chiuso dal silenzio")

    p = PagAmicoFrameParser()
    p.append("BT3{\"response\":\"OK\"}")
    check(p.readFrame(false)?.raw == "BT3", "testo chiuso dalla graffa successiva")
    check(p.readFrame(false)?.response == "OK", "JSON dopo il testo")
}

// ---------------------------------------------------------------- risposta

private fun responseTests() {
    section("Risposta JSON")

    val json = """
        {"response":"PO","amountRequested":0.1,"collectedAmount":0.1,"errorList":"E0090",
        "coins":[0,0,0,41,57,71,29,47,26,0],"coinsLimits":[0,0,0,10,10,10,10,10,10,0],
        "bankNotes":[[5,2,0,0,1,5,30,0,0,0],[10,0,0,0,2,1,30,0,0,0]],
        "bankNotes_BTA":[0,0,15,6,0,0,0,0,0,0,0],"bankNotesInStock":[0,2,0,1,0,0,0],
        "coinsInStock":[0,0,0,82,117,142,58,96,57,0],"firmwareVers":8.7,"sN":"0000",
        "typePagAmico":"4B","committedAmout":0,"AmountBanknotesInBTA":0,"Id":7365,
        "PosTot_1":12.5,"PosTot_2":0}
    """.trimIndent().replace("\n", "")

    val r = PagAmicoResponse.tryParse(json)
    check(r != null, "parsing della risposta di esempio del manuale")
    eq("PO", r!!.response, "campo response")
    eq("0000", r.serialNumber, "sN mappato su serialNumber")
    check(r.committedAmount?.signum() == 0, "committedAmout (refuso del firmware) mappato")
    check(r.id == 7365L, "Id del movimento")
    check(r.posTot1?.compareTo(BigDecimal("12.5")) == 0, "PosTot_1")
    check(r.firmwareVersion == 8.7, "firmwareVers")

    check(r.coinsByDenomination[5] == 41 && r.coinsByDenomination[200] == 26, "monete per taglio")
    check(r.coinsInStockByDenomination[10] == 117, "fondo cassa monete per taglio")
    check(r.bankNotesInStockByDenomination[5] == 2 && r.bankNotesInStockByDenomination[20] == 1, "fondo cassa banconote per taglio")
    check(r.bankNotesBtaByDenomination[5] == 15 && r.bankNotesBtaByDenomination[10] == 6, "banconote in BTA per taglio")
    check(r.drawers.size == 2 && r.drawers[0].value == 5 && r.drawers[0].quantity == 2, "cassetti banconote")

    val status = r.status
    check(status.banknotesEmpty, "errorList E0090: taglio banconote esaurito")
    check(!status.coinsBelowMinimum, "errorList E0090: monete ok")
    check(status.warnings().size == 1, "una sola anomalia segnalata")

    val legacy = PagAmicoResponse.tryParse(
        "{\"response\":\"IN\",\"serialNumber\":\"33004724002\",\"committedAmount\":3.5,\" AmountResettedBanknotesInBTA\":7}"
    )!!
    eq("33004724002", legacy.serialNumber, "serialNumber (FW < 8.71) mappato")
    check(legacy.committedAmount?.compareTo(BigDecimal("3.5")) == 0, "committedAmount corretto")
    check(legacy.amountResettedBanknotesInBta?.compareTo(BigDecimal("7")) == 0, "chiave con spazio iniziale gestita")

    check(PagAmicoResponse.tryParse("CMD ERROR") == null, "testo non JSON restituisce null")
    eq("Monete insufficienti per effettuare il pagamento", PagAmicoErrorCodes.describe("E303"), "descrizione E303")
    check(PagAmicoErrorCodes.parseState("99") == MachineState.BUSY, "errorType 99 = occupato")
}

// ---------------------------------------------------------------- stampa

private fun printerTests() {
    section("Stampa")

    eq("PTSTST", PagAmicoPrint.beginPrint(), "inizio stampa")
    eq("PTSTEN", PagAmicoPrint.endPrint(), "fine stampa")
    eq("PTBDON", PagAmicoPrint.bold(true), "grassetto on")
    eq("PTJTCE", PagAmicoPrint.align(PrintAlignment.CENTER), "allineamento centro")
    eq("PTFAHW", PagAmicoPrint.font(PrinterFont.A, PrinterFontMode.DOUBLE_HEIGHT_WIDTH), "font A doppia altezza/larghezza")
    eq("PTLFNR03", PagAmicoPrint.lineFeed(3), "avanzamento 3 righe")
    eq("PTUN01", PagAmicoPrint.underline(Underline.SINGLE), "sottolineato 1")
    eq("PTPRWLTotale 10,50", PagAmicoPrint.writeLine("Totale 10,50"), "stampa riga")
    eq("PTPRRL32-", PagAmicoPrint.repeatCharLine('-', 32), "riga di separazione")
    eq("PTQRQR42www.pagamico.it", PagAmicoPrint.qrCode("www.pagamico.it", 4, 2), "QR code (esempio del manuale)")
    eq(
        "PTBCBC210029876543210123",
        PagAmicoPrint.barcode(BarcodeType.EAN13, "876543210123", 100, BarcodeTextPosition.BELOW, 9),
        "barcode EAN13 (esempio del manuale)"
    )

    val cmds = PagAmicoPrintJob().align(PrintAlignment.CENTER).bold().line("SCONTRINO").cut().build()
    check(cmds.first() == "PTSTST" && cmds.last() == "PTSTEN", "il job e' delimitato da PTSTST/PTSTEN")
    check(cmds.size == 6, "numero comandi del job")

    val ok = PrinterStatus.parse(PagAmicoResponse.tryParse("{\"response\":\"OK\",\"errorType\":\"00000000\"}")!!)
    check(ok.installed && ok.canPrint && !ok.paperEmpty, "stato stampante OK")

    val ko = PrinterStatus.parse(PagAmicoResponse.tryParse("{\"response\":\"OK\",\"errorType\":\"10100010\"}")!!)
    check(ko.printing && ko.coverOpen && !ko.paperEmpty && !ko.paperLow, "stato stampante 10100010: b7 e b1 attivi")

    val none = PrinterStatus.parse(PagAmicoResponse.tryParse("{\"response\":\"ER\",\"errorType\":\"NO PRINTER\"}")!!)
    check(!none.installed, "stampante non installata")
}

// ---------------------------------------------------------------- display

private fun displayTests() {
    section("Display")

    eq(
        "DT|ESEMPIO FINESTRA CON TESTO|38|1|0|",
        PagAmicoDisplay.showText("ESEMPIO FINESTRA CON TESTO", DisplayPosition.TOP, 38, FontStyle.BOLD, FontColor.BLUE),
        "DT finestra in alto (esempio del manuale)"
    )
    eq(
        "DM|29|0|2|Selezionare la forma di pagamento|Contanti|POS|ANNULLA",
        PagAmicoDisplay.messageBox("Selezionare la forma di pagamento", "Contanti", "POS", "ANNULLA", 29, FontStyle.NORMAL, FontColor.GREEN),
        "DM messagebox (esempio del manuale)"
    )
    eq(
        "DM|29|0|2|Confermi?|SI|NO|[]",
        PagAmicoDisplay.messageBox("Confermi?", "SI", "NO", "", 29, FontStyle.NORMAL, FontColor.GREEN),
        "DM terzo bottone nascosto"
    )
    eq(
        "QR|Avvicinare il QR code al lettore ottico|16|1|2|0",
        PagAmicoDisplay.readCode("Avvicinare il QR code al lettore ottico", KeyboardMode.SHOW_KEYBOARD, 16, FontStyle.BOLD, FontColor.GREEN),
        "QR lettura codice (esempio del manuale)"
    )

    val layout = ListLayout(
        title = ListTextProperties(
            "ELENCO PRODOTTI ACQUISTATI",
            "FF2E86C1",
            "FFFFFFFF",
            28,
            FontStyle.BOLD,
            TextAlignment.CENTER
        ),
        body = mapOf("a" to ListCell.of("Descrizione"), "b" to ListCell.of("Quantita"))
    )
    val layoutJson = layout.toCompactJson()
    check(
        layoutJson.startsWith("{\"listView\":{\"titleProperties\":{\"tX\":\"ELENCO PRODOTTI ACQUISTATI\""),
        "JSON struttura lista compatto"
    )
    check(!layoutJson.contains("\n"), "JSON senza a capo")
    check(PagAmicoDisplay.listLayout(layoutJson).startsWith("TS{"), "comando TS")

    val data = ListData(
        rows = listOf(ListRow("01/01/2025", "Acquisto merci", BigDecimal("120.50"), BigDecimal("0.00"))),
        footer = ListFooter("Totale", BigDecimal("200.50"), BigDecimal("200.00"), BigDecimal("0.50"))
    )
    check(PagAmicoDisplay.listData(data.toCompactJson()).startsWith("ID{\"movimenti\":["), "comando ID")

    throws("JSON oltre 3999 caratteri deve fallire") { PagAmicoDisplay.listLayout("x".repeat(4000)) }
}

// ---------------------------------------------------------------- infrastruttura

internal fun section(name: String) = println("\n--- $name ---")

internal fun eq(expected: String, actual: String?, what: String) {
    if (expected == actual) pass(what) else fail("$what\n      atteso  : '$expected'\n      ottenuto: '$actual'")
}

internal fun check(condition: Boolean, what: String) {
    if (condition) pass(what) else fail(what)
}

private fun throws(what: String, block: () -> Unit) {
    try {
        block()
        fail("$what (nessuna eccezione)")
    } catch (e: Throwable) {
        pass(what)
    }
}

private fun pass(what: String) {
    passed++
    println("  OK   $what")
}

internal fun fail(what: String) {
    failed++
    println("  FAIL $what")
}
