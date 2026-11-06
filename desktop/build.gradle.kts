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

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass = "com.xmrip.desktop.MainKt"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}


// Interface web minimale (app macOS, tools/mac-app) : boutons qui lancent les mêmes commandes que `:desktop:run`.
tasks.register<JavaExec>("runWeb") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.xmrip.desktop.WebKt"
}
