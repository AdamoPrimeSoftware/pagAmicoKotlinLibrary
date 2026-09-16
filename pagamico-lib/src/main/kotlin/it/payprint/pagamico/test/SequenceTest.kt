package it.payprint.pagamico.test

import it.payprint.pagamico.client.ImagePacketLayout
import it.payprint.pagamico.client.PagAmicoClient
import it.payprint.pagamico.client.PagAmicoFrame
import it.payprint.pagamico.commands.CashFloatTarget
import it.payprint.pagamico.exceptions.PagAmicoBusyException
import it.payprint.pagamico.exceptions.PagAmicoCollectionCancelledException
import it.payprint.pagamico.exceptions.PagAmicoCollectionOpenException
import it.payprint.pagamico.exceptions.PagAmicoConnectionLostException
import it.payprint.pagamico.exceptions.PagAmicoException
import it.payprint.pagamico.exceptions.PagAmicoRejectedException
import it.payprint.pagamico.exceptions.PagAmicoTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.collections.plusAssign
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds

/**
 * Test offline delle sequenze di incasso: il client vero parla con un finto pagAmico su 127.0.0.1,
 * che riceve i comandi e risponde con le sequenze dell'esito della risposta PayPrint (cap. 3).
 * Nessuna macchina, nessun simulatore. Gemello di SequenceTests.cs: stessi casi, stessi nomi.
 *
 * Una differenza voluta, ed e' la divergenza nota sull'annullo: in C# il chiamante che annulla riceve
 * l'esito; in Kotlin la cancellazione si propaga subito come PagAmicoCollectionCancelledException, che
 * porta l'esito in outcome, e l'esito arriva anche a onOrphanFrame.
 */

// frame usati dai casi: le sequenze sono quelle del log del simulatore del 2/9 e del manuale 2.33
private const val OK = """{"response":"OK"}"""
private const val IN_FINAL =
    """{"response":"IN","amountRequested":80,"collectedAmount":80,"changeCoins":0,"changeBanknotes":0,"amountUnpaid":0}"""
private const val CM_ACCEPTED = """{"response":"CM","errorCode":"OK","collectedAmount":470,"committedAmout":0.0}"""
private const val CM_FINAL = """{"response":"CM","errorCode":"","collectedAmount":470,"committedAmout":470.0}"""
private const val CM_REFUSED = """{"response":"CM","errorCode":"NO"}"""
private const val AN_FINAL = """{"response":"AN","changeReturn":30,"halted":"FALSE"}"""
private const val ER_BUSY = """{"response":"ER","errorCode":"E100","errorType":"99"}"""
private const val AN_HALTED = """{"response":"AN","changeReturn":20,"halted":"TRUE"}"""
private const val PO_FINAL = """{"response":"PO","amountRequested":3.2,"collectedAmount":3.2}"""
private const val IM_FINAL = """{"response":"IM","errorCode":"CONT","collectedAmount":1}"""
private const val ST_FINAL = """{"response":"ST","errorList":"E0000"}"""

private fun p(collected: Int) = """{"response":"p","collectedAmount":$collected}"""

private val EIGHTY = BigDecimal("80")

internal fun sequenceTests() {
    section("Sequenze di incasso")
    runBlocking {
        busyAfterOk()
        cmdErrorAfterOk()
        busyBeforeOk()
        cmdErrorBeforeOk()
        erBusyBeforeOk()
        commitDuringCollection()
        commitRefused()
        commitBeforeOk()
        singleClosing()
        cancelByCaller()
        blockedSends()
        orphanWithoutWaiter()
        connectionLostWhileCollecting()
        commitOutsideCollection()
        cancelDuringCommit()
        cancelBeforeOk()
        otherCollections()
        forcedCloseFromPanel()
        dropWhileCommitWaitsForOk()
        disconnectWhileCollecting()
        collectionTimeout()
        dropAfterCallerCancel()
    }

    section("Comandi semplici e invio")
    runBlocking {
        lateCommitBeforeStatus()
        okClosesSimpleCommand()
        textErrorClosesSimpleCommand()
        lastJsonAcceptsAnything()
        defaultPauseBetweenCommands()
        terminatorAppended()
        keepAliveConfigured()
        imagePackets()
        commandNotifiedBeforeResponse()
    }
}

// ---------------------------------------------------------------- D3: predicato in due fasi

