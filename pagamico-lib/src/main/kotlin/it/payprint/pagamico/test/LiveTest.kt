package it.payprint.pagamico.test

import it.payprint.pagamico.client.ImagePacketLayout
import it.payprint.pagamico.client.PagAmicoClient
import it.payprint.pagamico.client.PagAmicoFileLogger
import it.payprint.pagamico.commands.CashFloatTarget
import it.payprint.pagamico.commands.DenominationToggle
import it.payprint.pagamico.commands.StockThreshold
import it.payprint.pagamico.display.DisplayPosition
import it.payprint.pagamico.display.FontStyle
import it.payprint.pagamico.display.KeyboardLayout
import it.payprint.pagamico.display.KeyboardMode
import it.payprint.pagamico.display.ListCell
import it.payprint.pagamico.display.ListData
import it.payprint.pagamico.display.ListFooter
import it.payprint.pagamico.display.ListLayout
import it.payprint.pagamico.display.ListRow
import it.payprint.pagamico.display.ListTextProperties
import it.payprint.pagamico.display.TextAlignment
import it.payprint.pagamico.exceptions.PagAmicoException
import it.payprint.pagamico.print.BarcodeType
import it.payprint.pagamico.print.PagAmicoPrint
import it.payprint.pagamico.print.PagAmicoPrintJob
import it.payprint.pagamico.print.PrintAlignment
import it.payprint.pagamico.print.PrinterFont
import it.payprint.pagamico.print.PrinterFontMode
import it.payprint.pagamico.print.Underline
import it.payprint.pagamico.response.PagAmicoResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.Base64
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

/**
 * Collaudo end-to-end della libreria contro un pagAmico reale o contro il simulatore
 * del pagAmico Dev Kit (sezione Simulatore -> Avvia).
 *
 * Esecuzione:
 *   gradle :pagamico-lib:run -PmainClass=it.payprint.pagamico.test.LiveTestKt --args="127.0.0.1 9100"
 *   gradle :pagamico-lib:run -PmainClass=it.payprint.pagamico.test.LiveTestKt --args="127.0.0.1 9100 base,cash,collect"
 *   gradle :pagamico-lib:run -PmainClass=it.payprint.pagamico.test.LiveTestKt --args="127.0.0.1 9100 --terminatore cr --pausa 0"
 *
 * Gruppi: base, cash, collect, display, print, system, movimenti, di
 * Opzioni: --terminatore nessuno|cr|crlf, --pausa <ms>
 */
/**
 * Gruppi eseguiti quando non se ne indicano. Esclude "riavvii", che riavvia pagAmico e POS:
 * innocuo sul simulatore, tutt'altro su una macchina in esercizio.
 */
private const val DEFAULT_GROUPS =
    "base,cash,collect,erogazione,ricariche,display,display2,print,print2,system,pos2,immagini,movimenti"

/** PNG 1x1 trasparente: serve solo a verificare l'incapsulamento binario di SF / SI. */
private const val TINY_PNG_BASE64 =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="

private val results = mutableListOf<Triple<String, Boolean, String>>()
private lateinit var client: PagAmicoClient
private var logger: PagAmicoFileLogger? = null

