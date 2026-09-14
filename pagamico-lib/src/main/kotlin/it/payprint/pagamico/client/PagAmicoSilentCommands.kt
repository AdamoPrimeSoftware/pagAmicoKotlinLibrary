package it.payprint.pagamico.client

/** Comandi che non producono alcuna risposta (guida Dev Kit, cap. 12). */
object PagAmicoSilentCommands {
    private val prefixes = listOf("CL", "DS", "DC", "QA", "CO", "DT", "DG", "TS")

    fun isSilent(command: String?): Boolean =
        !command.isNullOrEmpty() && prefixes.any { command.startsWith(it) }
}