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
./gradlew build                  # compila ed esegue i test offline
./gradlew test                   # solo i test offline (task selfTest)
./gradlew :pagamico-lib:run      # stessi test, con l'elenco completo a video
./gradlew :pagamico-lib:run "-PmainClass=it.payprint.pagamico.test.LiveTestKt" "--args=127.0.0.1 9100"
```

Richiede JDK 17. Su Windows usare **JDK 17.0.18 o successivo**: le versioni precedenti non permettono di regolare il keepalive TCP, e una caduta di rete durante un incasso si vedrebbe solo dopo ore (la diagnostica lo segnala alla connessione).

## Pacchetto Maven

```bash
./gradlew :pagamico-lib:publishToMavenLocal
```

Pubblica `it.payprint:pagamico-lib:1.0.0` (jar e sorgenti) in `~/.m2`. Da un altro progetto Gradle: `repositories { mavenLocal() }` e `implementation("it.payprint:pagamico-lib:1.0.0")`. Il jar contiene anche i test e il collaudo, che stanno in `src/main`.

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

Su Android il keepalive TCP resta ai valori di sistema. Su Android: `minSdk 26` (per `java.time`) oppure core library desugaring, permesso `INTERNET` e `kotlinx-coroutines-android`. Non serve il plugin kotlinx-serialization.

## Attenzione all'annullo

Cancellando la coroutine di un incasso, il client manda `AN` alla macchina, ma la macchina può aver già incassato. Il chiamante riceve subito una `PagAmicoCollectionCancelledException` (sottotipo di `CancellationException`) che **porta l'esito in `outcome`**:

```kotlin
try {
    client.collectCash(importo)
} catch (e: PagAmicoCollectionCancelledException) {
    val esito = withContext(NonCancellable) { e.outcome.await() }   // AN, o IN/CM se chiuso prima
    salva(esito)
    throw e
}
```

In alternativa si annulla con `cancelOperation()`, che restituisce direttamente l'esito. L'esito arriva comunque anche a `onOrphanFrame`: chi salva da entrambe le parti deve evitare il doppio salvataggio.
