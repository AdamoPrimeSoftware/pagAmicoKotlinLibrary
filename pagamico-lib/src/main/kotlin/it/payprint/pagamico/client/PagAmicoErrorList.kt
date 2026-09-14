package it.payprint.pagamico.client

/**
 * Parser della stringa "errorList" (formato E + 4 cifre, es. "E0090").
 * Cifra 1 = esito pagamento, 2 = scorta monete, 3 = scorta banconote, 4 = cassetto BTA.
 */
class PagAmicoErrorList private constructor(
    val raw: String?,
    /** 0 = ok; 1 = importo superiore alla disponibilita' di monete; 2 = importo non erogabile. */
    val paymentDigit: Int,
    /** 0 = ok; 1 = monete sottoscorta; 2 = troppe monete; 9 = monete esaurite. */
    val coinsDigit: Int,
    /** 0 = ok; 1 = sottoscorta; 2 = troppe; 5 = entrambi i cassetti vuoti; 6 = un cassetto vuoto; 9 = un taglio esaurito. */
    val banknotesDigit: Int,
    /** 0 = ok; 1 = BTA prossimo al riempimento; 2 = BTA pieno. */
    val btaDigit: Int
) {
    val coinsBelowMinimum: Boolean get() = coinsDigit == 1
    val tooManyCoins: Boolean get() = coinsDigit == 2
    val coinsEmpty: Boolean get() = coinsDigit == 9
    val banknotesBelowMinimum: Boolean get() = banknotesDigit == 1
    val tooManyBanknotes: Boolean get() = banknotesDigit == 2
    val banknotesEmpty: Boolean get() = banknotesDigit == 9
    val btaAlmostFull: Boolean get() = btaDigit == 1
    val btaFull: Boolean get() = btaDigit == 2

    val requiresOperatorAttention: Boolean
        get() = paymentDigit != 0 || coinsDigit != 0 || banknotesDigit != 0 || btaDigit != 0

    /** Descrizioni leggibili delle sole condizioni anomale. */
    fun warnings(): List<String> = buildList {
        when (paymentDigit) {
            0 -> {}
            1 -> add("Importo superiore alla disponibilita' di monete")
            2 -> add("Importo non erogabile")
            else -> add("Errore pagamento (codice $paymentDigit)")
        }
        when (coinsDigit) {
            1 -> add("Monete sottoscorta")
            2 -> add("Troppe monete: eseguire scarico monete")
            9 -> add("Monete esaurite")
        }
        when (banknotesDigit) {
            1 -> add("Banconote sottoscorta")
            2 -> add("Troppe banconote: eseguire scarico banconote")
            5 -> add("Entrambi i cassetti vuoti ma numero banconote > 0")
            6 -> add("Uno dei cassetti vuoto ma numero banconote > 0")
            9 -> add("Uno dei tagli di banconote esaurito")
        }
        when (btaDigit) {
            1 -> add("Cassetto BTA prossimo al riempimento")
            2 -> add("Cassetto BTA pieno")
        }
    }

    override fun toString(): String = raw ?: "E0000"

    companion object {
        val EMPTY = PagAmicoErrorList(null, 0, 0, 0, 0)

        fun parse(errorList: String?): PagAmicoErrorList {
            if (errorList.isNullOrBlank()) return EMPTY
            val s = errorList.trim()
            if (s.length < 5 || !s.startsWith("E", ignoreCase = true)) {
                return PagAmicoErrorList(errorList, 0, 0, 0, 0)
            }
            fun d(i: Int) = if (i < s.length && s[i].isDigit()) s[i] - '0' else 0
            return PagAmicoErrorList(errorList, d(1), d(2), d(3), d(4))
        }
    }
}