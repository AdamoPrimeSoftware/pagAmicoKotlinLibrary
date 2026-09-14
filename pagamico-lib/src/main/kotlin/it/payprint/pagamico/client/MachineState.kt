package it.payprint.pagamico.client

/** Stato macchina restituito in errorType quando errorCode = "E100". */
enum class MachineState(val code: Int) {
    UNKNOWN(-1),
    OUT_OF_SERVICE(0),
    OK(1),
    STARTING(2),
    SETUP_OR_MAINTENANCE(3),
    REBOOTING(5),
    BUSY(99)
}