private suspend fun busyAfterOk() = session { s ->
    val partials = Collections.synchronizedList(mutableListOf<BigDecimal?>())
    val t = s.async { s.client.collectCash(EIGHTY) { partials += it.collectedAmount } }
    s.fake.expect("IN008000")
    s.fake.json(OK)
    s.fake.json(p(10))
    s.fake.text("BUSY")
    s.fake.json(p(30))
    s.fake.json(IN_FINAL)
    val r = within(t)

    check(r.response == "IN", "OK p BUSY p IN: chiude IN, non il BUSY")
    check(
        partials.size == 2 && partials[0]?.compareTo(BigDecimal.TEN) == 0 && partials[1]?.compareTo(BigDecimal("30")) == 0,
        "parziali: solo i frame p, in ordine"
    )
    check(s.orphans.any { it.isBusy }, "il BUSY dopo l'OK va all'evento dei frame orfani")
    check(!s.client.isCollecting, "IsCollecting falso a incasso chiuso")
}

private suspend fun cmdErrorAfterOk() = session { s ->
    val t = s.async { s.client.collectCash(EIGHTY) }
    s.fake.expect("IN008000")
    s.fake.json(OK)
    s.fake.text("CMD ERROR")
    s.fake.json(IN_FINAL)
    val r = within(t)

    check(r.response == "IN", "OK CMD ERROR IN: chiude IN")
    check(s.orphans.any { it.raw == "CMD ERROR" }, "CMD ERROR dopo l'OK e' un frame orfano")
}

private suspend fun busyBeforeOk() = session { s ->
    val t = s.async { s.client.collectCash(EIGHTY) }
    s.fake.expect("IN008000")
    s.fake.text("BUSY")

    val ex = catching(t)
    check(ex is PagAmicoBusyException, "BUSY prima dell'OK: PagAmicoBusyException")
    check(!s.client.isCollecting, "dopo il rifiuto nessun incasso aperto")
}

private suspend fun cmdErrorBeforeOk() = session { s ->
    val t = s.async { s.client.collectCash(EIGHTY) }
    s.fake.expect("IN008000")
    s.fake.text("CMD ERROR")

    val ex = catching(t)
    check(ex is PagAmicoRejectedException && ex !is PagAmicoBusyException, "CMD ERROR prima dell'OK: rifiutato, non occupato")
}

private suspend fun erBusyBeforeOk() = session { s ->
    val t = s.async { s.client.collectCash(EIGHTY) }
    s.fake.expect("IN008000")
    s.fake.json(ER_BUSY)

    check(catching(t) is PagAmicoBusyException, "ER E100/99 prima dell'OK: occupato")
}

// ---------------------------------------------------------------- D1 e CM durante l'incasso

private suspend fun commitDuringCollection() = session { s ->
    val collect = s.async { s.client.collectCash(EIGHTY) }
    s.fake.expect("IN008000")
    s.fake.json(OK)
    waitUntil { s.client.isCollecting }

    val commit = s.async { s.client.commit() }
    check(s.fake.tryExpect("CM"), "CM durante l'incasso parte senza attendere la fine dell'IN")
    s.fake.json(CM_ACCEPTED)
    s.fake.json(CM_FINAL)
    val r = within(commit)

    check(r.errorCode != "OK", "CM/OK poi CM finale: CommitAsync restituisce l'esito, non l'accettazione")
    check(r.collectedAmount?.compareTo(BigDecimal("470")) == 0, "trattenuto letto da collectedAmount: 470,00")
    val c = within(collect)
    check(c.response == "CM" && c.errorCode != "OK", "l'incasso si chiude sul CM finale")
}

private suspend fun commitRefused() = session { s ->
    val collect = s.async { s.client.collectCash(EIGHTY) }
    s.fake.expect("IN008000")
    s.fake.json(OK)
    waitUntil { s.client.isCollecting }

    val commit = s.async { s.client.commit() }
    s.fake.expect("CM")
    s.fake.json(CM_REFUSED)
    val r = within(commit)

    check(r.errorCode == "NO", "CM/NO: commit rifiutato")
    check(s.client.isCollecting && !collect.isCompleted, "dopo CM/NO l'incasso resta aperto")

    val cancel = s.async { s.client.cancelOperation() }
    check(s.fake.tryExpect("AN"), "dopo CM/NO si puo' chiudere con AN")
    s.fake.json(AN_FINAL)
    val a = within(cancel)
    val c = within(collect)
    check(a.response == "AN" && c.response == "AN", "l'esito dell'AN arriva sia all'annullo sia all'incasso")
}

