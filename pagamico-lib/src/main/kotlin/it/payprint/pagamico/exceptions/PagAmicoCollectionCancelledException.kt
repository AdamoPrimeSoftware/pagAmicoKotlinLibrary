package it.payprint.pagamico.exceptions

import it.payprint.pagamico.response.PagAmicoResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred

/**
 * Lanciata a chi aveva avviato un incasso quando la sua coroutine viene cancellata. La libreria ha gia'
 * chiesto l'[AN] alla macchina, che pero' puo' aver incassato: **[outcome] porta l'esito dell'incasso**
 * (di norma [AN], ma [IN] o [CM] se il cliente o un commit hanno chiuso prima), lo stesso che in C#
 * restituisce `CollectCashAsync` annullato dal token.
 *
 * Il chiamante e' cancellato, quindi va atteso fuori dalla cancellazione:
 * ```
 * catch (e: PagAmicoCollectionCancelledException) {
 *     val esito = withContext(NonCancellable) { e.outcome.await() }
 *     throw e
 * }
 * ```
 * [outcome] fallisce se l'incasso termina senza esito (connessione caduta, timeout). L'esito arriva anche
 * a `onOrphanFrame`: chi lo salva da li' non deve salvarlo due volte.
 */
class PagAmicoCollectionCancelledException(
    val command: String,
    val outcome: Deferred<PagAmicoResponse>
) : CancellationException("Incasso '$command' cancellato dal chiamante: AN richiesto alla macchina")
