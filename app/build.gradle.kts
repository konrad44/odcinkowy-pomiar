import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.matka.opp"
    compileSdk = 36

    defaultConfig {
        applicationId = "pl.matka.opp"
        minSdk = 26
        targetSdk = 36
        // W CI numer buildu rośnie sam (100+ > dawne 3), lokalnie zostaje 3.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = if (build != null) 100 + build else 3
        versionName = "1.3" + (build?.let { ".$it" } ?: "-local")
    }

    // Stały klucz w repo, żeby kolejne APK z CI instalowały się jako aktualizacja
    // (bez odinstalowywania). Aplikacja tylko do użytku własnego.
    signingConfigs {
        create("matka") {
            storeFile = file("matka.keystore")
            storePassword = "matka-opp"
            keyAlias = "matka"
            keyPassword = "matka-opp"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("matka")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("matka")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].assets.srcDir("../data")

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
