package it.payprint.pagamico.response

import it.payprint.pagamico.client.PagAmicoErrorList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * Risposta JSON del pagAmico (protocollo TCP-IP rev. 2.33 / FW 8.72).
 *
 * Il parsing e' difensivo: le chiavi vengono normalizzate (minuscolo, senza underscore e spazi)
 * perche' documentazione e firmware usano nomi diversi per lo stesso campo:
 * `serialNumber`/`sN`, `committedAmount`/`committedAmout`, `PosFinancial...`/`posFinancial...`,
 * `" AmountResettedBanknotesInBTA"` con spazio iniziale.
 *
 * ATTENZIONE: gli importi in RISPOSTA sono in EURO, quelli nei comandi in CENTESIMI.
 */
class PagAmicoResponse private constructor(
    val rawJson: String,
    val fields: Map<String, JsonElement>
) {

    val response: String? = str("response")
    val amountRequested: BigDecimal? = dec("amountrequested")
    val amountToCollect: BigDecimal? = dec("amounttocollect")
    val collectedAmount: BigDecimal? = dec("collectedamount")
    val collectedCoins: BigDecimal? = dec("collectedcoins")
    val collectedBanknotes: BigDecimal? = dec("collectedbanknotes")

    /** Importo NON erogato come resto per mancanza di tagli: va segnalato all'operatore. */
    val amountUnpaid: BigDecimal? = dec("amountunpaid")

    val changeCoins: BigDecimal? = dec("changecoins")
    val changeBanknotes: BigDecimal? = dec("changebanknotes")
    val amountPaid: BigDecimal? = dec("amountpaid")

    val errorCode: String? = str("errorcode")
    val errorType: String? = str("errortype")

    /** Stato macchina, formato E + 4 cifre: vedi [PagAmicoErrorList]. */
    val errorList: String? = str("errorlist")

    val coins: IntArray = intArray("coins")
    val coinsLimits: IntArray = intArray("coinslimits")
    val bankNotes: List<IntArray> = intMatrix("banknotes")
    val bankNotesBta: IntArray = intArray("banknotesbta")
    val bankNotesInStock: IntArray = intArray("banknotesinstock")
    val coinsInStock: IntArray = intArray("coinsinstock")

    val firmwareVersion: Double? = dec("firmwarevers")?.toDouble()

    /** Matricola: "sN" dal FW 8.71, "serialNumber" nei firmware precedenti. */
    val serialNumber: String? = str("sn", "serialnumber")

    val changeReturn: BigDecimal? = dec("changereturn")

    /** "TRUE" se l'incasso e' stato interrotto dal pannello del pagAmico. */
    val halted: String? = str("halted")

    val isHalted: Boolean get() = halted.equals("TRUE", ignoreCase = true)

    /**
     * Scontrino / messaggio di fine transazione POS. Contiene caratteri di controllo ASCII
     * come sequenze uXXXX: STX (u0002) apre il blocco, ETX (u0003) lo chiude, ETB (u0017) chiude il frame.
     */
    val posFinancialTransactionEndResponseMessage: String? = str("posfinancialtransactionendresponsemessage")

    /** 0 = nessun problema, 9 = probabile banconota doppia, n = numero banconote spostate in BTA. */
    val noteCollectedBta: Int? = dec("notecollectedbta")?.toInt()

    /** Modello: 2B, 3S, 4B, 3C. */
    val typePagAmico: String? = str("typepagamico")

    val committedAmount: BigDecimal? = dec("committedamount", "committedamout")
    val amountResettedBanknotesInBta: BigDecimal? = dec("amountresettedbanknotesinbta")
    val amountBanknotesInBta: BigDecimal? = dec("amountbanknotesinbta")

    /** Id univoco del movimento nel database SQLite del pagAmico. */
    val id: Long? = dec("id")?.toLong()

    val posTot1: BigDecimal? = dec("postot1")
    val posTot2: BigDecimal? = dec("postot2")

    /** Movimenti restituiti dai comandi MV / MI (chiave "root"). */
    val movements: List<PagAmicoMovement> =
        (fields["root"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.map { PagAmicoMovement.fromJson(it) }
            ?: emptyList()

    val isAck: Boolean get() = response == "OK"
    val isPartial: Boolean get() = response == "p"
    val isError: Boolean get() = response == "ER"

    /** Stato macchina decodificato dal campo errorList. */
    val status: PagAmicoErrorList get() = PagAmicoErrorList.parse(errorList)

    /** Monete disponibili per taglio (chiave = centesimi: 5, 10, 20, 50, 100, 200). */
    val coinsByDenomination: Map<Int, Int> get() = mapCoins(coins)

    /** Fondo cassa monete per taglio (chiave = centesimi). */
    val coinsInStockByDenomination: Map<Int, Int> get() = mapCoins(coinsInStock)

    /** Soglie minime monete per taglio (chiave = centesimi). */
    val coinLimitsByDenomination: Map<Int, Int> get() = mapCoins(coinsLimits)

    /** Fondo cassa banconote per taglio (chiave = euro: 5, 10, 20, 50, 100, 200). */
    val bankNotesInStockByDenomination: Map<Int, Int>
        get() = buildMap {
            for (i in 1 until minOf(NOTE_EURO_BY_STOCK_INDEX.size, bankNotesInStock.size)) {
                put(NOTE_EURO_BY_STOCK_INDEX[i], bankNotesInStock[i])
            }
        }

    /** Banconote nel cassetto BTA per taglio (chiave = euro). */
    val bankNotesBtaByDenomination: Map<Int, Int>
        get() = buildMap {
            for (i in 2 until minOf(NOTE_EURO_BY_BTA_INDEX.size, bankNotesBta.size)) {
                put(NOTE_EURO_BY_BTA_INDEX[i], bankNotesBta[i])
            }
        }

    /**
     * Cassetti banconote. I tagli possono essere in ordine non crescente e duplicati:
     * leggere SEMPRE il taglio da [BanknoteDrawer.value], mai dedurlo dalla posizione.
     */
    val drawers: List<BanknoteDrawer>
        get() = bankNotes.mapIndexed { i, row -> BanknoteDrawer(i, row) }

    /**
     * Banconote effettivamente disponibili nei riciclatori, sommate per taglio.
     * E' l'unico modo corretto di leggerle: i cassetti non sono in ordine e lo stesso taglio
     * puo' comparire in piu' cassetti (guida Dev Kit, cap. 9.3).
     */
    val banknotesAvailableByDenomination: Map<Int, Int>
        get() = buildMap {
            drawers.filter { it.isConfigured }.forEach { d ->
                put(d.value, getOrDefault(d.value, 0) + d.quantity)
            }
        }

    // ---------------------------------------------------------------- helper interni

    private fun str(vararg keys: String): String? {
        for (k in keys) {
            val e = fields[k] ?: continue
            if (e is JsonNull) return null
            val p = e as? JsonPrimitive ?: return e.toString()
            return p.content
        }
        return null
    }

    private fun dec(vararg keys: String): BigDecimal? {
        for (k in keys) {
            val p = fields[k] as? JsonPrimitive ?: continue
            if (p is JsonNull) continue
            val text = p.content
            if (text.isBlank()) continue
            val parsed = runCatching { BigDecimal(text) }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun intArray(key: String): IntArray {
        val arr = fields[key] as? JsonArray ?: return IntArray(0)
        return IntArray(arr.size) { i ->
            (arr[i] as? JsonPrimitive)?.content?.toDoubleOrNull()?.let { Math.round(it).toInt() } ?: 0
        }
    }

    private fun intMatrix(key: String): List<IntArray> {
        val arr = fields[key] as? JsonArray ?: return emptyList()
        return arr.filterIsInstance<JsonArray>().map { row ->
            IntArray(row.size) { i ->
                (row[i] as? JsonPrimitive)?.content?.toDoubleOrNull()?.let { Math.round(it).toInt() } ?: 0
            }
        }
    }

    override fun toString(): String =
        "response=$response errorCode=$errorCode errorType=$errorType errorList=$errorList " +
            "collected=$collectedAmount unpaid=$amountUnpaid"

    companion object {
        private val COIN_CENTS_BY_INDEX = intArrayOf(0, 1, 2, 5, 10, 20, 50, 100, 200, 0)
        private val NOTE_EURO_BY_STOCK_INDEX = intArrayOf(0, 5, 10, 20, 50, 100, 200)
        private val NOTE_EURO_BY_BTA_INDEX = intArrayOf(0, 0, 5, 10, 20, 50, 100, 200)

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /** Normalizza la chiave: trim, rimozione di underscore e spazi, minuscolo. */
        private fun normalize(key: String): String =
            key.trim().replace("_", "").replace(" ", "").lowercase()

        private fun mapCoins(arr: IntArray): Map<Int, Int> = buildMap {
            for (i in 1 until minOf(COIN_CENTS_BY_INDEX.size - 1, arr.size)) {
                val cents = COIN_CENTS_BY_INDEX[i]
                if (cents > 0) put(cents, arr[i])
            }
        }

        /** Restituisce null se il testo non e' un oggetto JSON valido. */
        fun tryParse(text: String): PagAmicoResponse? {
            if (text.isBlank()) return null
            return runCatching {
                val root = json.parseToJsonElement(text) as? JsonObject ?: return null
                val fields = root.entries.associate { (k, v) -> normalize(k) to v }
                PagAmicoResponse(text, fields)
            }.getOrNull()
        }
    }
}

