package it.payprint.pagamico.client

import it.payprint.pagamico.commands.CashFloatTarget
import it.payprint.pagamico.commands.DenominationToggle
import it.payprint.pagamico.commands.MovementCause
import it.payprint.pagamico.commands.PagAmicoCommands
import it.payprint.pagamico.commands.StockThreshold
import it.payprint.pagamico.display.DisplayPosition
import it.payprint.pagamico.display.FontColor
import it.payprint.pagamico.display.FontStyle
import it.payprint.pagamico.display.KeyboardLayout
import it.payprint.pagamico.display.KeyboardMode
import it.payprint.pagamico.display.PagAmicoDisplay
import it.payprint.pagamico.exceptions.PagAmicoBusyException
import it.payprint.pagamico.exceptions.PagAmicoCollectionCancelledException
import it.payprint.pagamico.exceptions.PagAmicoCollectionOpenException
import it.payprint.pagamico.exceptions.PagAmicoConnectionLostException
import it.payprint.pagamico.exceptions.PagAmicoException
import it.payprint.pagamico.exceptions.PagAmicoRejectedException
import it.payprint.pagamico.exceptions.PagAmicoTimeoutException
import it.payprint.pagamico.print.PagAmicoPrint
import it.payprint.pagamico.print.PagAmicoPrintJob
import it.payprint.pagamico.print.PrinterStatus
import it.payprint.pagamico.response.PagAmicoMovement
import it.payprint.pagamico.response.PagAmicoResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.time.LocalDateTime

/**
 * Client TCP-IP per cassa rendiresto PayPrint pagAmico (protocollo rev. 2.33, FW 8.72).
 *
 * Il pagAmico e' il server: aprire UNA connessione e tenerla aperta. Il protocollo e' asincrono:
 * ad un comando possono seguire piu' messaggi (`{"response":"OK"}` di accettazione,
 * `{"response":"p"}` parziali, quindi il messaggio finale).
 *
 * Uso tipico:
 * ```
 * val client = PagAmicoClient("192.168.1.29")
 * client.connect()
 * val esito = client.collectCash(BigDecimal("10.50")) { parziale ->
 *     println("incassato finora ${parziale.collectedAmount}")
 * }
 * ```
 */
