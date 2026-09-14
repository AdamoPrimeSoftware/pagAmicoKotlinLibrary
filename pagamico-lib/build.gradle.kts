plugins {
    kotlin("jvm")
    application
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

kotlin {
    jvmToolchain(17)
}

application {
    // gradle :pagamico-lib:run                      -> test di autoverifica offline
    // gradle :pagamico-lib:run -PmainClass=...DemoKt -> demo contro una macchina reale
    mainClass.set(providers.gradleProperty("mainClass").getOrElse("it.payprint.pagamico.test.SelfTestKt"))
}

/*
 * NOTE PER ANDROID / KMP
 * ----------------------
 * Il codice non usa API JVM-only oltre a java.net.Socket, java.math.BigDecimal e java.time,
 * quindi funziona as-is su Android (minSdk 26 per java.time, oppure core library desugaring).
 * Per un modulo Android aggiungere:
 *
 *   android { defaultConfig { minSdk = 26 } }
 *   dependencies {
 *       implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
 *       implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
 *   }
 *   <uses-permission android:name="android.permission.INTERNET" />
 *
 * Non serve il plugin kotlinx-serialization: la libreria usa solo l'API JsonElement a runtime.
 */