fun main(rawArgs: Array<String>): Unit = runBlocking {
    // Opzioni: --terminatore nessuno|cr|crlf  --pausa <ms>. Il resto sono argomenti posizionali.
    var terminatorOption: String? = null
    var intervalOption: Long? = null
    val positional = mutableListOf<String>()
    var i = 0
    while (i < rawArgs.size) {
        when {
            rawArgs[i] == "--terminatore" && i + 1 < rawArgs.size -> terminatorOption = rawArgs[++i]
            rawArgs[i] == "--pausa" && rawArgs.getOrNull(i + 1)?.toLongOrNull() != null -> intervalOption = rawArgs[++i].toLong()
            else -> positional += rawArgs[i]
        }
        i++
    }
    val args = positional.toTypedArray()

    val host = args.getOrNull(0) ?: "127.0.0.1"
    val port = args.getOrNull(1)?.toIntOrNull() ?: PagAmicoClient.DEFAULT_PORT
    val groups = (args.getOrNull(2) ?: DEFAULT_GROUPS)
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    println("Collaudo pagAmico su $host:$port  [gruppi: ${groups.joinToString(", ")}]")
    println("=".repeat(100))

    client = when (terminatorOption?.lowercase()) {
        null -> PagAmicoClient(host, port)
        "cr" -> PagAmicoClient(host, port, commandTerminator = "\r")
        "crlf" -> PagAmicoClient(host, port, commandTerminator = "\r\n")
        else -> PagAmicoClient(host, port, commandTerminator = "")
    }
    val terminator = client.commandTerminator
    intervalOption?.let { client.minimumCommandIntervalMs = it }
    println("Terminatore: ${when (terminator) { "" -> "nessuno"; "\r" -> "CR"; else -> "CR+LF" }}, " +
            "pausa minima fra invii: ${client.minimumCommandIntervalMs} ms")
    client.onCommandSent = { cmd -> println("    TX > $cmd") }
    client.onDisconnected = { ex -> println("    !! disconnesso: ${ex?.message ?: "chiusura richiesta"}") }

    // diagnostica interna della libreria: finisce anche nel file, cosi' un passo fallito
    // si puo' ricostruire senza rilanciare il collaudo
    client.onTrace = { message ->
        println("    ..   $message")
        logger?.write("..", message)
    }

    try {
        client.connect()
        println("Connesso.\n")
    } catch (e: Exception) {
        println("CONNESSIONE FALLITA: ${e.message}")
        println("Avviare il simulatore dal pagAmico Dev Kit (sezione Simulatore -> Avvia) e riprovare.")
        exitProcess(2)
    }

    // il traffico in ingresso viene stampato mano a mano
    val loggerScope = CoroutineScope(Dispatchers.Default)
    val printer: Job = loggerScope.launchFrames()

    logger = PagAmicoFileLogger(prefix = "collaudo").also {
        it.attach(client, loggerScope)
        println("Log della sessione: ${it.currentFile.absolutePath}\n")
    }

    if ("base" in groups) baseGroup()
    if ("cash" in groups) cashGroup()
    if ("collect" in groups) collectGroup()
    if ("erogazione" in groups) dispenseGroup()
    if ("ricariche" in groups) reloadGroup()
    if ("display" in groups) displayGroup()
    if ("display2" in groups) displayExtraGroup()
    if ("print" in groups) printGroup()
    if ("print2" in groups) printExtraGroup()
    if ("system" in groups) systemGroup()
    if ("pos2" in groups) posExtraGroup()
    if ("immagini" in groups) imageGroup()
    if ("movimenti" in groups) movementsGroup()
    if ("di" in groups) inputBoxProbe()
    if ("riavvii" in groups) restartGroup()

    printer.cancel()
    logger?.close()
    client.disconnect()

    println()
    println("=".repeat(100))
    results.forEach { (step, ok, detail) ->
        println("${if (ok) "OK  " else "FAIL"}  ${step.padEnd(46)} ${trim(detail, 120)}")
    }
    val failed = results.count { !it.second }
    println("\n${results.size - failed} passi riusciti, $failed falliti")
    exitProcess(if (failed == 0) 0 else 1)
}

private fun CoroutineScope.launchFrames(): Job = launch {
    client.frames.collect { println("    RX < ${trim(it.raw, 220)}") }
}

private fun trim(s: String, max: Int) =
    if (s.length <= max) s.replace("\n", " ") else s.take(max).replace("\n", " ") + "..."

private suspend fun step(name: String, body: suspend () -> String) {
    println("--- $name")
    try {
        val detail = body()
        results += Triple(name, true, detail)
        logger?.write("+", "$name: $detail")
        println("    => OK  ${trim(detail, 200)}\n")
    } catch (e: Exception) {
        results += Triple(name, false, "${e::class.simpleName}: ${e.message}")
        logger?.write("!", "$name: FALLITO - ${e.message}")
        println("    => FAIL ${e.message}\n")
    }
}

// ---------------------------------------------------------------- gruppi