class PagAmicoClient(
    val host: String,
    val port: Int = DEFAULT_PORT,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Codifica di comandi e risposte: il FW 8.72 gestisce accentate ed euro. */
    val charset: Charset = Charsets.UTF_8,
    /**
     * Terminatore accodato ad ogni comando. Il manuale 2.33 non lo documenta, ma PayPrint
     * (risposta dell'11 settembre 2026, domande 1.1 e 1.2) indica CR o CR+LF per la macchina.
     * Il simulatore del Dev Kit lo accetta: collaudo completo 64/64 con CR (14 settembre 2026).
     * Vuoto = nessun terminatore, come Hercules.
     */
    val commandTerminator: String = "\r",
    /** Intervallo di polling: determina la latenza di chiusura dei frame testuali. */
    private val pollIntervalMs: Int = 50,
    /**
     * Distanza minima fra due invii consecutivi.
     *
     * Nata perche' una versione precedente del simulatore leggeva il buffer del socket come UN
     * solo comando: due comandi senza terminatore nello stesso segmento TCP, il secondo si perdeva.
     * Il simulatore attuale regge le raffiche con e senza terminatore (collaudo 64/64 a 0 ms e
     * prova con piu' comandi in un segmento, 14 settembre 2026), e PayPrint dice che con CR la
     * pausa non serve. Il default resta 80 ms come rete di sicurezza finche' non e' verificato
     * sulla macchina reale.
     */
    var minimumCommandIntervalMs: Long = 80
) {
    companion object {
        /** Porta di default; il manuale consiglia di cambiarla (es. 43775) perche' la 9100 e' molto usata. */
        const val DEFAULT_PORT = 9100

        private val BUTTON_REGEX = Regex("^BT([123])$")

        /** Timeout di default per i comandi brevi. */
        val DEFAULT_TIMEOUT_MS = 15_000L

        /** Timeout di default per incasso / erogazione (operazioni con intervento utente). */
        val TRANSACTION_TIMEOUT_MS = 5 * 60_000L
    }

    var imageLayout: ImagePacketLayout = ImagePacketLayout.DOCUMENTED

    /**
     * Secondi di silenzio dopo i quali parte la prima sonda keepalive TCP. Durante un incasso non si
     * puo' mandare [ST]: il keepalive e' l'unico modo di accorgersi che la macchina non e' piu'
     * raggiungibile. Il default di sistema e' 2 ore. Si applica alla connessione successiva.
     */
    var keepAliveTimeSec: Int = 10

    /** Secondi fra due sonde keepalive senza risposta. Si applica alla connessione successiva. */
    var keepAliveIntervalSec: Int = 2

    /** Sonde senza risposta prima di dichiarare caduta la connessione. Si applica alla connessione successiva. */
    var keepAliveRetryCount: Int = 5

    private val parser = PagAmicoFrameParser()
    private val writeMutex = Mutex()
    private val commandMutex = Mutex()
    private val _frames = MutableSharedFlow<PagAmicoFrame>(replay = 0, extraBufferCapacity = 256)

    /** Tutti i messaggi ricevuti dal pagAmico. */
    val frames: SharedFlow<PagAmicoFrame> = _frames.asSharedFlow()

    private val gate = Any()
    private val waiters = mutableListOf<Waiter>()
    private var collection: Collection? = null // incasso aperto, protetto da gate

    private var lastSentAt: Long = 0
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var scope: CoroutineScope? = null
    private var receiveJob: Job? = null

    /** Invocata quando la connessione cade; null se la chiusura e' stata richiesta. */
    var onDisconnected: ((Throwable?) -> Unit)? = null

    /** Invocata per ogni comando trasmesso (utile per log e pannelli di traffico). */
    var onCommandSent: ((String) -> Unit)? = null

    /**
     * Messaggio che nessuna attesa riconosce come proprio: arrivato dopo un timeout o una caduta, la
     * risposta a qualcos'altro durante un incasso (un BUSY), l'[AN] di una chiusura forzata dal pannello,
     * l'esito di un incasso il cui chiamante e' stato cancellato. **Puo' portare importi**: chi integra lo
     * deve ascoltare e salvare. Arriva dal thread di ricezione.
     */
    var onOrphanFrame: ((PagAmicoFrame) -> Unit)? = null

    /**
     * Diagnostica interna della libreria: connessione, pause imposte fra un invio e l'altro,
     * separazione dei messaggi, attese soddisfatte o scadute. Serve per capire *perche'* un
     * comando non ha avuto risposta, cosa che il solo traffico TX/RX non mostra.
     */
    var onTrace: ((String) -> Unit)? = null

    private fun trace(message: String) = onTrace?.invoke(message)

    private fun shorten(text: String, max: Int = 90) =
        if (text.length <= max) text else text.take(max) + "..."

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    /**
     * Vero dall'invio di un incasso ([IN], [PO], [IM], [I2]) al suo esito. Finche' e' vero la macchina
     * accetta solo [AN] e [CM]: ogni altro invio lancia [PagAmicoCollectionOpenException] senza trasmettere nulla.
     */
    val isCollecting: Boolean get() = synchronized(gate) { collection != null }

    // ------------------------------------------------------------------ connessione

    suspend fun connect(connectTimeoutMs: Int = 5_000) = withContext(ioDispatcher) {
        disconnect()

        val s = Socket()
        s.tcpNoDelay = true
        configureKeepAlive(s)
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        s.soTimeout = pollIntervalMs

        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
        parser.clear()

        val newScope = CoroutineScope(SupervisorJob() + ioDispatcher)
        scope = newScope
        receiveJob = newScope.launch { receiveLoop() }

        trace(
            "connesso a $host:$port (pausa minima fra invii $minimumCommandIntervalMs ms, " +
                "terminatore ${if (commandTerminator.isEmpty()) "nessuno" else "presente"})"
        )
    }

    /**
     * Accende il keepalive e ne regola i tempi con jdk.net.ExtendedSocketOptions, letto per riflessione:
     * su Android la classe non esiste e restano i valori di sistema.
     */
    private fun configureKeepAlive(s: Socket) {
        s.keepAlive = true
        val time = keepAliveTimeSec.coerceAtLeast(1)
        val interval = keepAliveIntervalSec.coerceAtLeast(1)
        val count = keepAliveRetryCount.coerceAtLeast(1)
        try {
            val options = Class.forName("jdk.net.ExtendedSocketOptions")
            @Suppress("UNCHECKED_CAST")
            fun option(name: String) = options.getField(name).get(null) as java.net.SocketOption<Int>
            val idle = option("TCP_KEEPIDLE")
            val intervalOption = option("TCP_KEEPINTERVAL")
            val countOption = option("TCP_KEEPCOUNT")
            val supported = s.supportedOptions()
            if (idle !in supported || intervalOption !in supported || countOption !in supported) {
                trace("keepalive TCP con i valori di sistema: regolazione non supportata su questa JVM")
                return
            }
            s.setOption(idle, time)
            s.setOption(intervalOption, interval)
            s.setOption(countOption, count)
            trace("keepalive TCP: prima sonda dopo $time s, poi ogni $interval s, caduta dopo $count sonde senza risposta")
        } catch (e: ReflectiveOperationException) {
            trace("keepalive TCP con i valori di sistema: regolazione non disponibile (${e.javaClass.simpleName})")
        } catch (e: java.io.IOException) {
            trace("keepalive TCP con i valori di sistema: regolazione rifiutata (${e.message})")
        } catch (e: UnsupportedOperationException) {
            trace("keepalive TCP con i valori di sistema: regolazione non supportata (${e.message})")
        }
    }

    fun disconnect() {
        // prima le attese, poi lo scope: chi aspetta deve ricevere "connessione persa", non una cancellazione
        failWaiters("Connessione chiusa")
        receiveJob?.cancel()
        scope?.cancel()
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
        scope = null
        receiveJob = null
    }

    /** Fa fallire tutte le attese in corso: la connessione non c'e' piu'. */
    private fun failWaiters(reason: String) {
        val (pending, mayBeCollecting) = synchronized(gate) {
            val list = waiters.toList()
            waiters.clear()
            list to (collection?.accepted == true)
        }
        val message =
            if (mayBeCollecting) "$reason: l'incasso era aperto, la macchina potrebbe stare ancora incassando" else reason
        pending.forEach { it.result.completeExceptionally(PagAmicoConnectionLostException(message, mayBeCollecting)) }
    }

    // ------------------------------------------------------------------ IO di basso livello

    /**
     * Invia un comando testuale senza attendere risposta. A incasso aperto lancia
     * [PagAmicoCollectionOpenException]: per chiudere un incasso usare [cancelOperation] o [commit].
     */
    suspend fun sendRaw(command: String) = sendCommand(command, duringCollection = false)

    /** Invia byte grezzi (usato per l'invio delle immagini SF / SI). A incasso aperto lancia. */
    suspend fun sendRaw(payload: ByteArray) = write(payload, "(invio binario)", duringCollection = false)

    private suspend fun sendCommand(command: String, duringCollection: Boolean) {
        write((command + commandTerminator).toByteArray(charset), command, duringCollection)
        onCommandSent?.invoke(command)
    }

    // duringCollection: vero solo per l'[IN] stesso e per l'[AN] / [CM] che lo chiudono
    private suspend fun write(payload: ByteArray, what: String, duringCollection: Boolean) = withContext(ioDispatcher) {
        val out = output ?: throw PagAmicoException("Client non connesso: chiamare connect()")
        if (!duringCollection) throwIfCollecting(what)
        writeMutex.withLock {
            // ricontrollo sotto il lock di scrittura: l'incasso si apre prima che l'[IN] sia scritto,
            // quindi nessun invio laterale puo' finire dopo l'[IN]
            if (!duringCollection) throwIfCollecting(what)

            val elapsed = System.currentTimeMillis() - lastSentAt
            if (elapsed < minimumCommandIntervalMs) {
                val wait = minimumCommandIntervalMs - elapsed
                trace("attesa di $wait ms prima dell'invio: il pagAmico ignora i comandi troppo ravvicinati")
                delay(wait)
            }

            out.write(payload)
            out.flush()
            lastSentAt = System.currentTimeMillis()
        }
    }

    private fun throwIfCollecting(command: String) {
        if (!isCollecting) return
        trace("'${shorten(command)}' NON inviato: incasso aperto, la macchina accetta solo AN e CM")
        throw PagAmicoCollectionOpenException(command)
    }

    /**
     * Invia un comando e attende il messaggio finale identificato da [isFinal].
     * I messaggi intermedi (accettazione, parziali) vengono passati a [onFrame].
     */
    suspend fun request(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onFrame: ((PagAmicoFrame) -> Unit)? = null,
        isFinal: (PagAmicoFrame) -> Boolean
    ): PagAmicoFrame = requestCore(command, timeoutMs, onFrame, verdicts(isFinal))

    private suspend fun requestCore(
        command: String,
        timeoutMs: Long,
        onProgress: ((PagAmicoFrame) -> Unit)?,
        classify: (PagAmicoFrame) -> Verdict
    ): PagAmicoFrame {
        // prima del lock: un comando accodato dietro un incasso aperto aspetterebbe per minuti
        // e partirebbe comunque durante l'incasso, provocando un BUSY
        throwIfCollecting(command)
        return commandMutex.withLock {
            // l'attesa si registra PRIMA dell'invio, altrimenti si perdono le risposte immediate
            val waiter = Waiter(classify, onProgress)
            synchronized(gate) { waiters += waiter }
            try {
                sendCommand(command, duringCollection = false)
                awaitWaiter(waiter, command, timeoutMs)
            } finally {
                synchronized(gate) { waiters.remove(waiter) }
            }
        }
    }

    /** Attende un messaggio senza inviare nulla (es. esito di una dialog gia' aperta sul display). */
    suspend fun waitFor(timeoutMs: Long = DEFAULT_TIMEOUT_MS, isFinal: (PagAmicoFrame) -> Boolean): PagAmicoFrame {
        val waiter = Waiter(verdicts(isFinal), null)
        synchronized(gate) { waiters += waiter }
        try {
            return awaitWaiter(waiter, "(attesa senza invio)", timeoutMs)
        } finally {
            synchronized(gate) { waiters.remove(waiter) }
        }
    }

    private suspend fun awaitWaiter(waiter: Waiter, command: String, timeoutMs: Long): PagAmicoFrame {
        trace("in attesa dell'esito di '$command' (timeout ${timeoutMs / 1000.0}s)")
        val frame = try {
            withTimeout(timeoutMs) { waiter.result.await() }
        } catch (e: TimeoutCancellationException) {
            // se a cancellare e' stato il chiamante la cancellazione deve propagarsi come tale,
            // altrimenti chi ha avviato una transazione non riesce a inviare [AN]
            currentCoroutineContext().ensureActive()
            trace("attesa di '$command' SCADUTA dopo ${timeoutMs / 1000.0}s senza alcun messaggio utile")
            throw PagAmicoTimeoutException(
                "Nessuna risposta dal pagAmico entro ${timeoutMs / 1000.0}s per il comando '$command'"
            )
        }
        trace("attesa di '$command' soddisfatta da: ${shorten(frame.raw)}")
        return frame
    }

    // ------------------------------------------------------------------ loop di ricezione

    private suspend fun receiveLoop() {
        val stream = input ?: return
        val decoder: CharsetDecoder = charset.newDecoder()
        val bytes = ByteArray(16 * 1024)
        val charBuffer = CharBuffer.allocate(16 * 1024)
        var fault: Throwable? = null

        try {
            while (true) {
                val read = try {
                    stream.read(bytes)
                } catch (e: SocketTimeoutException) {
                    drainFrames(idle = true) // silenzio: chiudo eventuali frame testuali
                    continue
                }
                if (read < 0) throw IOException("Connessione chiusa dal pagAmico")
                if (read == 0) continue

                trace("letti $read byte dal socket")
                charBuffer.clear()
                decoder.decode(ByteBuffer.wrap(bytes, 0, read), charBuffer, false)
                charBuffer.flip()
                parser.append(charBuffer.toString())
                drainFrames(idle = false)
                if (parser.bufferedLength > 0) {
                    trace("nel buffer restano ${parser.bufferedLength} caratteri in attesa del resto del messaggio")
                }
            }
        } catch (e: CancellationException) {
            // chiusura richiesta
        } catch (e: Throwable) {
            fault = e
        } finally {
            // come in C#: alla caduta le attese falliscono subito, invece di restare sospese fino al timeout
            failWaiters(fault?.message ?: "Connessione chiusa")
            onDisconnected?.invoke(fault)
        }
    }

    private suspend fun drainFrames(idle: Boolean) {
        while (true) {
            val frame = parser.readFrame(idle) ?: return
            if (frame.raw.isEmpty()) continue
            trace(
                "messaggio separato: ${if (frame.isJson) "JSON" else "testo"}, ${frame.raw.length} caratteri" +
                    if (idle) " (chiuso dal silenzio)" else ""
            )
            val orphan = dispatch(frame)
            _frames.emit(frame)
            if (orphan != null) {
                trace(orphan)
                onOrphanFrame?.invoke(frame)
            }
        }
    }

    /** Consegna il messaggio alle attese; restituisce la ragione se nessuna lo riconosce (frame orfano). */
    private fun dispatch(frame: PagAmicoFrame): String? {
        val snapshot = synchronized(gate) { waiters.toList() }
        var claimed = false
        for (w in snapshot) {
            when (runCatching { w.classify(frame) }.getOrDefault(Verdict.FOREIGN)) {
                Verdict.FINAL -> {
                    claimed = true
                    synchronized(gate) { waiters.remove(w) }
                    w.result.complete(frame)
                }
                Verdict.PROGRESS -> {
                    claimed = true
                    // chiamata sincrona dal thread di ricezione: i parziali arrivano nell'ordine in cui la macchina li manda
                    runCatching { w.onProgress?.invoke(frame) }
                        .onFailure { trace("eccezione nel gestore di avanzamento, ignorata: ${it.message}") }
                }
                Verdict.FOREIGN -> Unit
            }
        }
        return when {
            claimed -> null
            snapshot.isEmpty() -> "messaggio non atteso da nessuno (nessun comando in corso): ${shorten(frame.raw)}"
            else -> "messaggio estraneo alle attese in corso: ${shorten(frame.raw)}"
        }
    }

    // ------------------------------------------------------------------ predicati

    /** Come un'attesa giudica un messaggio. */
    private enum class Verdict {
        /** Non e' suo: se nessun'altra attesa lo riconosce, e' un frame orfano. */
        FOREIGN,

        /** Avanzamento (accettazione, parziale): l'attesa continua. */
        PROGRESS,

        /** Esito: chiude l'attesa. */
        FINAL
    }

    /** Predicato booleano delle API pubbliche: vero chiude, falso e' avanzamento. */
    private fun verdicts(isFinal: (PagAmicoFrame) -> Boolean): (PagAmicoFrame) -> Verdict =
        { if (isFinal(it)) Verdict.FINAL else Verdict.PROGRESS }

    /**
     * Predicato dei comandi semplici. Sul simulatore ogni comando riceve il proprio codice (ST, SM, SB, PL...)
     * oppure OK, e un errore come testo o ER (collaudo dell'11/09/2026). Il resto non e' suo: sul simulatore un CM
     * arrivato in ritardo ha fatto da risposta a un ST. Unica eccezione [LO], che rimanda l'ultimo JSON qualunque sia.
     */
    private fun ownResponse(command: String): (PagAmicoFrame) -> Verdict {
        if (command == PagAmicoCommands.lastJson()) return { Verdict.FINAL }
        val code = command.take(2)
        return { f ->
            val r = f.response
            when {
                f.isText -> Verdict.FINAL
                r == null -> Verdict.FOREIGN
                r == "OK" || r.equals("ER", ignoreCase = true) || r.equals(code, ignoreCase = true) -> Verdict.FINAL
                else -> Verdict.FOREIGN
            }
        }
    }

    private fun terminal(vararg finalResponses: String): (PagAmicoFrame) -> Boolean = { f ->
        when {
            f.isText -> true // "CMD ERROR", "ER ...", ecc.
            f.response == null -> false
            f.response == "OK" || f.response == "p" -> false // accettazione / parziale
            else -> finalResponses.isEmpty() ||
                finalResponses.any { it.equals(f.response, ignoreCase = true) } ||
                f.response.equals("ER", ignoreCase = true) ||
                f.response.equals("AN", ignoreCase = true)
        }
    }

    /**
     * Predicato dell'incasso, in due fasi (esito della risposta PayPrint, D3).
     *
     * Prima dell'OK di accettazione un testo o un ER vuol dire incasso rifiutato ("BUSY", "CMD ERROR") e chiude.
     *
     * Dopo l'OK chiudono solo la risposta finale del comando, [AN] e il [CM] finale. Un testo o un ER sono la
     * risposta a qualcos'altro: non toccano l'incasso e finiscono all'evento dei frame orfani.
     */
    private fun classifyCollection(c: Collection, finalResponse: String, f: PagAmicoFrame): Verdict {
        val r = f.response
        if (!c.accepted) {
            return when {
                f.isText -> Verdict.FINAL
                r == null -> Verdict.FOREIGN
                r == "OK" -> {
                    c.accepted = true
                    trace("incasso '${c.command}' accettato: da qui chiudono solo $finalResponse, AN e il CM finale")
                    c.acceptance.complete(Unit)
                    Verdict.PROGRESS
                }
                r == "p" -> Verdict.PROGRESS
                r.equals("ER", ignoreCase = true) -> Verdict.FINAL
                isCollectionOutcome(f, finalResponse) -> Verdict.FINAL
                else -> Verdict.FOREIGN
            }
        }
        return when {
            f.isText || r == null -> Verdict.FOREIGN
            r == "p" -> Verdict.PROGRESS
            isCollectionOutcome(f, finalResponse) -> Verdict.FINAL
            else -> Verdict.FOREIGN
        }
    }

    private fun isCollectionOutcome(f: PagAmicoFrame, finalResponse: String): Boolean =
        f.response.equals(finalResponse, ignoreCase = true) ||
            f.response.equals("AN", ignoreCase = true) ||
            isCommitOutcome(f)

    /**
     * [CM] finale: quello con errorCode diverso da "OK" e da "NO" (D1). L'accettazione porta errorCode "OK" e
     * committedAmount a zero, il rifiuto errorCode "NO"; il manuale non documenta la differenza, il log del
     * simulatore del 2/9 si'.
     */
    private fun isCommitOutcome(f: PagAmicoFrame): Boolean =
        f.response == "CM" && !isCommitAck(f) && !isCommitRefused(f)

    private fun isCommitAck(f: PagAmicoFrame): Boolean =
        f.response == "CM" && f.json?.errorCode.equals("OK", ignoreCase = true)

    private fun isCommitRefused(f: PagAmicoFrame): Boolean =
        f.response == "CM" && f.json?.errorCode.equals("NO", ignoreCase = true)

    /**
     * Predicato di [CM]: chiude sul rifiuto, sull'esito, o sulla seconda accettazione (se la macchina ne mandasse due).
     * Dentro un incasso un testo non e' suo; fuori, come per ogni comando, chiude con errore.
     */
    private fun commitVerdicts(insideCollection: Boolean): (PagAmicoFrame) -> Verdict {
        var acks = 0
        return { f ->
            when {
                f.isText -> if (insideCollection) Verdict.FOREIGN else Verdict.FINAL
                f.response != "CM" -> if (insideCollection) Verdict.FOREIGN else Verdict.PROGRESS
                !isCommitAck(f) -> Verdict.FINAL
                ++acks >= 2 -> Verdict.FINAL
                else -> Verdict.PROGRESS
            }
        }
    }

    private fun requireJson(frame: PagAmicoFrame, command: String): PagAmicoResponse {
        val json = frame.json ?: throw PagAmicoException("Risposta non JSON al comando '$command': ${frame.raw}", frame)
        if (json.isError) throw PagAmicoException.fromFrame(frame)
        return json
    }

    /**
     * Invia un comando e restituisce la sua risposta (per i comandi che rispondono una sola volta): quella col
     * codice del comando (ST su ST), un OK, oppure un errore. Un altro messaggio arrivato nel frattempo - un CM in
     * ritardo, un parziale - non la sostituisce: va ai frame orfani.
     */
    suspend fun sendSimple(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): PagAmicoResponse =
        requireJson(requestCore(command, timeoutMs, null, ownResponse(command)), command)

    // ------------------------------------------------------------------ stato

    /** [ST] Situazione completa: fondi cassa, scorte, stato accettatori. */
    suspend fun status(): PagAmicoResponse = sendSimple(PagAmicoCommands.status())

    /** [CL] Pulisce il display (nessuna risposta prevista). */
    suspend fun clearDisplay() = sendRaw(PagAmicoCommands.clearDisplay())

    /** [LO] Rinvia l'ultimo JSON trasmesso dal pagAmico (utile dopo una disconnessione). */
    suspend fun lastJson(): PagAmicoResponse = sendSimple(PagAmicoCommands.lastJson())

    // ------------------------------------------------------------------ incasso

    /**
     * [IN] Incasso in contanti. Restituisce il messaggio finale ("IN", "AN" se annullato, "CM" se chiuso
     * da [commit]); lancia [PagAmicoRejectedException] / [PagAmicoBusyException] se la macchina lo rifiuta
     * prima di accettarlo, [PagAmicoConnectionLostException] se la connessione cade.
     *
     * La cancellazione della coroutine invia [AN] al pagAmico (dopo l'OK, se l'incasso non e' ancora
     * accettato) e si propaga subito al chiamante come [PagAmicoCollectionCancelledException], che porta
     * l'esito dell'incasso in `outcome`. L'esito arriva anche a [onOrphanFrame].
     * A [onPartial] arrivano solo i parziali [p], in ordine.
     */
    suspend fun collectCash(
        amountEuro: BigDecimal,
        timeoutMs: Long = TRANSACTION_TIMEOUT_MS,
        onPartial: ((PagAmicoResponse) -> Unit)? = null
    ): PagAmicoResponse = transaction(PagAmicoCommands.collect(amountEuro), "IN", timeoutMs, onPartial)

    /** [PO] Incasso tramite POS integrato. */
    suspend fun collectPos(
        amountEuro: BigDecimal,
        timeoutMs: Long = TRANSACTION_TIMEOUT_MS,
        onPartial: ((PagAmicoResponse) -> Unit)? = null
    ): PagAmicoResponse = transaction(PagAmicoCommands.collectPos(amountEuro), "PO", timeoutMs, onPartial)

    /**
     * [IM] Incasso automatico: contanti oppure POS, decide il cliente (FW >= 8.71).
     * A fine incasso errorCode vale "CONT" o "POS".
     */
    suspend fun collectAuto(
        amountEuro: BigDecimal,
        timeoutMs: Long = TRANSACTION_TIMEOUT_MS,
        onPartial: ((PagAmicoResponse) -> Unit)? = null
    ): PagAmicoResponse = transaction(PagAmicoCommands.collectAuto(amountEuro), "IM", timeoutMs, onPartial)

    /** [I2] Incasso con timeout in secondi (sconsigliato dal manuale). */
    suspend fun collectCashWithTimeout(
        amountEuro: BigDecimal,
        seconds: Int,
        onPartial: ((PagAmicoResponse) -> Unit)? = null
    ): PagAmicoResponse = transaction(
        PagAmicoCommands.collectWithTimeout(seconds, amountEuro),
        "IN",
        (seconds + 30) * 1000L,
        onPartial
    )

    private suspend fun transaction(
        command: String,
        finalResponse: String,
        timeoutMs: Long,
        onPartial: ((PagAmicoResponse) -> Unit)?
    ): PagAmicoResponse {
        throwIfCollecting(command)
        val clientScope = scope ?: throw PagAmicoException("Client non connesso: chiamare connect()")
        val c = Collection(command)

        val outcome: PagAmicoFrame = commandMutex.withLock {
            // ai parziali arrivano solo i frame [p], chiamati in modo sincrono e quindi in ordine
            val waiter = Waiter({ classifyCollection(c, finalResponse, it) }) { f ->
                if (f.isPartial) f.json?.let { onPartial?.invoke(it) }
            }
            synchronized(gate) {
                collection = c
                waiters += waiter
            }

            // l'incasso vive nello scope del client, non in quello del chiamante: se il chiamante viene
            // cancellato la macchina continua a incassare, e l'attesa deve arrivare fino all'esito
            val job = clientScope.async {
                var frame: PagAmicoFrame? = null
                try {
                    sendCommand(command, duringCollection = true)
                    awaitWaiter(waiter, command, timeoutMs).also { frame = it }
                } finally {
                    synchronized(gate) { waiters.remove(waiter) }
                    endCollection(c, frame)
                }
            }

            try {
                job.await()
            } catch (e: CancellationException) {
                if (currentCoroutineContext().isActive) {
                    // cancellato lo scope del client (disconnect), non il chiamante
                    throw PagAmicoConnectionLostException("Connessione chiusa durante l'incasso '$command'", c.accepted)
                }
                // cancellato il chiamante: annullo lato macchina; l'esito viaggia con l'eccezione
                // e arriva anche a onOrphanFrame
                synchronized(gate) { c.detached = true }
                clientScope.launch { cancelFromCaller(c) }
                val outcome = clientScope.async { requireJson(c.outcome.await(), command) }
                throw PagAmicoCollectionCancelledException(command, outcome).apply { initCause(e) }
            }
        }

        if (!c.accepted && (outcome.isText || outcome.isError)) {
            val reason = if (outcome.isJson) PagAmicoException.fromFrame(outcome).message else outcome.raw
            if (outcome.isBusy) {
                throw PagAmicoBusyException("Incasso '$command' rifiutato, macchina impegnata: $reason", outcome)
            }
            throw PagAmicoRejectedException("Incasso '$command' rifiutato: $reason", outcome)
        }
        return requireJson(outcome, command)
    }

    /** Chiude lo stato dell'incasso e sblocca chi aspettava l'accettazione o l'esito. */
    private fun endCollection(c: Collection, outcome: PagAmicoFrame?) {
        val (commitWaiter, detached) = synchronized(gate) {
            if (collection === c) collection = null
            c.commitWaiter to c.detached
        }

        val ended = PagAmicoException(
            if (outcome == null) "Incasso '${c.command}' terminato senza esito"
            else "Incasso '${c.command}' chiuso da '${shorten(outcome.raw)}'"
        )
        c.acceptance.completeExceptionally(ended)
        if (outcome == null) c.outcome.completeExceptionally(ended) else c.outcome.complete(outcome)

        // un [CM] ancora in attesa: se l'incasso si e' chiuso sul CM finale e' il suo esito, altrimenti
        // la macchina ha chiuso prima (IN, AN) e l'eventuale risposta al CM arrivera' come frame orfano
        if (commitWaiter != null) {
            if (outcome != null && isCommitOutcome(outcome)) commitWaiter.result.complete(outcome)
            else commitWaiter.result.completeExceptionally(PagAmicoException("${ended.message} prima dell'esito di CM"))
        }
        trace(if (outcome == null) "incasso '${c.command}' chiuso senza esito" else "incasso '${c.command}' chiuso")

        if (detached && outcome != null) {
            trace("esito di un incasso il cui chiamante e' stato cancellato: ${shorten(outcome.raw)}")
            onOrphanFrame?.invoke(outcome)
        }
    }

    /**
     * Prenota il solo comando di chiusura dell'incasso e lo invia, dopo l'OK se l'incasso non e' ancora
     * accettato. Vince il primo: una seconda chiusura lancia subito.
     */
    private suspend fun sendClosing(c: Collection, command: String, commitWaiter: Waiter? = null) {
        synchronized(gate) {
            if (collection !== c) throw PagAmicoException("Nessun incasso aperto: '$command' non inviato")
            c.closing?.let {
                throw PagAmicoException("Chiusura dell'incasso gia' richiesta con $it: '$command' non inviato")
            }
            c.closing = command
            if (commitWaiter != null) c.commitWaiter = commitWaiter
        }

        try {
            if (!c.acceptance.isCompleted) trace("'$command' in attesa dell'OK dell'incasso prima di partire")
            c.acceptance.await()
            sendCommand(command, duringCollection = true)
        } catch (e: Throwable) {
            synchronized(gate) { if (c.closing == command) c.closing = null }
            throw e
        }
    }

    private suspend fun cancelFromCaller(c: Collection) {
        synchronized(gate) {
            c.cancelRequested = true
            c.closing?.let {
                trace("annullo richiesto mentre e' in corso $it: l'AN partira' solo se il $it viene rifiutato")
                return
            }
        }
        try {
            sendClosing(c, PagAmicoCommands.cancel())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            trace("annullo non inviato: ${e.message}")
        }
    }

    /**
     * [AN] Annulla l'operazione in corso; l'importo restituito e' in changeReturn.
     *
     * A incasso aperto passa per la via laterale, senza accodarsi dietro l'incasso: parte dopo l'OK di
     * accettazione e restituisce l'esito dell'incasso (di norma [AN], ma [IN] se il cliente ha finito prima).
     */
    suspend fun cancelOperation(): PagAmicoResponse {
        val c = synchronized(gate) { collection }
        if (c == null) {
            val frame = request(PagAmicoCommands.cancel(), DEFAULT_TIMEOUT_MS) { it.isText || it.response == "AN" }
            return requireJson(frame, "AN")
        }

        sendClosing(c, PagAmicoCommands.cancel())
        val outcome = try {
            withTimeout(TRANSACTION_TIMEOUT_MS) { c.outcome.await() }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw PagAmicoTimeoutException("Nessun esito dal pagAmico entro ${TRANSACTION_TIMEOUT_MS / 1000.0}s per 'AN'")
        }
        return requireJson(outcome, "AN")
    }

    /**
     * [CM] Chiude l'incasso in corso trattenendo quanto gia' incassato, e restituisce l'**esito**: il [CM]
     * con errorCode diverso da "OK" (il primo [CM], con errorCode "OK" e committedAmount a zero, e' solo
     * l'accettazione). **L'importo trattenuto e' in collectedAmount**; committedAmount serve da controllo.
     * Un [CM] rifiutato torna con errorCode "NO" e lascia l'incasso aperto.
     *
     * A incasso aperto non si accoda dietro l'incasso: parte subito, o all'OK se l'incasso non e' ancora
     * accettato. Un solo comando di chiusura per incasso: se e' gia' partito un [AN] o un [CM] lancia.
     *
     * NB: dopo il commit possono ancora arrivare parziali [p] o [IN], l'hopper legge molto velocemente.
     */
    suspend fun commit(timeoutMs: Long = TRANSACTION_TIMEOUT_MS): PagAmicoResponse {
        val c = synchronized(gate) { collection }
        val frame = if (c == null) {
            requestCore(PagAmicoCommands.commit(), timeoutMs, null, commitVerdicts(insideCollection = false))
        } else {
            // l'attesa si registra prima dell'invio, altrimenti una risposta immediata andrebbe persa
            val waiter = Waiter(commitVerdicts(insideCollection = true), null)
            synchronized(gate) { waiters += waiter }
            val f = try {
                sendClosing(c, PagAmicoCommands.commit(), waiter)
                awaitWaiter(waiter, "CM", timeoutMs)
            } finally {
                synchronized(gate) {
                    waiters.remove(waiter)
                    if (c.commitWaiter === waiter) c.commitWaiter = null
                }
            }

            if (isCommitRefused(f)) {
                // CM rifiutato: il posto di chiusura si libera, e un annullo chiesto nel frattempo parte ora
                val cancel = synchronized(gate) {
                    if (c.closing == PagAmicoCommands.commit()) c.closing = null
                    c.cancelRequested
                }
                trace("CM rifiutato dalla macchina (errorCode NO): l'incasso resta aperto")
                if (cancel) scope?.launch { cancelFromCaller(c) }
            }
            f
        }

        val response = requireJson(frame, "CM")
        val collected = response.collectedAmount
        val committed = response.committedAmount
        if (collected != null && committed != null && collected.compareTo(committed) != 0) {
            trace("controllo CM: collectedAmount $collected diverso da committedAmount $committed; vale collectedAmount")
        }
        return response
    }

    // ------------------------------------------------------------------ erogazione

    /** [PA] Eroga un importo (resto/rimborso). */
    suspend fun dispense(
        amountEuro: BigDecimal,
        password: String = "",
        timeoutMs: Long = TRANSACTION_TIMEOUT_MS
    ): PagAmicoResponse {
        val cmd = PagAmicoCommands.dispense(amountEuro, password)
        return requireJson(request(cmd, timeoutMs, null, terminal("PA")), cmd)
    }

    /** [P2] Eroga un numero preciso di banconote per taglio. */
    suspend fun dispenseBanknotes(
        n5: Int, n10: Int, n20: Int, n50: Int, n100: Int, n200: Int,
        password: String = "",
        timeoutMs: Long = TRANSACTION_TIMEOUT_MS
    ): PagAmicoResponse {
        val cmd = PagAmicoCommands.dispenseBanknotes(n5, n10, n20, n50, n100, n200, password)
        return requireJson(request(cmd, timeoutMs, null, terminal("PB", "P2")), cmd)
    }

    /** [PM] Eroga un numero preciso di monete per taglio. */
    suspend fun dispenseCoins(
        c05: Int, c10: Int, c20: Int, c50: Int, c100: Int, c200: Int,
        password: String = "",
        timeoutMs: Long = TRANSACTION_TIMEOUT_MS
    ): PagAmicoResponse {
        val cmd = PagAmicoCommands.dispenseCoins(c05, c10, c20, c50, c100, c200, password)
        return requireJson(request(cmd, timeoutMs, null, terminal("PM")), cmd)
    }

    // ------------------------------------------------------------------ manutenzione / fondo cassa

    /** [M2] Sposta banconote nel cassetto BTA. */
    suspend fun moveBanknotesToBta(
        n5: Int, n10: Int, n20: Int, n50: Int, n100: Int, n200: Int,
        password: String = "",
        timeoutMs: Long = TRANSACTION_TIMEOUT_MS
    ): PagAmicoResponse {
        val cmd = PagAmicoCommands.moveBanknotesToBta(n5, n10, n20, n50, n100, n200, password)
        return requireJson(request(cmd, timeoutMs, null, terminal("M2")), cmd)
    }

    /** [MF] Sposta monete nel cassetto di recupero (solo modelli con cassetto monete sul fondo). */
    suspend fun moveCoinsToCashbox(
        c05: Int, c10: Int, c20: Int, c50: Int, c100: Int, c200: Int,
        password: String = "",
        timeoutMs: Long = TRANSACTION_TIMEOUT_MS
    ): PagAmicoResponse {
        val cmd = PagAmicoCommands.moveCoinsToCashbox(c05, c10, c20, c50, c100, c200, password)
        return requireJson(request(cmd, timeoutMs, null, terminal("MF")), cmd)
    }

    /** [BT] Azzera il cassetto BTA; l'importo presente e' in amountResettedBanknotesInBta. */
    suspend fun resetBta(password: String = ""): PagAmicoResponse = sendSimple(PagAmicoCommands.resetBta(password))

    /** [AF] Aggiorna il fondo cassa (monete, banconote o entrambi). */
    suspend fun updateCashFloat(target: CashFloatTarget, password: String = ""): PagAmicoResponse =
        sendSimple(PagAmicoCommands.updateCashFloat(target, password))

    /** [AZ] Azzera le banconote presenti (solo modelli a due cassetti). */
    suspend fun resetBanknotes(password: String = ""): PagAmicoResponse =
        sendSimple(PagAmicoCommands.resetBanknotes(password))

    /** [SM] Imposta scorta minima/massima monete. */
    suspend fun setCoinStock(thresholds: List<StockThreshold>): PagAmicoResponse =
        sendSimple(PagAmicoCommands.setCoinStock(thresholds))

    /** [SB] Imposta scorta minima/massima banconote. */
    suspend fun setBanknoteStock(thresholds: List<StockThreshold>): PagAmicoResponse =
        sendSimple(PagAmicoCommands.setBanknoteStock(thresholds))

    /** [EM] Abilita/disabilita i tagli monete in incasso e come resto. */
    suspend fun setCoinAcceptance(toggles: List<DenominationToggle>): PagAmicoResponse =
        sendSimple(PagAmicoCommands.enableCoins(toggles))

    /** [EB] Abilita/disabilita i tagli banconote in incasso e come resto. */
    suspend fun setBanknoteAcceptance(toggles: List<DenominationToggle>): PagAmicoResponse =
        sendSimple(PagAmicoCommands.enableBanknotes(toggles))

    /** [RI] Riavvia il pagAmico. */
    suspend fun reboot() = sendRaw(PagAmicoCommands.reboot())

    // ------------------------------------------------------------------ ricariche

    /** [RC]/[RS]/[VC]/[VS] Avvia una ricarica mista monete + banconote. Chiudere con [endReload]. */
    suspend fun startMixedReload(updateCashFloat: Boolean, sendPartials: Boolean = false): PagAmicoResponse =
        sendSimple(PagAmicoCommands.reloadMixed(updateCashFloat, sendPartials))

    /** [RM]/[R3] Ricarica monete. */
    suspend fun startCoinReload(updateCashFloat: Boolean): PagAmicoResponse =
        sendSimple(PagAmicoCommands.reloadCoins(updateCashFloat))

    /** [RB]/[R2] Ricarica banconote. */
    suspend fun startBanknoteReload(updateCashFloat: Boolean): PagAmicoResponse =
        sendSimple(PagAmicoCommands.reloadBanknotes(updateCashFloat))

    /** [FR] Fine ricarica. */
    suspend fun endReload(): PagAmicoResponse = sendSimple(PagAmicoCommands.reloadEnd())

    // ------------------------------------------------------------------ POS

    /** [PL] Rilegge l'ultima transazione POS, con o senza ristampa scontrino. */
    suspend fun readLastPosTransaction(reprintReceipt: Boolean): PagAmicoResponse =
        sendSimple(PagAmicoCommands.posLastTransaction(reprintReceipt))

    /** [PR] Totali POS (campi posTot1 / posTot2). */
    suspend fun readPosTotals(): PagAmicoResponse = sendSimple(PagAmicoCommands.posTotals())

    /** [PS] Chiusura giornaliera POS. */
    suspend fun closePosDay(timeoutMs: Long = 120_000): PagAmicoResponse =
        sendSimple(PagAmicoCommands.posDailyClose(), timeoutMs)

    /** [PZ] Riavvio POS. */
    suspend fun rebootPos(timeoutMs: Long = 120_000): PagAmicoResponse =
        sendSimple(PagAmicoCommands.posReboot(), timeoutMs)

    /** [PP] Primo DLL POS (ricarica certificati). */
    suspend fun posFirstDll(timeoutMs: Long = 300_000): PagAmicoResponse =
        sendSimple(PagAmicoCommands.posFirstDll(), timeoutMs)

    // ------------------------------------------------------------------ movimenti

    /** [MV] Elenco movimenti in un intervallo. La risposta termina con il marcatore `|\`. */
    suspend fun movements(
        from: LocalDateTime,
        to: LocalDateTime,
        cause: String = MovementCause.ALL,
        timeoutMs: Long = 60_000
    ): List<PagAmicoMovement> {
        val cmd = PagAmicoCommands.movements(from, to, cause)
        val frame = request(cmd, timeoutMs) { it.isText || it.json != null }
        if (frame.isText) throw PagAmicoException("Errore comando MV: ${frame.raw}", frame)
        return frame.json!!.movements
    }

    /** [MI] Singolo movimento per Id. */
    suspend fun movement(id: Long, timeoutMs: Long = DEFAULT_TIMEOUT_MS): PagAmicoMovement? {
        val cmd = PagAmicoCommands.movementById(id)
        val frame = request(cmd, timeoutMs) { it.isText || it.json != null }
        if (frame.isText) throw PagAmicoException("Errore comando MI: ${frame.raw}", frame)
        return frame.json!!.movements.firstOrNull()
    }

    // ------------------------------------------------------------------ display

    /** [DT]/[DG] Mostra una finestra di testo (nessuna risposta prevista). */
    suspend fun showText(
        text: String,
        position: DisplayPosition = DisplayPosition.TOP,
        fontSize: Int = 38,
        style: FontStyle = FontStyle.BOLD,
        color: FontColor = FontColor.BLUE
    ) = sendRaw(PagAmicoDisplay.showText(text, position, fontSize, style, color))

    /** [DS] Chiude la finestra di testo. */
    suspend fun closeText() = sendRaw(PagAmicoDisplay.closeText())

    /**
     * [DM] MessageBox con 1..3 bottoni: restituisce 1, 2 o 3 in base al bottone premuto.
     * Passare stringa vuota per nascondere un bottone.
     */
    suspend fun showMessageBox(
        text: String,
        button1: String,
        button2: String = "",
        button3: String = "",
        fontSize: Int = 29,
        style: FontStyle = FontStyle.NORMAL,
        color: FontColor = FontColor.GREEN,
        timeoutMs: Long = 120_000
    ): Int {
        val cmd = PagAmicoDisplay.messageBox(text, button1, button2, button3, fontSize, style, color)
        val frame = request(cmd, timeoutMs) { it.isText && BUTTON_REGEX.matches(it.raw.trim()) }
        return BUTTON_REGEX.find(frame.raw.trim())!!.groupValues[1].toInt()
    }

    /** [DC] Chiude il MessageBox o la finestra di input. */
    suspend fun closeMessageBox() = sendRaw(PagAmicoDisplay.closeMessageBox())

    /**
     * [DI] Finestra di input con tastiera on-screen: restituisce il testo digitato,
     * oppure null se l'utente ha annullato ("AN").
     */
    suspend fun readInput(
        title: String,
        initialText: String = "",
        keyboard: KeyboardLayout = KeyboardLayout.STANDARD,
        fontSize: Int = 29,
        style: FontStyle = FontStyle.NORMAL,
        color: FontColor = FontColor.BLUE,
        timeoutMs: Long = 120_000
    ): String? {
        val cmd = PagAmicoDisplay.inputBox(title, initialText, keyboard, fontSize, style, color)
        val frame = request(cmd, timeoutMs) { it.isText && it.raw.trim().isNotEmpty() }
        val value = frame.raw.trim()
        return if (value == PagAmicoTextResponses.CANCELLED) null else value
    }

    /**
     * [QR] Attiva la lettura di barcode / QrCode / Tessera Sanitaria / input da tastiera.
     * Restituisce il codice letto, oppure null se l'utente ha premuto Annulla ("AN").
     */
    suspend fun readCode(
        prompt: String,
        mode: KeyboardMode = KeyboardMode.NO_KEYBOARD,
        fontSize: Int = 16,
        style: FontStyle = FontStyle.BOLD,
        color: FontColor = FontColor.GREEN,
        timeoutMs: Long = 120_000
    ): String? {
        val cmd = PagAmicoDisplay.readCode(prompt, mode, fontSize, style, color)
        val frame = request(cmd, timeoutMs) { it.isText && it.raw.trim().isNotEmpty() }
        val value = frame.raw.trim()
        return if (value == "AN") null else value
    }

    /** [QA] Chiude la richiesta di lettura codice. */
    suspend fun closeCodeReader() = sendRaw(PagAmicoDisplay.closeCodeReader())

    /** [TS] Definisce la struttura della lista (JSON compatto, max 3999 caratteri). */
    suspend fun showListLayout(compactJson: String) = sendRaw(PagAmicoDisplay.listLayout(compactJson))

    /** [ID] Popola la lista. Alla pressione del bottone di uscita il pagAmico risponde "EX". */
    suspend fun showListData(compactJson: String) = sendRaw(PagAmicoDisplay.listData(compactJson))

    /** Attende la chiusura della lista da parte dell'utente ("EX"). */
    suspend fun waitListExit(timeoutMs: Long = 300_000) {
        waitFor(timeoutMs) { it.isText && it.raw.trim() == "EX" }
    }

    /** [CO] Chiude la lista. */
    suspend fun closeList() = sendRaw(PagAmicoDisplay.closeList())

    // ------------------------------------------------------------------ immagini

    /** [SF] Invia il logo permanente (PNG, area 571x520 dip). */
    suspend fun sendLogo(pngBytes: ByteArray) {
        sendRaw(buildImagePacket("SF", pngBytes))
        onCommandSent?.invoke("[SF] payload binario di ${pngBytes.size} byte")
    }

    /** [SI] Invia un'immagine temporanea (PNG/JPG/BMP) che sostituisce il logo fino alla rimozione. */
    suspend fun sendTemporaryImage(imageBytes: ByteArray) {
        sendRaw(buildImagePacket("SI", imageBytes))
        onCommandSent?.invoke("[SI] payload binario di ${imageBytes.size} byte")
    }

    /** [SR] Rimuove l'immagine temporanea e ripristina il logo. */
    suspend fun removeTemporaryImage() = sendRaw(PagAmicoCommands.removeTempImage())

    internal fun buildImagePacket(prefix: String, image: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(prefix.toByteArray(Charsets.US_ASCII))

        if (imageLayout == ImagePacketLayout.DOCUMENTED) {
            // "SF" + CHR(255) + CHR(255) + immagine + CHR(254) + CHR(254) + "||"
            out.write(0xFF); out.write(0xFF)
            out.write(image)
            out.write(0xFE); out.write(0xFE)
            out.write('|'.code); out.write('|'.code)
        } else {
            // esempio Python del manuale: b"SF\xFF\xFF||" + immagine + b"||\xFE\xFE"
            out.write(0xFF); out.write(0xFF)
            out.write('|'.code); out.write('|'.code)
            out.write(image)
            out.write('|'.code); out.write('|'.code)
            out.write(0xFE); out.write(0xFE)
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ stampa

    /**
     * Invia un job di stampa: PTSTST ... PTSTEN. La stampa parte dopo PTSTEN.
     * Gli ack testuali ("OK comando") vengono attesi ma un timeout non blocca il job.
     */
    suspend fun print(job: PagAmicoPrintJob, waitAck: Boolean = true) {
        for (cmd in job.build()) {
            if (!waitAck) {
                sendRaw(cmd)
                continue
            }
            runCatching { requestCore(cmd, 5_000, null, ownResponse(cmd)) }
                .onFailure { if (it !is PagAmicoTimeoutException) throw it }
        }
    }

    /** [PTSTAT] Stato stampante. */
    suspend fun printerStatus(): PrinterStatus {
        val frame = requestCore(PagAmicoPrint.status(), DEFAULT_TIMEOUT_MS, null, ownResponse(PagAmicoPrint.status()))
        val json = frame.json ?: throw PagAmicoException("Risposta inattesa a PTSTAT: ${frame.raw}", frame)
        return PrinterStatus.parse(json)
    }

    /** [PTSTAN] Annulla la stampa inviata ma non ancora eseguita. */
    suspend fun cancelPrint() = sendRaw(PagAmicoPrint.cancelPrint())

    // ------------------------------------------------------------------

    private class Waiter(
        val classify: (PagAmicoFrame) -> Verdict,
        val onProgress: ((PagAmicoFrame) -> Unit)?
    ) {
        val result = CompletableDeferred<PagAmicoFrame>()
    }

    /** Un incasso aperto. I campi mutabili si toccano sotto gate. */
    private class Collection(val command: String) {
        /** OK di accettazione ricevuto: da qui la macchina sta incassando. */
        @Volatile
        var accepted = false

        /** Comando di chiusura in corso ("AN" o "CM"), null se il posto e' libero. */
        var closing: String? = null

        /** Annullo chiesto dal chiamante mentre il posto era occupato da un CM. */
        var cancelRequested = false

        /** Il chiamante e' stato cancellato: l'esito va a onOrphanFrame. */
        var detached = false

        /** Attesa di un [CM] mandato dentro l'incasso. */
        var commitWaiter: Waiter? = null

        /** Si completa all'OK; fallisce se l'incasso si chiude prima (rifiuto, caduta, timeout). */
        val acceptance = CompletableDeferred<Unit>()

        /** Messaggio che ha chiuso l'incasso. */
        val outcome = CompletableDeferred<PagAmicoFrame>()
    }
}
