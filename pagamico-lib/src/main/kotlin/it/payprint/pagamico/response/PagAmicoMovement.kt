package it.payprint.pagamico.response

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Movimento contabile restituito dai comandi MV / MI. */
class PagAmicoMovement(
    val rawDateTime: String?,
    val dateTime: LocalDateTime?,
    /**
     * Causale: 000 tutte, 001 Incasso, 010 Pagamento, 011/071 Scarico Monete, 021 Ricarica Monete,
     * 031 Ricarica Banconote, 041/061 Scarico Banconote, 051 Scarico Banconote in BTA, 090 Incasso POS.
     */
    val operationCode: String?,
    val debit: BigDecimal,
    val credit: BigDecimal,
    val change: BigDecimal,
    val coinsAmount: BigDecimal,
    val banknotesAmount: BigDecimal,
    val posAmount: BigDecimal,
    val notDispensedAmount: BigDecimal,
    val cancelled: Boolean
) {
    override fun toString(): String =
        "$rawDateTime cod=$operationCode dare=$debit avere=$credit pos=$posAmount annullato=$cancelled"

    companion object {
        private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun fromJson(o: JsonObject): PagAmicoMovement {
            fun s(name: String): String? = (o[name] as? JsonPrimitive)?.content
            fun d(name: String): BigDecimal =
                (o[name] as? JsonPrimitive)?.content?.let { runCatching { BigDecimal(it) }.getOrNull() } ?: BigDecimal.ZERO

            val raw = s("dataora")
            val parsed = raw?.let { runCatching { LocalDateTime.parse(it, FORMAT) }.getOrNull() }

            return PagAmicoMovement(
                rawDateTime = raw,
                dateTime = parsed,
                operationCode = s("codice_operazione"),
                debit = d("importo_dare"),
                credit = d("importo_avere"),
                change = d("resto"),
                coinsAmount = d("importo_monete"),
                banknotesAmount = d("importo_banconote"),
                posAmount = d("importo_pos"),
                notDispensedAmount = d("importo_non_erogato"),
                cancelled = d("flg_annullato").signum() != 0
            )
        }
    }
}