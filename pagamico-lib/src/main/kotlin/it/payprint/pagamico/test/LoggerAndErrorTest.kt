package it.payprint.pagamico.test

import it.payprint.pagamico.client.FrameKind
import it.payprint.pagamico.client.MachineState
import it.payprint.pagamico.client.PagAmicoErrorCodes
import it.payprint.pagamico.client.PagAmicoErrorList
import it.payprint.pagamico.client.PagAmicoFileLogger
import it.payprint.pagamico.client.PagAmicoFrame
import it.payprint.pagamico.client.PagAmicoTextResponses
import it.payprint.pagamico.display.KeyboardLayout
import it.payprint.pagamico.display.PagAmicoDisplay
import it.payprint.pagamico.response.PagAmicoResponse
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Registro su file (righe intatte con due scrittori, cambio di giorno, ripulitura) e vocabolario degli
 * errori. Gemello di LoggerAndErrorTests.cs: stessi casi, stessi nomi.
 */
internal fun loggerAndErrorTests() {
    section("Registro su file")
    val dir = Files.createTempDirectory("pagamico-test-").toFile()
    try {
        concurrentWriters(dir)
        crossProcessWriters(dir)
        dayChange(dir)
        writersStartedOnDifferentDays(dir)
        sanitize(dir)
    } finally {
        dir.deleteRecursively()
    }

    section("Errori e display")
    errorTests()
}

// ---------------------------------------------------------------- registro su file