private suspend fun baseGroup() {
    step("[ST] richiesta situazione") {
        val r = client.status()
        if (r.response != "ST" && r.response != "OK") throw PagAmicoException("response inatteso: ${r.response}")
        "modello=${r.typePagAmico} fw=${r.firmwareVersion} sn=${r.serialNumber} errorList=${r.errorList}"
    }

    step("[ST] decodifica giacenze") {
        val r = client.status()
        if (r.coins.isEmpty()) throw PagAmicoException("array coins vuoto")
        val coins = r.coinsByDenomination.toSortedMap().entries.joinToString(" ") { "%.2f=%d".format(it.key / 100.0, it.value) }
        val notes = r.banknotesAvailableByDenomination.toSortedMap().entries.joinToString(" ") { "${it.key}E=${it.value}" }
        "monete[$coins] banconote[$notes] cassetti=${r.drawers.count { it.isConfigured }}"
    }

    step("[CL] pulisci display (nessuna risposta attesa)") {
        client.clearDisplay()
        delay(300.milliseconds)
        "inviato"
    }

    step("[LO] rinvio ultimo JSON") {
        val r = client.lastJson()
        "response=${r.response}"
    }
}

private suspend fun cashGroup() {
    step("[SM] soglie monete 5/100") {
        "response=" + client.setCoinStock(List(6) { StockThreshold(5, 100) }).response
    }
    step("[SB] soglie banconote 2/50") {
        "response=" + client.setBanknoteStock(List(6) { StockThreshold(2, 50) }).response
    }
    step("[EM] abilita tutti i tagli monete") {
        "response=" + client.setCoinAcceptance(List(6) { DenominationToggle.ALL }).response
    }
    step("[EB] abilita tutti i tagli banconote") {
        "response=" + client.setBanknoteAcceptance(List(6) { DenominationToggle.ALL }).response
    }
    step("[AF] aggiorna fondo cassa (monete + banconote)") {
        "response=" + client.updateCashFloat(CashFloatTarget.BOTH).response
    }
    step("[BT] azzera cassetto BTA") {
        val r = client.resetBta()
        "response=${r.response} importoInBTA=${r.amountResettedBanknotesInBta}"
    }
}

private suspend fun collectGroup() {
    step("[IN] incasso 1,50 EUR con parziali") {
        var partials = 0
        val r = client.collectCash(BigDecimal("1.50"), timeoutMs = 120_000) { partials++ }
        if (r.response != "IN" && r.response != "AN") throw PagAmicoException("response inatteso: ${r.response}")
        "response=${r.response} incassato=${r.collectedAmount} restoMonete=${r.changeCoins} " +
            "restoBanconote=${r.changeBanknotes} nonErogato=${r.amountUnpaid} parziali=$partials Id=${r.id}"
    }

    step("[IN]+[AN] incasso annullato dal client") {
        // la cancellazione della coroutine fa inviare [AN] al pagAmico
        withTimeoutOrNull(250.milliseconds) { client.collectCash(BigDecimal("500.00"), timeoutMs = 60_000) }
        delay(2000.milliseconds)
        val r = client.lastJson()
        when (r.response) {
            "AN" -> "annullato: restituito=${r.changeReturn} halted=${r.halted} errorType=${r.errorType}"
            "IN" -> "il cliente virtuale ha completato l'incasso prima dell'annullo " +
                "(incassato=${r.collectedAmount}): alzare 'Ritardo fra un pezzo e l'altro' nel simulatore"
            else -> "ultimo JSON dopo l'annullo: response=${r.response} restituito=${r.changeReturn}"
        }
    }

    step("[IN]+[CM] commit di un incasso parziale") {
        // il CM parte mentre l'incasso e' aperto, come lo usera' il gestionale: non si accoda dietro l'IN
        coroutineScope {
            val collect = async { client.collectCash(BigDecimal("80.00"), timeoutMs = 60_000) }
            delay(2500.milliseconds)
            if (collect.isCompleted) {
                val done = collect.await()
                return@coroutineScope "l'incasso si e' chiuso prima del CM (response=${done.response} " +
                    "incassato=${done.collectedAmount}): alzare 'Ritardo fra un pezzo e l'altro' nel simulatore"
            }

            val r = client.commit(60_000)
            if (r.errorCode == "NO") {
                val a = client.cancelOperation()
                return@coroutineScope "CM rifiutato (errorCode NO), incasso annullato: response=${a.response} restituito=${a.changeReturn}"
            }
            if (r.errorCode == "OK") throw PagAmicoException("commit() ha restituito l'accettazione invece dell'esito (difetto D1)")

            val c = collect.await()
            "response=${r.response} trattenuto=${r.collectedAmount} (controllo committedAmount=${r.committedAmount}) " +
                "errorCode='${r.errorCode}' incasso chiuso da ${c.response}"
        }
    }

    step("[PA] erogazione 2,00 EUR") {
        val r = client.dispense(BigDecimal("2.00"))
        "response=${r.response} erogato=${r.amountPaid} nonErogato=${r.amountUnpaid}"
    }
}

