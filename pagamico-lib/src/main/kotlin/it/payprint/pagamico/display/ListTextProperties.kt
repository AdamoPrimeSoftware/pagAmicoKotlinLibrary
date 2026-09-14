package it.payprint.pagamico.display

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Proprieta' grafiche di un elemento della lista ([ TS]). */
data class ListTextProperties(
    val text: String = "",
    val backgroundColor: String = "FFFFFFFF",
    val textColor: String = "FF000000",
    val fontSize: Int = 14,
    val fontStyle: FontStyle = FontStyle.NORMAL,
    val alignment: TextAlignment = TextAlignment.LEFT
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("tX", text)
        put("bC", backgroundColor)
        put("tC", textColor)
        put("fZ", fontSize)
        put("fS", fontStyle.code)
        put("aL", alignment.code)
    }
}