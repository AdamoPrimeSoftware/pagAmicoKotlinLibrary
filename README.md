# pagAmico — libreria Kotlin

Libreria client (JVM/Android) per la cassa rendiresto **PayPrint pagAmico** (protocollo TCP-IP rev. 2.33, FW 8.72). Usa coroutines e kotlinx-serialization, nessuna dipendenza da UI.

Il modulo `pagamico-lib` contiene la libreria, i 168 test offline di autoverifica e il collaudo contro simulatore o macchina reale. Il banco di prova Compose sta nel repository **pagAmico_Kotlin_Demo**.

## Compilare e testare

Aprire la cartella in IntelliJ IDEA; le configurazioni salvate in `.run/` sono numerate nell'ordine delle prove:

| Configurazione | Cosa fa |
|---|---|
| **1 - Test offline (168)** | test di autoverifica |
| **2 - Collaudo simulatore** | collaudo su `127.0.0.1:9100` (pagAmico Dev Kit → Simulatore → Avvia) |
| **4 - Collaudo macchina reale** | collaudo su `192.168.1.231:9100` |

Da riga di comando:

```bash
./gradlew build
./gradlew :pagamico-lib:run
./gradlew :pagamico-lib:run "-PmainClass=it.payprint.pagamico.test.LiveTestKt" "--args=127.0.0.1 9100"
```

Richiede JDK 17.

## Uso

```kotlin
val client = PagAmicoClient("192.168.1.29")
client.connect()

val stato = client.status()                                 // [ST]
val esito = client.collectCash(BigDecimal("10.50")) { p ->  // [IN]
    println("incassato ${p.collectedAmount}")
}
```

Log su file:

```kotlin
val logger = PagAmicoFileLogger()               // su Android passare context.filesDir
logger.attach(client, scope)
```

Su Android: `minSdk 26` (per `java.time`) oppure core library desugaring, permesso `INTERNET` e `kotlinx-coroutines-android`. Non serve il plugin kotlinx-serialization.

## Attenzione all'annullo

Cancellando la coroutine di un incasso, il client manda `AN` alla macchina, ma l'esito **non** torna al chiamante (che riceve subito `CancellationException`): arriva a `onOrphanFrame`. Per ricevere l'esito dell'annullo usare `cancelOperation()`, oppure ascoltare `onOrphanFrame`.
