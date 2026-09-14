package it.payprint.pagamico.exceptions

import it.payprint.pagamico.client.PagAmicoErrorCodes
import it.payprint.pagamico.client.PagAmicoFrame

/** Errore del pagAmico o comando non valido. */
open class PagAmicoException(
    message: String,
    val frame: PagAmicoFrame? = null
) : Exception(message) {

    val errorCode: String? get() = frame?.json?.errorCode
    val errorTypeValue: String? get() = frame?.json?.errorType

    companion object {
        fun fromFrame(frame: PagAmicoFrame): PagAmicoException {
            val json = frame.json
            if (json != null) {
                val code = json.errorCode.orEmpty()
                val type = json.errorType
                val message = if (code.isEmpty()) {
                    "pagAmico ha risposto ER (errorType=$type)"
                } else {
                    "pagAmico ha risposto ER: $code - ${PagAmicoErrorCodes.describe(code)}" +
                        if (type.isNullOrEmpty()) "" else " (errorType=$type)"
                }
                return PagAmicoException(message, frame)
            }
            return PagAmicoException("pagAmico ha risposto: ${frame.raw}", frame)
        }
    }
}