private suspend fun commitBeforeOk() = session { s ->
    val collect = s.async { s.client.collectCash(EIGHTY) }
    s.fake.expect("IN008000")

    val commit = s.async { s.client.commit() }
    check(s.fake.silent(400), "CM prima dell'OK aspetta l'accettazione")
    s.fake.json(OK)
    check(s.fake.tryExpect("CM"), "dopo l'OK parte il CM")
    s.fake.json(CM_ACCEPTED)
    s.fake.json(CM_FINAL)
    within(commit)
    within(collect)
}

private suspend fun singleClosing() = session { s ->
    val collect = s.async { s.client.collectCash(EIGHTY) }
    s.fake.expect("IN008000")
    s.fake.json(OK)
    waitUntil { s.client.isCollecting }

    val first = s.async { s.client.commit() }
    s.fake.expect("CM")
    val second = catching(s.async { s.client.commit() })
    val third = catching(s.async { s.client.cancelOperation() })
    check(
        second is PagAmicoException && second !is PagAmicoCollectionOpenException && third is PagAmicoException,
        "seconda chiusura rifiutata subito"
    )
    check(s.fake.silent(300), "alla macchina arriva un solo comando di chiusura")

    s.fake.json(CM_ACCEPTED)
    s.fake.json(CM_FINAL)
    within(first)
    within(collect)
}

private suspend fun cancelByCaller() = session { s ->
    val cancelled = CompletableDeferred<PagAmicoCollectionCancelledException>()
    val collect = s.async {
        try {
            s.client.collectCash(EIGHTY)
        } catch (e: PagAmicoCollectionCancelledException) {
            cancelled.complete(e)
            throw e
        }
    }
    s.fake.expect("IN008000")
    s.fake.json(OK)
    waitUntil { s.client.isCollecting }

    collect.cancel()
    s.fake.expect("AN")
    check(s.fake.silent(300), "annullo del chiamante: un solo AN")
    s.fake.json(AN_FINAL)
    // divergenza nota: il chiamante ha ricevuto la cancellazione, l'esito arriva a onOrphanFrame
    waitUntil { s.orphans.any { it.response == "AN" } }
    check(
        s.orphans.any { it.response == "AN" && it.json?.changeReturn?.compareTo(BigDecimal("30")) == 0 },
        "annullo del chiamante: esito AN consegnato"
    )
    val outcome = withTimeout(3_000) { cancelled.await().outcome.await() }
    check(
        outcome.response == "AN" && outcome.changeReturn?.compareTo(BigDecimal("30")) == 0,
        "annullo del chiamante: l'esito AN viaggia anche con l'eccezione di cancellazione"
    )
}

// ---------------------------------------------------------------- invii bloccati, orfani, caduta

private suspend fun blockedSends() = session { s ->
    val collect = s.async { s.client.collectCash(EIGHTY) }
    s.fake.expect("IN008000")
    s.fake.json(OK)
    waitUntil { s.client.isCollecting }
    check(s.client.isCollecting, "IsCollecting vero durante l'incasso")

    val st = catching(s.async { s.client.status() })
    val dt = catching(s.async { s.client.showText("ciao") })
    val raw = catching(s.async { s.client.sendRaw("ST") })
    check(st is PagAmicoCollectionOpenException, "ST a incasso aperto: eccezione immediata")
    check(dt is PagAmicoCollectionOpenException, "display a incasso aperto: eccezione immediata")
    check(raw is PagAmicoCollectionOpenException, "invio grezzo a incasso aperto: eccezione immediata")
    check(s.fake.silent(300), "a incasso aperto nulla e' trasmesso alla macchina")

    s.fake.json(IN_FINAL)
    within(collect)
}

private suspend fun orphanWithoutWaiter() = session { s ->
    s.fake.json(AN_FINAL)
    waitUntil { s.orphans.isNotEmpty() }
    check(s.orphans.any { it.response == "AN" }, "frame senza nessuna attesa: evento dei frame orfani")
}

private suspend fun connectionLostWhileCollecting() = session { s ->
    val collect = s.async { s.client.collectCash(EIGHTY) }
    s.fake.expect("IN008000")
    s.fake.json(OK)
    s.fake.json(p(20))
    waitUntil { s.client.isCollecting }
    s.fake.drop()

    val ex = catching(collect)
    check(
        ex is PagAmicoConnectionLostException && ex.mayBeCollecting,
        "caduta durante l'incasso: connessione persa, forse sta incassando"
    )
}

