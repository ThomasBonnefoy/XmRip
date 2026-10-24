import java.util.Properties

plugins {
    id("com.android.application")
}

// Config de signature release : lue depuis keystore.properties (racine du repo),
// fichier local ignoré par git. S'il est absent (contributeurs), le build release
// reste non signé mais le build debug fonctionne normalement.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(keystorePropertiesFile.inputStream())
    }
}

android {
    namespace = "com.xmrip.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.xmrip.app"
        minSdk = 24
        targetSdk = 37
        versionCode = 3
        versionName = "0.3.0"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists() &&
            file(keystoreProperties.getProperty("storeFile")).exists()
        ) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}