// ---------------------------------------------------------------- erogazione e movimentazione

private suspend fun dispenseGroup() {
    step("[P2] eroga 1 banconota del taglio piu' basso disponibile") {
        val value = pickAvailableBanknote()
        if (value == null) {
            "nessuna banconota nei riciclatori: passo non applicabile"
        } else {
            val q = banknoteVector(value)
            val r = client.dispenseBanknotes(q[0], q[1], q[2], q[3], q[4], q[5])
            "erogata 1 banconota da $value EUR: response=${r.response} " +
                "erogato=${r.amountPaid} errorCode=${r.errorCode}"
        }
    }

    step("[PM] eroga monete 1x0,10 + 1x0,05") {
        val r = client.dispenseCoins(1, 1, 0, 0, 0, 0)
        "response=${r.response} erogato=${r.amountPaid} errorCode=${r.errorCode}"
    }

    step("[M2] sposta nel BTA 1 banconota fra quelle presenti") {
        val value = pickAvailableBanknote()
        if (value == null) {
            "nessuna banconota nei riciclatori: passo non applicabile"
        } else {
            val q = banknoteVector(value)
            val r = client.moveBanknotesToBta(q[0], q[1], q[2], q[3], q[4], q[5])
            "spostata 1 banconota da $value EUR: response=${r.response} errorCode=${r.errorCode}"
        }
    }

    step("[MF] sposta 1 moneta da 0,05 nel cassetto di recupero") {
        val r = client.moveCoinsToCashbox(1, 0, 0, 0, 0, 0)
        "response=${r.response} errorCode=${r.errorCode}"
    }

    step("[AZ] azzera le banconote presenti") {
        val r = client.resetBanknotes()
        "response=${r.response} errorCode=${r.errorCode}"
    }

    step("[I2] incasso 1,00 EUR con timeout di 5 secondi") {
        val r = client.collectCashWithTimeout(BigDecimal("1.00"), 5)
        "response=${r.response} incassato=${r.collectedAmount}"
    }

    step("[IM] incasso automatico contanti/POS di 1,00 EUR") {
        val r = client.collectAuto(BigDecimal("1.00"), timeoutMs = 90_000)
        "response=${r.response} incassato=${r.collectedAmount} " +
            "errorCode=${r.errorCode} (CONT = contanti, POS = carta)"
    }
}

private val BANKNOTE_DENOMINATIONS = intArrayOf(5, 10, 20, 50, 100, 200)

/**
 * Taglio con almeno una banconota nei riciclatori, il piu' basso disponibile. I passi che
 * erogano o spostano banconote devono adattarsi allo stato reale della macchina: dopo
 * un'erogazione il taglio potrebbe non esserci piu'.
 */
private suspend fun pickAvailableBanknote(): Int? =
    client.status().banknotesAvailableByDenomination
        .filter { it.value > 0 && it.key in BANKNOTE_DENOMINATIONS }
        .keys.minOrNull()

/** Vettore delle sei quantita' (5, 10, 20, 50, 100, 200) con 1 sul taglio indicato. */
private fun banknoteVector(denomination: Int): IntArray {
    val q = IntArray(6)
    val index = BANKNOTE_DENOMINATIONS.indexOf(denomination)
    if (index >= 0) q[index] = 1
    return q
}

// ---------------------------------------------------------------- tutte le ricariche

private suspend fun reloadGroup() {
    val sessions: List<Pair<String, suspend () -> PagAmicoResponse>> = listOf(
        "[RM] ricarica monete senza fondo cassa" to { client.startCoinReload(false) },
        "[R3] ricarica monete con fondo cassa" to { client.startCoinReload(true) },
        "[RB] ricarica banconote senza fondo cassa" to { client.startBanknoteReload(false) },
        "[R2] ricarica banconote con fondo cassa" to { client.startBanknoteReload(true) },
        "[RS] ricarica mista con fondo cassa" to { client.startMixedReload(true) },
        "[VC] ricarica mista con parziali" to { client.startMixedReload(false, sendPartials = true) },
        "[VS] ricarica mista con parziali e fondo cassa" to { client.startMixedReload(true, sendPartials = true) },
    )

    sessions.forEach { (name, start) ->
        step("$name + [FR]") {
            val opened = start()
            delay(400.milliseconds)
            val closed = client.endReload()
            "avvio=${opened.response} fine=${closed.response}"
        }
    }
}