private val WELL_FORMED = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}  TX  [ABCD]-\d+$""")
private const val PER_WRITER = 500

/** Due logger distinti sullo stesso file, da due thread dello stesso processo. */
private fun concurrentWriters(dir: File) {
    val a = PagAmicoFileLogger(dir, "concorrenza")
    val b = PagAmicoFileLogger(dir, "concorrenza")
    writeTogether(a, b)
    val lines = a.currentFile.readLines().filter { it.isNotEmpty() }
    a.close()
    b.close()
    check(
        lines.size == 2 * PER_WRITER && lines.all { WELL_FORMED.matches(it) },
        "due logger sullo stesso file da due thread: 1000 righe intatte"
    )
}

/**
 * Tre processi veri sullo stesso file: questo e due JVM figlie. Il lock sul file (FileChannel.lock)
 * deve tenere intere tutte le righe, come il mutex con nome in C#.
 */
private fun crossProcessWriters(dir: File) {
    val java = File(File(System.getProperty("java.home"), "bin"), "java").path
    val classpath = System.getProperty("java.class.path")
    val children = listOf("C", "D").map { label ->
        ProcessBuilder(java, "-cp", classpath, "it.payprint.pagamico.test.LoggerWriterProcessKt",
            dir.path, "processi", label, PER_WRITER.toString())
            .redirectErrorStream(true)
            .start()
    }
    PagAmicoFileLogger(dir, "processi").use { log -> repeat(PER_WRITER) { log.write("TX", "A-$it") } }
    val exited = children.all { it.waitFor(60, TimeUnit.SECONDS) && it.exitValue() == 0 }

    val lines = File(dir, "processi-${LocalDate.now()}.log").readLines().filter { it.isNotEmpty() }
    check(
        exited && lines.size == 3 * PER_WRITER && lines.all { WELL_FORMED.matches(it) },
        "tre processi sullo stesso file: 1500 righe intatte"
    )
}

private fun dayChange(dir: File) {
    var now = LocalDateTime.of(2030, 1, 1, 23, 59, 59, 900_000_000)
    val log = PagAmicoFileLogger(dir, "giorno").also { it.clock = { now } }

    log.write("--", "prima di mezzanotte")
    val yesterday = log.currentFile
    now = LocalDateTime.of(2030, 1, 2, 0, 0, 0, 100_000_000)
    log.write("--", "dopo mezzanotte")
    val today = log.currentFile
    log.close()

    val todayLines = today.readLines().filter { it.isNotEmpty() }
    val yesterdayLines = yesterday.readLines().filter { it.isNotEmpty() }
    check(
        yesterday != today && todayLines.size == 1 && todayLines[0].endsWith("dopo mezzanotte"),
        "cambio di giorno: la riga dopo mezzanotte va nel file nuovo"
    )
    check(
        yesterdayLines.size == 1 && yesterdayLines[0].endsWith("prima di mezzanotte"),
        "cambio di giorno: il file di ieri resta com'era"
    )
}

/** Un logger che ha scritto la prima riga ieri e uno partito oggi scrivono insieme il file di oggi. */
private fun writersStartedOnDifferentDays(dir: File) {
    val today = LocalDateTime.of(2030, 3, 2, 9, 0)
    var clockA = LocalDateTime.of(2030, 3, 1, 23, 0)
    val a = PagAmicoFileLogger(dir, "mutex").also { it.clock = { clockA } }
    val b = PagAmicoFileLogger(dir, "mutex").also { it.clock = { today } }
    a.write("--", "ieri")
    clockA = today
    writeTogether(a, b)
    val lines = b.currentFile.readLines().filter { it.isNotEmpty() }
    a.close()
    b.close()
    check(
        lines.size == 2 * PER_WRITER && lines.all { WELL_FORMED.matches(it) },
        "logger avviati in giorni diversi: sul file di oggi si escludono ancora"
    )
}

private fun writeTogether(a: PagAmicoFileLogger, b: PagAmicoFileLogger) {
    val ta = thread { repeat(PER_WRITER) { a.write("TX", "A-$it") } }
    val tb = thread { repeat(PER_WRITER) { b.write("TX", "B-$it") } }
    ta.join()
    tb.join()
}

private fun sanitize(dir: File) {
    val log = PagAmicoFileLogger(dir, "pulizia")
    var last: String? = null
    log.onLineWritten = { last = it }

    log.write("RX", "a\r\nb\u0002c")
    check(last?.endsWith("a  b<02>c") == true, "CR e LF diventano spazi, i caratteri di controllo <XX>")

    log.maxLineLength = 5
    log.write("RX", "1234567890")
    check(last?.endsWith("12345... (+5 caratteri)") == true, "riga oltre il massimo troncata con il conto")

    log.maxLineLength = 0
    log.enabled = false
    last = null
    log.write("RX", "non registrata")
    val lines = log.currentFile.readLines().filter { it.isNotEmpty() }
    log.close()
    check(last == null && lines.size == 2, "logger disabilitato: nessuna riga")
}

// ---------------------------------------------------------------- errori e display

private fun errorTests() {
    eq(
        "DI|29|0|0|NOME|MARIO|2",
        PagAmicoDisplay.inputBox("NOME", "MARIO", KeyboardLayout.NUMERIC_NO_DECIMALS),
        "DI finestra di input, forma a sette campi"
    )
    eq("DI|29|0|0|A/B||1", PagAmicoDisplay.inputBox("A|B"), "DI: la barra verticale nel titolo diventa /")

    eq("Comando non eseguibile (vedi errorType per lo stato macchina)", PagAmicoErrorCodes.describe("E100"), "descrizione E100")
    eq("Erogazione disabilitata nel setup", PagAmicoErrorCodes.describe("DISPAG"), "descrizione DISPAG")
    eq("Quantita' monete insufficiente per il taglio 50", PagAmicoErrorCodes.describe("QTAMONETE50"), "descrizione QTAMONETE per taglio")
    eq("XYZ", PagAmicoErrorCodes.describe("XYZ"), "codice sconosciuto restituito com'e'")
    check(
        PagAmicoErrorCodes.parseState("5") == MachineState.REBOOTING && PagAmicoErrorCodes.parseState("abc") == MachineState.UNKNOWN,
        "errorType 5 = riavvio, non numerico = sconosciuto"
    )

    val list = PagAmicoErrorList.parse("E1291")
    check(
        list.paymentDigit == 1 && list.coinsDigit == 2 && list.banknotesDigit == 9 && list.btaDigit == 1 &&
            list.warnings().size == 4 && list.requiresOperatorAttention,
        "errorList E1291: quattro anomalie"
    )
    eq("Date non valide nel comando MV", PagAmicoTextResponses.describe("CMD ERROR, DATE INVALID"), "descrizione CMD ERROR di MV")

    check(text("BUSY").isBusy && text("BUSY").isError, "testo BUSY: occupato ed errore")
    check(text("ER BUSY").isBusy, "testo ER BUSY: occupato")
    check(!text("CMD ERROR").isBusy && text("CMD ERROR").isError, "CMD ERROR: errore ma non occupato")
    check(
        !json("""{"response":"ER","errorCode":"E100","errorType":"5"}""").isBusy,
        "ER E100 con errorType 5 (riavvio): non occupato"
    )
}

private fun text(raw: String) = PagAmicoFrame(FrameKind.TEXT, raw)

private fun json(raw: String) = PagAmicoFrame(FrameKind.JSON, raw, PagAmicoResponse.tryParse(raw))
