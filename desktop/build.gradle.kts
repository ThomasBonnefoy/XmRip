plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

// Banc de test PC : compile le dossier protocol/ de l'app tel quel (aucune copie) et le branche
// sur le pont RFCOMM (tools/pc-bridge) au lieu d'une socket Bluetooth Android.
sourceSets {
    main {
        kotlin.srcDir("../app/src/main/java/com/xmrip/app/protocol")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

application {
    mainClass = "com.xmrip.desktop.MainKt"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