private suspend fun commitOutsideCollection() = session { s ->
    val commit = s.async { s.client.commit() }
    s.fake.expect("CM")
    s.fake.json(CM_ACCEPTED)
    s.fake.json(CM_FINAL)
    val r = within(commit)
    check(
        r.errorCode != "OK" && r.collectedAmount?.compareTo(BigDecimal("470")) == 0,
        "CM fuori incasso: esito dal CM finale, non 0,00"
    )
}

private suspend fun cancelDuringCommit() = session { s ->
    val collect = s.async { s.client.collectCash(EIGHTY) }
    s.fake.expect("IN008000")
    s.fake.json(OK)
    waitUntil { s.client.isCollecting }

    val commit = s.async { s.client.commit() }
    s.fake.expect("CM")
    collect.cancel()
    check(s.fake.silent(300), "annullo del chiamante durante un CM: l'AN aspetta l'esito del CM")
    s.fake.json(CM_REFUSED)
    within(commit)
    check(s.fake.tryExpect("AN"), "dopo il CM/NO parte l'AN chiesto dal chiamante")
    s.fake.json(AN_FINAL)
    waitUntil { s.orphans.any { it.response == "AN" } }
    check(s.orphans.any { it.response == "AN" }, "incasso chiuso dall'AN")
}

// ---------------------------------------------------------------- casi che toccano i soldi

private suspend fun cancelBeforeOk() = session { s ->
    val collect = s.async { s.client.collectCash(EIGHTY) }
    s.fake.expect("IN008000")

    collect.cancel()
    check(s.fake.silent(400), "annullo del chiamante prima dell'OK: l'AN aspetta l'accettazione")
    s.fake.json(OK)
    check(s.fake.tryExpect("AN"), "dopo l'OK parte l'AN chiesto prima")
    s.fake.json(AN_FINAL)
    // divergenza nota: il chiamante ha ricevuto la cancellazione, l'esito arriva a onOrphanFrame
    waitUntil { s.orphans.any { it.response == "AN" } }
    check(s.orphans.any { it.response == "AN" }, "annullo prima dell'OK: esito AN consegnato")
}

private suspend fun otherCollections() {
    session { s ->
        val t = s.async { s.client.collectPos(BigDecimal("3.20")) }
        s.fake.expect("PO000320")
        s.fake.json(OK)
        s.fake.text("BUSY")
        s.fake.json(PO_FINAL)
        val r = within(t)
        check(r.response == "PO" && r.collectedAmount?.compareTo(BigDecimal("3.2")) == 0, "PO: chiude sull'esito PO, non sul BUSY")
    }
    session { s ->
        val t = s.async { s.client.collectAuto(BigDecimal("1.00")) }
        s.fake.expect("IM000100")
        s.fake.json(OK)
        s.fake.json(p(1))
        s.fake.json(IM_FINAL)
        val r = within(t)
        check(r.response == "IM" && r.errorCode == "CONT", "IM: chiude sull'esito IM (CONT)")
    }
    session { s ->
        val t = s.async { s.client.collectCashWithTimeout(BigDecimal("1.00"), 20) }
        s.fake.expect("I2020000100")
        s.fake.json(OK)
        s.fake.json(IN_FINAL)
        check(within(t).response == "IN", "I2: chiude sull'esito IN")
    }
}

private suspend fun forcedCloseFromPanel() = session { s ->
    val collect = s.async { s.client.collectCash(EIGHTY) }
    s.fake.expect("IN008000")
    s.fake.json(OK)
    s.fake.json(p(20))
    s.fake.json(AN_HALTED)
    val r = within(collect)

    check(r.response == "AN" && r.isHalted, "chiusura forzata dal pannello: esito AN con halted TRUE")
    check(s.fake.silent(200), "chiusura forzata: il client non ha mandato nessun AN")
}

private suspend fun dropWhileCommitWaitsForOk() = session { s ->
    val collect = s.async { s.client.collectCash(EIGHTY) }
    s.fake.expect("IN008000")
    val commit = s.async { s.client.commit() }
    delay(200.milliseconds)
    s.fake.drop()

    val commitEx = catching(commit)
    val collectEx = catching(collect)
    check(commitEx is PagAmicoException, "caduta mentre il CM aspetta l'OK: il commit fallisce")
    check(
        collectEx is PagAmicoConnectionLostException && !collectEx.mayBeCollecting,
        "caduta prima dell'OK: connessione persa, l'incasso non era accettato"
    )
    check(s.fake.silent(200), "caduta prima dell'OK: il CM non e' mai partito")
}