// ---------------------------------------------------------------- display, il resto

private suspend fun displayExtraGroup() {
    step("[DG] finestra di testo in basso") {
        client.showText("COLLAUDO - FINESTRA IN BASSO", DisplayPosition.BOTTOM)
        delay(500.milliseconds)
        client.closeText()
        "DG + DS inviati"
    }

    step("[DI] finestra di input con tastiera numerica") {
        val value = client.readInput(
            "Inserire il codice cliente", "",
            KeyboardLayout.NUMERIC_NO_DECIMALS, timeoutMs = 45_000
        )
        client.closeMessageBox()
        if (value == null) "input annullato (AN)" else "testo digitato: $value"
    }
}

// ---------------------------------------------------------------- stampa, comandi singoli

private suspend fun printExtraGroup() {
    val singles = listOf(
        "[PTITON] corsivo attivo" to PagAmicoPrint.italic(true),
        "[PTITOF] corsivo disattivo" to PagAmicoPrint.italic(false),
        "[PTDBON] doppia grandezza attiva" to PagAmicoPrint.doubleSize(true),
        "[PTDBOF] doppia grandezza disattiva" to PagAmicoPrint.doubleSize(false),
        "[PTUN01] sottolineato livello 1" to PagAmicoPrint.underline(Underline.SINGLE),
        "[PTUN00] sottolineato disattivo" to PagAmicoPrint.underline(Underline.NONE),
        "[PTFBNO] font B normale" to PagAmicoPrint.font(PrinterFont.B, PrinterFontMode.NORMAL),
        "[PTFANO] font A normale" to PagAmicoPrint.font(PrinterFont.A, PrinterFontMode.NORMAL),
        "[PTCP] code page 00" to PagAmicoPrint.codePage(0),
        "[PTPRWR] stampa senza avanzamento" to PagAmicoPrint.write("prova senza a capo"),
        "[PTPRRE] carattere ripetuto" to PagAmicoPrint.repeatChar('*', 10),
        "[PTBCBC] barcode EAN13" to PagAmicoPrint.barcode(BarcodeType.EAN13, "876543210123"),
        "[PTPRDT] sequenza ESC/POS diretta" to PagAmicoPrint.rawEscPos("prova ESC/POS"),
    )

    singles.forEach { (name, command) ->
        step(name) {
            val frame = client.request(command, 6_000) { true }
            if (frame.isError) throw PagAmicoException("rifiutato: ${frame.raw}")
            "risposta: ${frame.raw}"
        }
    }

    step("[PTSTAN] annullo di una stampa non avviata") {
        // ER NO-PRINT e' la risposta corretta: non c'era nulla da annullare
        val frame = client.request(PagAmicoPrint.cancelPrint(), 6_000) { true }
        "risposta: ${frame.raw}"
    }
}

// ---------------------------------------------------------------- POS, il resto

private suspend fun posExtraGroup() {
    step("[PLT] ultima transazione POS con ristampa") {
        val r = client.readLastPosTransaction(true)
        "response=${r.response} errorCode=${r.errorCode}"
    }

    step("[PS] chiusura giornaliera POS") {
        val r = client.closePosDay()
        "response=${r.response} errorCode=${r.errorCode} errorType=${r.errorType}"
    }

    step("[PP] primo DLL POS") {
        val r = client.posFirstDll(60_000)
        "response=${r.response} errorCode=${r.errorCode}"
    }
}

// ---------------------------------------------------------------- immagini

