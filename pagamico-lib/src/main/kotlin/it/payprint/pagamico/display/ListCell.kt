package it.payprint.pagamico.display

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/** Coppia etichetta/valore di una colonna o di un totale. */
data class ListCell(
    val label: ListTextProperties,
    val value: ListTextProperties = ListTextProperties()
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("label", label.toJson())
        put("value", value.toJson())
    }

    companion object {
        fun of(label: String, fontSize: Int = 14, alignment: TextAlignment = TextAlignment.LEFT) = ListCell(
            label = ListTextProperties(label, "FFF5F5F5", "FF333333", fontSize, FontStyle.NORMAL, alignment),
            value = ListTextProperties("", "FFFFFFFF", "FF000000", fontSize, FontStyle.NORMAL, alignment)
        )
    }
}