private suspend fun disconnectWhileCollecting() = session { s ->
    val collect = s.async { s.client.collectCash(EIGHTY) }
    s.fake.expect("IN008000")
    s.fake.json(OK)
    delay(200.milliseconds)
    s.client.disconnect()

    val ex = catching(collect)
    check(
        ex is PagAmicoConnectionLostException && ex.mayBeCollecting,
        "Disconnect() a incasso accettato: connessione persa, forse sta incassando"
    )
    waitUntil { !s.client.isCollecting }
    check(!s.client.isCollecting, "dopo Disconnect() nessun incasso aperto")
}

private suspend fun collectionTimeout() = session { s ->
    val collect = s.async { s.client.collectCash(EIGHTY, timeoutMs = 600) }
    s.fake.expect("IN008000")
    s.fake.json(OK)

    val ex = catching(collect)
    check(ex is PagAmicoTimeoutException, "timeout dell'incasso: PagAmicoTimeoutException")
    check(!s.client.isCollecting, "dopo il timeout lo stato dell'incasso si libera (la macchina no: D2)")
    s.fake.json(p(30))
    waitUntil { s.orphans.any { it.isPartial } }
    check(s.orphans.any { it.isPartial }, "dopo il timeout i frame dell'incasso vanno ai frame orfani")
}

private suspend fun dropAfterCallerCancel() = session { s ->
    val collect = s.async { s.client.collectCash(EIGHTY) }
    s.fake.expect("IN008000")
    s.fake.json(OK)
    waitUntil { s.client.isCollecting }
    collect.cancel()
    s.fake.expect("AN")
    s.fake.drop()

    catching(collect)
    waitUntil { !s.client.isCollecting }
    check(!s.client.isCollecting, "caduta dopo l'annullo del chiamante: nessun incasso resta aperto")
}

// ---------------------------------------------------------------- comandi semplici e invio

private suspend fun lateCommitBeforeStatus() = session { s ->
    val t = s.async { s.client.status() }
    s.fake.expect("ST")
    s.fake.json(CM_FINAL)
    s.fake.json(ST_FINAL)
    val r = within(t)

    check(r.response == "ST", "CM in ritardo prima della risposta a ST: ST riceve la sua")
    check(s.orphans.any { it.response == "CM" }, "il CM in ritardo va ai frame orfani")
}

private suspend fun okClosesSimpleCommand() = session { s ->
    val t = s.async { s.client.updateCashFloat(CashFloatTarget.BOTH) }
    s.fake.expect("AF9")
    s.fake.json(OK)
    check(within(t).response == "OK", "comando semplice chiuso da OK")
}

private suspend fun textErrorClosesSimpleCommand() = session { s ->
    val t = s.async { s.client.status() }
    s.fake.expect("ST")
    s.fake.text("CMD ERROR")
    val ex = catching(t)
    check(ex is PagAmicoException && ex !is PagAmicoTimeoutException, "comando semplice chiuso da un testo di errore")
}

private suspend fun lastJsonAcceptsAnything() = session { s ->
    val t = s.async { s.client.lastJson() }
    s.fake.expect("LO")
    s.fake.json(CM_FINAL)
    check(within(t).response == "CM", "LO accetta l'ultimo JSON, qualunque sia")
}

private suspend fun defaultPauseBetweenCommands() = session(defaults = true) { s ->
    // configurazione di default: nessun terminatore, 80 ms fra un invio e l'altro
    s.client.showText("A")
    s.client.closeText()
    val segments = s.fake.segments(2)

    check(
        segments.size == 2 && segments[1].first - segments[0].first >= 70,
        "pausa di default: due invii consecutivi arrivano ad almeno 70 ms"
    )
    check(
        segments.size == 2 && String(segments[1].second, Charsets.UTF_8) == "DS\r",
        "default: ogni comando arriva nel suo segmento, chiuso da CR"
    )
}

