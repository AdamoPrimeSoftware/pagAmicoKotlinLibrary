package it.payprint.pagamico.demo

import it.payprint.pagamico.client.PagAmicoClient
import it.payprint.pagamico.exceptions.PagAmicoException
import it.payprint.pagamico.client.PagAmicoFileLogger
import it.payprint.pagamico.exceptions.PagAmicoTimeoutException
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Esempio d'uso del client pagAmico.
 * Esecuzione: `gradle run -PmainClass=it.payprint.pagamico.demo.DemoKt --args="192.168.1.29 9100 1.50"`
 */
fun main(args: Array<String>) = runBlocking {
    val host = args.getOrNull(0) ?: "192.168.1.29"
    val port = args.getOrNull(1)?.toIntOrNull() ?: PagAmicoClient.DEFAULT_PORT
    val amount = BigDecimal(args.getOrNull(2)?.replace(',', '.') ?: "1.50")

    val client = PagAmicoClient(host, port)

    // tracciato su file: un file al giorno in %LOCALAPPDATA%\PayPrint.PagAmico\logs
    val logger = PagAmicoFileLogger(prefix = "demo")
    logger.attach(client, this)

    client.onCommandSent = { cmd -> println("  -> $cmd") }
    client.onDisconnected = { ex -> println("  !! disconnesso: ${ex?.message ?: "chiusura richiesta"}") }

    // diagnostica interna: pause imposte, separazione dei messaggi, attese scadute
    client.onTrace = { message ->
        println("  ..  $message")
        logger.write("..", message)
    }

    println("Connessione a $host:$port ...")
    println("Log della sessione: ${logger.currentFile.absolutePath}")
    client.connect()

    try {
        showStatus(client)
        collect(client, amount)
        showMovements(client)
    } catch (e: PagAmicoException) {
        System.err.println("ERRORE pagAmico: ${e.message}")
    } finally {
        logger.close()
        client.disconnect()
    }
}

private suspend fun showStatus(client: PagAmicoClient) {
    println("\n== [ST] Situazione ==")
    val st = client.status()

    println("Modello ${st.typePagAmico}  FW ${st.firmwareVersion}  matricola ${st.serialNumber}")
    println("Monete disponibili: " + st.coinsByDenomination.entries.joinToString(", ") { (cents, qty) ->
        "%.2f EUR x%d".format(cents / 100.0, qty)
    })
    println("Cassetti banconote:")
    st.drawers.filter { it.isConfigured }.forEach { println("  $it") }

    val warnings = st.status.warnings()
    if (warnings.isNotEmpty()) println("ATTENZIONE: " + warnings.joinToString(" | "))
}

private suspend fun collect(client: PagAmicoClient, amount: BigDecimal) {
    println("\n== [IN] Incasso di $amount EUR ==")

    try {
        val result = client.collectCash(amount) { partial ->
            println(
                "  parziale: incassato ${partial.collectedAmount} EUR " +
                    "(monete ${partial.collectedCoins}, banconote ${partial.collectedBanknotes})"
            )
        }

        if (result.response == "AN") {
            println("Incasso annullato. Restituiti ${result.changeReturn} EUR" +
                if (result.isHalted) " (annullo dal pannello del pagAmico)" else "")
            return
        }

        println(
            "Incassato ${result.collectedAmount} EUR, resto erogato " +
                "${result.changeCoins} in monete + ${result.changeBanknotes} in banconote"
        )

        result.amountUnpaid?.takeIf { it.signum() > 0 }?.let {
            println("ATTENZIONE: resto NON erogato per $it EUR - ricaricare le monete")
        }
        result.amountBanknotesInBta?.takeIf { it.signum() > 0 }?.let {
            println("Banconote deviate in BTA per $it EUR")
        }
        if (result.noteCollectedBta == 9) println("ATTENZIONE: probabile banconota doppia spostata in BTA")

        println("Id movimento: ${result.id}")
    } catch (e: PagAmicoTimeoutException) {
        println("Timeout: nessuna risposta, invio [AN] di sicurezza")
        client.cancelOperation()
    }
}

private suspend fun showMovements(client: PagAmicoClient) {
    println("\n== [MV] Movimenti di oggi ==")
    val today = LocalDateTime.now().toLocalDate()
    try {
        val movements = client.movements(today.atStartOfDay(), today.atTime(LocalTime.of(23, 59)))
        movements.take(20).forEach { println("  $it") }
        println("Totale movimenti: ${movements.size}")
    } catch (e: PagAmicoException) {
        println("Movimenti non disponibili: ${e.message} (log finanziari disabilitati?)")
    }
}
