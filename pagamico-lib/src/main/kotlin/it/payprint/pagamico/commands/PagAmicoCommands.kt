package it.payprint.pagamico.commands

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Costruttori delle stringhe di comando del protocollo TCP-IP pagAmico.
 *
 * ATTENZIONE: nei comandi gli importi sono in CENTESIMI a lunghezza fissa;
 * nelle risposte JSON sono in EURO.
 */
object PagAmicoCommands {

    private val MOVEMENT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")

    /** Converte euro in centesimi con arrotondamento commerciale. */
    fun toCents(euro: BigDecimal): Long =
        euro.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()

    private fun fixed(value: Long, digits: Int): String {
        require(value >= 0) { "Importo/quantita' negativa non ammessa" }
        val s = value.toString()
        require(s.length <= digits) { "Valore $value eccede le $digits cifre previste dal protocollo" }
        return s.padStart(digits, '0')
    }

    private fun pwd(password: String?): String {
        if (password.isNullOrEmpty()) return ""
        require(password.length <= 19) { "La password di autorizzazione ha massimo 19 caratteri" }
        return password
    }

    private fun counts3(vararg counts: Int): String =
        counts.joinToString("") { fixed(it.toLong(), 3) }

    // ---- incasso ---------------------------------------------------------

    /** [ IN] Incasso contanti: INxxxxxx (6 cifre, centesimi). */
    fun collect(amountEuro: BigDecimal): String = "IN" + fixed(toCents(amountEuro), 6)

    /** [ I2] Incasso con timeout: I2tttxxxxxx. */
    fun collectWithTimeout(seconds: Int, amountEuro: BigDecimal): String {
        require(seconds in 0..999) { "Timeout fuori range 0..999 secondi" }
        return "I2" + fixed(seconds.toLong(), 3) + fixed(toCents(amountEuro), 6)
    }

    /** [ PO] Incasso tramite POS integrato: POxxxxxx. */
    fun collectPos(amountEuro: BigDecimal): String = "PO" + fixed(toCents(amountEuro), 6)

    /** [ IM] Incasso automatico contanti/POS (FW >= 8.71): IMxxxxxx. */
    fun collectAuto(amountEuro: BigDecimal): String = "IM" + fixed(toCents(amountEuro), 6)

    /** [ AN] Annulla l'operazione in corso. */
    fun cancel(): String = "AN"

    /** [ CM] Chiude l'incasso trattenendo il parziale incassato. */
    fun commit(): String = "CM"

    // ---- erogazione ------------------------------------------------------

    /** [ PA] Eroga un importo: PAxxxxxxxxxx + password (10 cifre, centesimi). */
    fun dispense(amountEuro: BigDecimal, password: String = ""): String =
        "PA" + fixed(toCents(amountEuro), 10) + pwd(password)

    /** [ P2] Eroga banconote per taglio: P2 aaa bbb ccc ddd eee fff + password. */
    fun dispenseBanknotes(n5: Int, n10: Int, n20: Int, n50: Int, n100: Int, n200: Int, password: String = ""): String =
        "P2" + counts3(n5, n10, n20, n50, n100, n200) + pwd(password)

    /** [ PM] Eroga monete (0,05 / 0,10 / 0,20 / 0,50 / 1,00 / 2,00) + password. */
    fun dispenseCoins(c05: Int, c10: Int, c20: Int, c50: Int, c100: Int, c200: Int, password: String = ""): String =
        "PM" + counts3(c05, c10, c20, c50, c100, c200) + pwd(password)

    /** [ M2] Sposta banconote nel cassetto BTA. */
    fun moveBanknotesToBta(n5: Int, n10: Int, n20: Int, n50: Int, n100: Int, n200: Int, password: String = ""): String =
        "M2" + counts3(n5, n10, n20, n50, n100, n200) + pwd(password)

    /** [ MF] Sposta monete nel cassetto di recupero. */
    fun moveCoinsToCashbox(c05: Int, c10: Int, c20: Int, c50: Int, c100: Int, c200: Int, password: String = ""): String =
        "MF" + counts3(c05, c10, c20, c50, c100, c200) + pwd(password)

    // ---- fondo cassa e configurazione -----------------------------------

    /** [ AF] Aggiorna fondo cassa: AFn + password. */
    fun updateCashFloat(target: CashFloatTarget, password: String = ""): String =
        "AF" + target.code + pwd(password)

    /** [ BT] Azzera cassetto BTA. */
    fun resetBta(password: String = ""): String = "BT" + pwd(password)