private suspend fun keepAliveConfigured() {
    val fake = FakePagAmico()
    val client = PagAmicoClient("127.0.0.1", fake.port).apply {
        keepAliveTimeSec = 7
        keepAliveIntervalSec = 3
        keepAliveRetryCount = 4
    }
    val trace = Collections.synchronizedList(mutableListOf<String>())
    client.onTrace = { trace += it }
    try {
        client.connect()
        fake.waitConnected()
        val lines = synchronized(trace) { trace.toList() }
        check(
            if (keepAliveTunable()) lines.any { it.contains("prima sonda dopo 7 s, poi ogni 3 s") && it.contains("4 sonde") }
            else lines.any { it.contains("keepalive TCP con i valori di sistema") },
            "keepalive TCP regolato alla connessione (7 s, ogni 3 s, 4 sonde), o avviso se la JVM non lo permette"
        )
    } finally {
        client.disconnect()
        fake.close()
    }
}

/** Vero se la JVM permette di regolare il keepalive (su Windows sì con JDK 17.0.14 e 17.0.20, no con 17.0.8; mai su Android). */
private fun keepAliveTunable(): Boolean = try {
    val idle = Class.forName("jdk.net.ExtendedSocketOptions").getField("TCP_KEEPIDLE").get(null)
    Socket().use { idle in it.supportedOptions() }
} catch (e: ReflectiveOperationException) {
    false
}

private suspend fun terminatorAppended() = session(defaults = true, terminator = "\r\n") { s ->
    s.client.clearDisplay()
    check(String(s.fake.takeBytes(4), Charsets.US_ASCII) == "CL\r\n", "terminatore CR+LF accodato al comando")
}

/**
 * La notifica del comando esce prima dei byte: nel log la riga del comando precede sempre quella
 * della risposta. Prima della correzione del 16/09 la notifica arrivava dopo la scrittura, e su
 * 127.0.0.1 la risposta veniva registrata per prima.
 */
private suspend fun commandNotifiedBeforeResponse() = session { s ->
    val order = Collections.synchronizedList(mutableListOf<String>())
    s.client.onCommandSent = { order += "TX $it" }
    val collector = s.launch { s.client.frames.collect { order += "RX ${it.raw}" } }
    delay(100)     // il flusso dei frame non ha replay: si aspetta che il collettore sia attivo

    val status = s.async { s.client.status() }
    s.fake.expect("ST")
    s.fake.json(ST_FINAL)
    within(status)
    collector.cancel()

    val seen = order.toList()
    check(
        seen.size >= 2 && seen[0] == "TX ST" && seen[1].startsWith("RX "),
        "il comando e' notificato prima della risposta: nel log la riga TX precede la RX"
    )
}

private suspend fun imagePackets() = session(defaults = true) { s ->
    val image = byteArrayOf(1, 2, 3)
    val ff = 0xFF.toByte()
    val fe = 0xFE.toByte()
    val bar = '|'.code.toByte()

    s.client.sendLogo(image)
    check(
        s.fake.takeBytes(11).contentEquals(byteArrayOf('S'.code.toByte(), 'F'.code.toByte(), ff, ff, 1, 2, 3, fe, fe, bar, bar)),
        "immagine SF, incapsulamento del manuale: SF FF FF png FE FE ||"
    )

    s.client.imageLayout = ImagePacketLayout.PYTHON_SAMPLE
    s.client.sendTemporaryImage(image)
    check(
        s.fake.takeBytes(13).contentEquals(
            byteArrayOf('S'.code.toByte(), 'I'.code.toByte(), ff, ff, bar, bar, 1, 2, 3, bar, bar, fe, fe)
        ),
        "immagine SI, incapsulamento dell'esempio Python: SI FF FF || png || FE FE"
    )
}

// ---------------------------------------------------------------- infrastruttura

private suspend fun <T> within(d: Deferred<T>, ms: Long = 5_000): T = withTimeout(ms.milliseconds) { d.await() }

private suspend fun catching(d: Deferred<*>, ms: Long = 5_000): Throwable? =
    try {
        withTimeout(ms.milliseconds) { d.await() }
        null
    } catch (e: Throwable) {
        e
    }

private suspend fun waitUntil(ms: Long = 3_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (!condition() && System.currentTimeMillis() < end) delay(10.milliseconds)
}

/**
 * Apre un finto pagAmico e un client connesso; li chiude alla fine del caso.
 * [defaults]: configurazione di default della libreria (terminatore CR, pausa di 80 ms).
 */
