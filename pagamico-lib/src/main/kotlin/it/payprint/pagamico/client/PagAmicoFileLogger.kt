package it.payprint.pagamico.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Registra su file tutto il traffico con il pagAmico: comandi trasmessi, risposte ricevute
 * ed eventi di connessione. Un file al giorno, in append, apribile mentre viene scritto.
 *
 * Serve sia in sviluppo (rileggere una sessione, allegare un tracciato a una segnalazione)
 * sia in esercizio: quando una cassa contesta un incasso, il tracciato e' l'unica prova
 * di che cosa e' stato chiesto alla macchina e che cosa ha risposto.
 *
 * Su Android passare esplicitamente una cartella scrivibile (per esempio `context.filesDir`).
 */
class PagAmicoFileLogger(
    directory: File? = null,
    private val prefix: String = "pagamico"
) : Closeable {

    val logDirectory: File = (directory ?: defaultDirectory).also { it.mkdirs() }

    /** File attualmente in scrittura (cambia a mezzanotte). */
    val currentFile: File get() = File(logDirectory, "$prefix-${clock().toLocalDate()}.log")

    /** Orologio del logger: sostituibile solo dai test, per provare il cambio di giorno. */
    internal var clock: () -> LocalDateTime = { LocalDateTime.now() }

    /** Se false le righe vengono scartate senza toccare il file. */
    var enabled: Boolean = true

    /** Massima lunghezza di una riga registrata; oltre viene troncata. 0 = nessun limite. */
    var maxLineLength: Int = 0

    /** Invocata dopo ogni riga scritta, con la riga formattata (utile per una finestra di log). */
    var onLineWritten: ((String) -> Unit)? = null

    private val lock = Any()
    private var stream: FileOutputStream? = null
    private var openDate: LocalDate? = null
    private var closed = false
    private var attachedJob: Job? = null

    /** Aggancia il logger al client: da qui in poi registra TX, RX e disconnessioni. */
    fun attach(client: PagAmicoClient, scope: CoroutineScope): Job {
        detach()

        val previousSent = client.onCommandSent
        client.onCommandSent = { cmd ->
            previousSent?.invoke(cmd)
            write("TX", cmd)
        }

        val previousDisconnect = client.onDisconnected
        client.onDisconnected = { ex ->
            previousDisconnect?.invoke(ex)
            write("--", if (ex == null) "disconnesso (chiusura richiesta)" else "disconnesso: ${ex.message}")
        }

        write("--", "logger agganciato a ${client.host}:${client.port}")

        val job = scope.launch { client.frames.collect { write("RX", it.raw) } }
        attachedJob = job
        return job
    }

    fun detach() {
        attachedJob?.cancel()
        attachedJob = null
    }

    /** Registra una riga arbitraria (esito di un comando, nota applicativa, errore). */
    fun write(kind: String, text: String?) {
        if (!enabled || closed) return

        val line = "${clock().format(TIME_FORMAT)}  ${kind.padEnd(2)}  ${sanitize(text)}"

        val bytes = (line + System.lineSeparator()).toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            runCatching {
                ensureStream()
                val out = stream ?: return@runCatching
                // Esclusione fra processi, come il mutex con nome in C#: un lock del sistema operativo sul
                // file, preso per ogni riga. Dentro la JVM prima si serializza per percorso: due FileLock
                // dello stesso processo sullo stesso file lancerebbero OverlappingFileLockException.
                synchronized(pathLock(currentFile)) {
                    out.channel.lock().use { out.write(bytes) }
                }
            }
            // un log che non riesce a scrivere non deve mai fermare l'incasso in corso
        }

        onLineWritten?.invoke(line)
    }

    private fun sanitize(text: String?): String {
        if (text.isNullOrEmpty()) return ""

        val sb = StringBuilder(text.length)
        text.forEach { c ->
            when {
                c == '\r' || c == '\n' -> sb.append(' ')
                // i caratteri di controllo del messaggio POS renderebbero illeggibile il file
                c.isISOControl() -> sb.append("<%02X>".format(c.code))
                else -> sb.append(c)
            }
        }

        val line = sb.toString()
        return if (maxLineLength > 0 && line.length > maxLineLength) {
            line.take(maxLineLength) + "... (+${line.length - maxLineLength} caratteri)"
        } else {
            line
        }
    }

    private fun ensureStream() {
        val today = clock().toLocalDate()
        if (stream != null && openDate == today) return

        stream?.runCatching { close() }
        logDirectory.mkdirs()
        stream = FileOutputStream(currentFile, true)
        openDate = today
    }

    override fun close() {
        if (closed) return
        detach()
        synchronized(lock) {
            closed = true
            stream?.runCatching { close() }
            stream = null
        }
    }

    companion object {
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

        private val pathLocks = ConcurrentHashMap<String, Any>()

        private fun pathLock(file: File): Any = pathLocks.computeIfAbsent(file.absoluteFile.normalize().path) { Any() }

        /** Su Windows %LOCALAPPDATA%\PayPrint.PagAmico\logs, altrimenti ~/.payprint-pagamico/logs */
        val defaultDirectory: File
            get() {
                val localAppData = System.getenv("LOCALAPPDATA")
                return if (!localAppData.isNullOrBlank()) {
                    File(File(localAppData, "PayPrint.PagAmico"), "logs")
                } else {
                    File(File(System.getProperty("user.home"), ".payprint-pagamico"), "logs")
                }
            }
    }
}
