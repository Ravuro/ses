plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ravuro.telsiz"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ravuro.telsiz"
        // 26: bildirim kanalları ve Notification.Builder çerçevede hazır,
        // uyumluluk kütüphanesine gerek kalmıyor.
        minSdk = 26
        targetSdk = 28
        // build-nosdk.sh ile aynı numarada tutuluyor: daha düşük bir sürümü
        // telefona kurmaya çalışmak "downgrade" hatası veriyor.
        versionCode = 6
        versionName = "1.5"
    }

    buildFeatures {
        // Uygulamada res/ yok: arayüz koddan kuruluyor.
        resValues = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Bağımlılık yok: androidx ve okhttp yerine çerçeve API'leri ve
// elde yazılmış WebSocket istemcisi kullanılıyor.
dependencies {
}
