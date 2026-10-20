plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.gardas.fm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gardas.fm"
        minSdk = 26
        targetSdk = 34
        // Sürüm numarası = GitHub derleme sırası: uygulama güncellemeyi bununla anlar (Updater.kt)
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "2.$run"
    }

    // Sabit imza anahtarı: her derleme AYNI imzayla çıkar, böylece yeni APK eskisinin üstüne kurulabilir (otomatik güncelleme)
    signingConfigs {
        create("gardas") {
            storeFile = file("gardas.keystore")
            storePassword = "gardasfm"
            keyAlias = "gardas"
            keyPassword = "gardasfm"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false          // NewPipe kütüphanesi için kapalı (kural gerektirir); yine de debug'dan çok daha hızlı
            signingConfig = signingConfigs.getByName("gardas")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true   // NewPipeExtractor için gerekli
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST",
                "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.4")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // YouTube'dan arama + ses linki çıkaran kütüphane (NewPipe'ın çekirdeği)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
}
