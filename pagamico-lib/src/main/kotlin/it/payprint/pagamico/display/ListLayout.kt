package it.payprint.pagamico.display

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Struttura della lista da inviare con [TS]. */
data class ListLayout(
    val title: ListTextProperties,
    /** Colonne "a".."e". */
    val body: Map<String, ListCell> = emptyMap(),
    val footerBackground: String = "FFE0E0E0",
    /** Sezioni "description", "total1", "total2", "total3". */
    val footer: Map<String, ListCell> = emptyMap(),
    val exitButton: ListTextProperties = ListTextProperties("Esci", "FFFF0000", "FFFFFFFF", 16, FontStyle.NORMAL, TextAlignment.CENTER),
    val printButton: ListTextProperties = ListTextProperties("Stampa", "FF0000FF", "FFFFFFFF", 16, FontStyle.NORMAL, TextAlignment.CENTER),
    val rowHeight: Int = 20,
    /** 0 = corpo con 4 colonne (a=data, b=descrizione, c=dare, d=avere); 1 = colonne aggiuntive. */
    val listType: Int = 0
) {
    /** Serializza in JSON compatto nella forma attesa: `{"listView": { ... }}`. */
    fun toCompactJson(): String {
        val listView = buildJsonObject {
            put("titleProperties", title.toJson())
            put("bodyProperties", buildJsonObject { body.forEach { (k, v) -> put(k, v.toJson()) } })
            put(
                "footerProperties",
                buildJsonObject {
                    put("bC", footerBackground)
                    footer.forEach { (k, v) -> put(k, v.toJson()) }
                }
            )
            put("exitButton", exitButton.toJson())
            put("printButton", printButton.toJson())
            put("rowHeight", rowHeight)
            put("listType", listType)
        }
        return buildJsonObject { put("listView", listView) }.toString()
    }
}