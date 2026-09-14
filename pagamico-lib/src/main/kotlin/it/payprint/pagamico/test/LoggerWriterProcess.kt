package it.payprint.pagamico.test

import it.payprint.pagamico.client.PagAmicoFileLogger
import java.io.File

/**
 * Processo figlio del test sul registro fra processi: scrive [count] righe "TX <etichetta>-n" nel file
 * del giorno. Uso: LoggerWriterProcessKt <cartella> <prefisso> <etichetta> <count>
 */
fun main(args: Array<String>) {
    val (dir, prefix, label, count) = args
    PagAmicoFileLogger(File(dir), prefix).use { log ->
        repeat(count.toInt()) { log.write("TX", "$label-$it") }
    }
}