private suspend fun imageGroup() {
    val png = Base64.getDecoder().decode(TINY_PNG_BASE64)

    step("[SI] invio immagine temporanea") {
        client.sendTemporaryImage(png)
        delay(600.milliseconds)
        "inviati ${png.size} byte con l'incapsulamento del manuale"
    }

    step("[SR] rimozione immagine temporanea") {
        client.removeTemporaryImage()
        delay(400.milliseconds)
        "inviato"
    }

    step("[SF] invio logo permanente") {
        client.sendLogo(png)
        delay(600.milliseconds)
        "inviati ${png.size} byte. NB: i due manuali indicano incapsulamenti diversi, " +
            "questo e' quello descritto a testo"
    }

    step("[SI] invio con l'incapsulamento dell'esempio Python") {
        val previous = client.imageLayout
        client.imageLayout = ImagePacketLayout.PYTHON_SAMPLE
        try {
            client.sendTemporaryImage(png)
            delay(600.milliseconds)
            client.removeTemporaryImage()
            "inviato: confrontare sul display quale dei due incapsulamenti viene accettato"
        } finally {
            client.imageLayout = previous
        }
    }
}

// ---------------------------------------------------------------- riavvii (solo su richiesta)

private suspend fun restartGroup() {
    step("[PZ] riavvio del POS") {
        val r = client.rebootPos(60_000)
        "response=${r.response} errorCode=${r.errorCode}"
    }

    step("[RI] riavvio del pagAmico") {
        client.reboot()
        delay(1500.milliseconds)
        "inviato: il dispositivo si riavvia, la connessione cadra'"
    }
}

private suspend fun displayGroup() {
    step("[DT] finestra di testo in alto") {
        client.showText("COLLAUDO IN CORSO")
        delay(500.milliseconds)
        client.closeText()
        "DT + DS inviati"
    }

    step("[DM] messagebox a 3 bottoni") {
        val button = client.showMessageBox("Selezionare la forma di pagamento", "Contanti", "POS", "ANNULLA", timeoutMs = 45_000)
        client.closeMessageBox()
        "bottone premuto: BT$button"
    }

    step("[QR] lettura codice") {
        val code = client.readCode("Avvicinare il codice al lettore", KeyboardMode.NO_KEYBOARD, timeoutMs = 45_000)
        client.closeCodeReader()
        if (code == null) "lettura annullata (AN)" else "codice letto: $code"
    }

    step("[TS]+[ID]+[CO] lista sul display") {
        val layout = ListLayout(
            title = ListTextProperties(
                "COLLAUDO LISTA",
                "FF2E86C1",
                "FFFFFFFF",
                28,
                FontStyle.BOLD,
                TextAlignment.CENTER
            ),
            body = mapOf(
                "a" to ListCell.of("Data"),
                "b" to ListCell.of("Descrizione"),
                "c" to ListCell.of("Dare", 14, TextAlignment.RIGHT),
                "d" to ListCell.of("Avere", 14, TextAlignment.RIGHT)
            ),
            footer = mapOf(
                "description" to ListCell.of("Numero articoli"),
                "total1" to ListCell.of("Imponibile", 14, TextAlignment.RIGHT),
                "total2" to ListCell.of("Imposta", 14, TextAlignment.RIGHT),
                "total3" to ListCell.of("Totale", 14, TextAlignment.RIGHT)
            )
        )
        val data = ListData(
            rows = listOf(
                ListRow("01/01/2025", "Acquisto merci", BigDecimal("120.50"), BigDecimal("0.00")),
                ListRow("02/01/2025", "Vendita prodotti", BigDecimal("0.00"), BigDecimal("200.00"))
            ),
            footer = ListFooter("2", BigDecimal("200.50"), BigDecimal("200.00"), BigDecimal("0.50"))
        )

        val json = layout.toCompactJson()
        client.showListLayout(json)
        client.showListData(data.toCompactJson())
        delay(800.milliseconds)
        client.closeList()
        "TS di ${json.length} caratteri (limite 3999), ID e CO inviati"
    }
}

private suspend fun printGroup() {
    step("[PTSTAT] stato stampante") { client.printerStatus().toString() }

    step("stampa scontrino di prova") {
        val job = PagAmicoPrintJob()
            .reset()
            .align(PrintAlignment.CENTER)
            .font(PrinterFont.A, PrinterFontMode.DOUBLE_HEIGHT_WIDTH)
            .line("PRIME SOFTWARE")
            .font(PrinterFont.A, PrinterFontMode.NORMAL)
            .feed()
            .align(PrintAlignment.LEFT)
            .separator()
            .line("Articolo 1                 12,00")
            .line("Articolo 2                  3,50")
            .separator()
            .bold()
            .line("TOTALE                     15,50")
            .bold(false)
            .feed()
            .align(PrintAlignment.CENTER)
            .qrCode("www.pagamico.it")
            .feed(2)
            .cut()

        val commands = job.build()
        client.print(job)
        "${commands.size} comandi inviati, da PTSTST a PTSTEN"
    }
}