    /** [ AZ] Azzera banconote presenti (solo modelli a due cassetti). */
    fun resetBanknotes(password: String = ""): String = "AZ2" + pwd(password)

    /** [ SM] Scorte monete: 6 tagli nell'ordine 0,05 0,10 0,20 0,50 1,00 2,00. */
    fun setCoinStock(thresholds: List<StockThreshold>): String = "SM" + thresholdsToString(thresholds)

    /** [ SB] Scorte banconote: 6 tagli nell'ordine 5 10 20 50 100 200. */
    fun setBanknoteStock(thresholds: List<StockThreshold>): String = "SB" + thresholdsToString(thresholds)

    private fun thresholdsToString(list: List<StockThreshold>): String {
        require(list.size == 6) { "Servono esattamente 6 soglie (una per taglio)" }
        return list.joinToString("")
    }

    /** [ EM] Abilita/disabilita tagli monete: 6 tagli 0,05 0,10 0,20 0,50 1,00 2,00. */
    fun enableCoins(toggles: List<DenominationToggle>): String = "EM" + togglesToString(toggles)

    /** [ EB] Abilita/disabilita tagli banconote: 6 tagli 5 10 20 50 100 200. */
    fun enableBanknotes(toggles: List<DenominationToggle>): String = "EB" + togglesToString(toggles)

    private fun togglesToString(list: List<DenominationToggle>): String {
        require(list.size == 6) { "Servono esattamente 6 abilitazioni (una per taglio)" }
        return list.joinToString("")
    }

    // ---- ricariche -------------------------------------------------------

    /** [ RM] senza aggiornamento fondo cassa, [ R3] con aggiornamento. */
    fun reloadCoins(updateCashFloat: Boolean): String = if (updateCashFloat) "R3" else "RM"

    /** [ RB] senza aggiornamento fondo cassa, [ R2] con aggiornamento. */
    fun reloadBanknotes(updateCashFloat: Boolean): String = if (updateCashFloat) "R2" else "RB"

    /** [ RC]/[ RS] ricarica mista; [ VC]/[ VS] la variante che invia i parziali al client. */
    fun reloadMixed(updateCashFloat: Boolean, sendPartials: Boolean = false): String =
        if (sendPartials) {
            if (updateCashFloat) "VS" else "VC"
        } else {
            if (updateCashFloat) "RS" else "RC"
        }

    /** [ FR] Fine ricarica. */
    fun reloadEnd(): String = "FR"

    // ---- stato / servizio ------------------------------------------------

    /** [ ST] Richiesta situazione. */
    fun status(): String = "ST"

    /** [ CL] Pulisce il display (nessuna risposta prevista). */
    fun clearDisplay(): String = "CL"

    /** [ RI] Riavvia il pagAmico. */
    fun reboot(): String = "RI"

    /** [ LO] Rinvio dell'ultimo JSON trasmesso. */
    fun lastJson(): String = "LO"

    /** [ SR] Rimuove l'immagine temporanea. */
    fun removeTempImage(): String = "SR"

    // ---- POS -------------------------------------------------------------

    /** [ PL] Ultima transazione POS: PLT ristampa lo scontrino, PLF no. */
    fun posLastTransaction(reprintReceipt: Boolean): String = if (reprintReceipt) "PLT" else "PLF"

    /** [ PR] Totali POS. */
    fun posTotals(): String = "PR"

    /** [ PS] Chiusura giornaliera POS. */
    fun posDailyClose(): String = "PS"

    /**
     * [ PZ] Riavvio POS. NB: il manuale rev. 2.33 elenca PZ nella tabella comandi ma nel
     * dettaglio del par. 2.30 riporta "PR": PZ e' il codice della tabella riepilogativa.
     */
    fun posReboot(): String = "PZ"

    /** [ PP] Primo DLL POS (ricarica certificati). */
    fun posFirstDll(): String = "PP"

    // ---- movimenti -------------------------------------------------------

    /** [ MV] Elenco movimenti: MV + inizio + fine (yyyy/MM/dd HH:mm) + causale a 3 cifre. */
    fun movements(from: LocalDateTime, to: LocalDateTime, cause: String = MovementCause.ALL): String {
        require(cause.length == 3 && cause.all { it.isDigit() }) { "La causale deve essere di 3 cifre (es. \"000\")" }
        return "MV" + from.format(MOVEMENT_FORMAT) + to.format(MOVEMENT_FORMAT) + cause
    }

    /** [ MI] Movimento per Id: MI + 9 cifre. */
    fun movementById(id: Long): String = "MI" + fixed(id, 9)
}
