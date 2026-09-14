package it.payprint.pagamico.display

import java.math.BigDecimal

/** Piede della lista dati. */
data class ListFooter(
    val description: String = "",
    val total1: BigDecimal = BigDecimal.ZERO,
    val total2: BigDecimal = BigDecimal.ZERO,
    val total3: BigDecimal = BigDecimal.ZERO
)