private suspend fun systemGroup() {
    step("[PR] totali POS") {
        val r = client.readPosTotals()
        "response=${r.response} tot1=${r.posTot1} tot2=${r.posTot2}"
    }

    step("[PL] ultima transazione POS (senza ristampa)") {
        val r = client.readLastPosTransaction(false)
        val msg = r.posFinancialTransactionEndResponseMessage
        "response=${r.response} messaggioPOS=${if (msg.isNullOrEmpty()) "assente" else "${msg.length} caratteri"}"
    }

    step("[PO] incasso POS 3,20 EUR") {
        val r = client.collectPos(BigDecimal("3.20"), timeoutMs = 60_000)
        "response=${r.response} incassato=${r.collectedAmount} errorCode=${r.errorCode} errorType=${r.errorType}"
    }

    step("[RC]+[FR] sessione di ricarica mista") {
        val start = client.startMixedReload(updateCashFloat = false)
        delay(1500.milliseconds)
        val end = client.endReload()
        "avvio=${start.response} fine=${end.response}"
    }

    step("comando inesistente -> CMD ERROR") {
        val frame = client.request("XX", 10_000) { true }
        if (!frame.isError) throw PagAmicoException("atteso un errore, ricevuto: ${frame.raw}")
        "risposta: ${frame.raw}"
    }
}

private suspend fun movementsGroup() {
    step("[MV] elenco movimenti di oggi") {
        val today = LocalDateTime.now().toLocalDate()
        val list = client.movements(today.atStartOfDay(), today.atTime(23, 59))
        "${list.size} movimenti" + if (list.isNotEmpty()) ", ultimo: ${list[0]}" else ""
    }

    step("[MI] movimento per Id") {
        val id = client.status().id ?: 1L
        val m = client.movement(id)
        if (m == null) "nessun movimento con Id $id" else "Id $id: $m"
    }
}

/**
 * Il comando [ DI] non e' documentato nei manuali: prova le varianti plausibili e riporta
 * quale viene accettata dal dispositivo/simulatore.
 *
 * La prima variante e' l'esempio del produttore, estratto dallo snapshot Dart del pagAmico
 * Dev Kit (data/app.so): "DI|16|0|1|SCRIVI NOME|18|1|0|OK|ANNULLA|ESCI|3". Dodici campi:
 * dimensione/stile/colore del titolo, il titolo, gli stessi tre attributi per il campo di
 * input, le tre etichette dei bottoni e infine il tipo di tastiera. Le altre quattro sono i
 * tentativi fatti prima di trovarlo: si tengono finche' la macchina vera non conferma quale
 * accetta davvero.
 */
private suspend fun inputBoxProbe() {
    val variants = listOf(
        "DI|dimT|stileT|coloreT|titolo|dimI|stileI|coloreI|bt1|bt2|bt3|tastiera (esempio Dev Kit)"
            to "DI|16|0|1|Inserire il codice|18|1|0|OK|ANNULLA|ESCI|3",
        "DI|dim|stile|colore|titolo|iniziale|tastiera" to "DI|29|0|0|Inserire il codice||1",
        "DI|dim|stile|colore|titolo|tastiera" to "DI|29|0|0|Inserire il codice|1",
        "DI|titolo|dim|stile|colore|tastiera" to "DI|Inserire il codice|29|0|0|1",
        "DI|dim|stile|colore|titolo|" to "DI|29|0|0|Inserire il codice|"
    )

    variants.forEach { (name, command) ->
        step("[DI] variante $name") {
            val frame = client.request(command, 20_000) { it.isText && it.raw.trim().isNotEmpty() }
            client.closeMessageBox()
            if (frame.isError) throw PagAmicoException("rifiutata: ${frame.raw}")
            "accettata, risposta: ${frame.raw}"
        }
        delay(500.milliseconds)
    }
}
