package it.payprint.pagamico.display

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Payload dati della lista ([ ID]). */
data class ListData(
    val rows: List<ListRow> = emptyList(),
    val footer: ListFooter = ListFooter()
) {
    fun toCompactJson(): String = buildJsonObject {
        put(
            "movimenti",
            buildJsonArray {
                rows.forEach { row ->
                    add(
                        buildJsonObject {
                            putAny("a", row.a)
                            putAny("b", row.b)
                            putAny("c", row.c)
                            putAny("d", row.d)
                            putAny("e", row.e)
                        }
                    )
                }
            }
        )
        put(
            "footer",
            buildJsonObject {
                put("desc", footer.description)
                put("tot1", footer.total1)
                put("tot2", footer.total2)
                put("tot3", footer.total3)
            }
        )
    }.toString()


    private fun JsonObjectBuilder.putAny(key: String, value: Any?) {
        if (value == null) return
        val element: JsonElement = when (value) {
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
        put(key, element)
    }

}