private suspend fun session(defaults: Boolean = false, terminator: String? = null, body: suspend (Session) -> Unit) {
    val fake = FakePagAmico()
    // il terminatore serve solo al finto pagAmico per separare i comandi
    val client = if (defaults) {
        PagAmicoClient("127.0.0.1", fake.port, commandTerminator = terminator ?: "\r")
    } else {
        PagAmicoClient("127.0.0.1", fake.port, commandTerminator = "\r", minimumCommandIntervalMs = 0)
    }
    val s = Session(fake, client)
    try {
        client.connect()
        fake.waitConnected()
        body(s)
    } catch (e: Throwable) {
        fail("caso interrotto: ${e::class.simpleName}: ${e.message}")
    } finally {
        s.cancel()
        client.disconnect()
        fake.close()
    }
}

private class Session(val fake: FakePagAmico, val client: PagAmicoClient) :
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {
    val orphans: MutableList<PagAmicoFrame> = Collections.synchronizedList(mutableListOf())

    init {
        client.onOrphanFrame = { orphans += it }
    }
}

/** Finto pagAmico: un solo client, comandi separati dal CR, risposte scritte dal test. */
private class FakePagAmico : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val commands = LinkedBlockingQueue<String>()
    private val connected = CountDownLatch(1)
    private val segmentList = mutableListOf<Pair<Long, ByteArray>>()
    private val raw = ByteArrayOutputStream()
    @Volatile private var peer: Socket? = null

    val port: Int = server.localPort

    init {
        thread(isDaemon = true, name = "finto-pagamico") {
            runCatching {
                val s = server.accept()
                peer = s
                connected.countDown()
                val input = s.getInputStream()
                val buffer = ByteArray(4096)
                val pending = StringBuilder()
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    synchronized(segmentList) {
                        val data = buffer.copyOf(n)
                        segmentList += System.currentTimeMillis() to data
                        raw.write(data)
                    }
                    pending.append(String(buffer, 0, n, Charsets.UTF_8))
                    while (true) {
                        val cr = pending.indexOf("\r")
                        if (cr < 0) break
                        commands.put(pending.substring(0, cr))
                        pending.delete(0, cr + 1)
                    }
                }
            }
        }
    }

    fun waitConnected() {
        if (!connected.await(3, TimeUnit.SECONDS)) throw IllegalStateException("il client non si e' connesso al finto pagAmico")
    }

    /** Attende il prossimo comando e verifica che sia quello atteso. */
    fun expect(command: String) {
        val received = commands.poll(3, TimeUnit.SECONDS)
            ?: throw IllegalStateException("il finto pagAmico non ha ricevuto '$command'")
        if (received != command) throw IllegalStateException("il finto pagAmico attendeva '$command', ha ricevuto '$received'")
    }

    fun tryExpect(command: String): Boolean = commands.poll(3, TimeUnit.SECONDS) == command

    /** I primi [count] segmenti TCP ricevuti, con l'istante d'arrivo in millisecondi. */
    fun segments(count: Int, ms: Long = 3_000): List<Pair<Long, ByteArray>> {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            synchronized(segmentList) { if (segmentList.size >= count) return segmentList.take(count) }
            Thread.sleep(10)
        }
        return synchronized(segmentList) { segmentList.toList() }
    }

    /** Attende [count] byte grezzi, li restituisce e li toglie dal buffer. */
    fun takeBytes(count: Int, ms: Long = 3_000): ByteArray {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            synchronized(segmentList) {
                val all = raw.toByteArray()
                if (all.size >= count) {
                    raw.reset()
                    raw.write(all, count, all.size - count)
                    return all.copyOf(count)
                }
            }
            Thread.sleep(10)
        }
        return synchronized(segmentList) { raw.toByteArray() }
    }

    /** Vero se per [ms] millisecondi non arriva nessun comando. */
    fun silent(ms: Long): Boolean = commands.poll(ms, TimeUnit.MILLISECONDS) == null

    fun json(json: String) = write(json)

    /** Testo nudo, senza terminatore, come lo manda la macchina: il client lo chiude dopo 150 ms di silenzio. */
    fun text(text: String) {
        write(text)
        Thread.sleep(300)
    }

    fun drop() {
        runCatching { peer?.close() }
    }

    private fun write(raw: String) {
        val out = peer!!.getOutputStream()
        out.write(raw.toByteArray(Charsets.UTF_8))
        out.flush()
        Thread.sleep(20)
    }

    override fun close() {
        runCatching { peer?.close() }
        runCatching { server.close() }
    }
}
