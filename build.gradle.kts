// :pagamico-lib  libreria client (JVM/Android), nessuna dipendenza da UI
//
// Comandi utili:
//   gradle :pagamico-lib:run                  esegue i test di autoverifica offline
//   gradle :pagamico-lib:run -PmainClass=it.payprint.pagamico.demo.DemoKt --args="192.168.1.29 9100 1.50"

plugins {
    kotlin("jvm") version "2.2.20" apply false
}

allprojects {
    group = "it.payprint"
    version = "1.0.0"

    repositories {
        mavenCentral()
        google()
    }
}
