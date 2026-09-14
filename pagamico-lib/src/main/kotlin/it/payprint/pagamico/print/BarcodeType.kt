package it.payprint.pagamico.print

/**
 * Tipo di barcode ([ PTBCBC]).
 *
 * Sui codici 7 e 8 il manuale Protocollo di stampa 2.00 si contraddice da solo: la tabella del
 * comando a pagina 3 dice 7 = CODE128 e 8 = CODE93, la nota di aggiornamento a pagina 5 dello
 * stesso documento dice 7 = CODE93 e 8 = CODE1128 (refuso per CODE128). Vale la seconda: il
 * catalogo comandi del pagAmico Dev Kit, che e' anche il programma che emula il dispositivo,
 * riporta 7 = CODE93 e 8 = CODE128.
 */
enum class BarcodeType(val code: Int) {
    UPC_A(0),
    UPC_E(1),
    EAN13(2),
    EAN8(3),
    CODE39(4),
    ITF(5),
    CODABAR(6),
    CODE93(7),
    CODE128(8)
}