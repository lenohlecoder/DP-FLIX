plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.dpflix.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dpflix.android"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // Étape 2c-3 : signature release.
        // En local (ou si les variables ne sont pas définies), ce signingConfig
        // reste incomplet volontairement : assembleRelease échoue alors avec un
        // message clair plutôt que de produire un APK non signé.
        // Sur Codemagic, ces 4 variables sont injectées automatiquement par la
        // fonctionnalité "Code signing identities" (cf. codemagic.yaml + README).
        create("release") {
            val storeFilePath = System.getenv("CM_KEYSTORE_PATH")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("CM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CM_KEY_ALIAS")
                keyPassword = System.getenv("CM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Le check "NullSafeMutableLiveData" (détecteur NonNullableMutableLiveDataDetector)
        // plante systématiquement pendant l'analyse Lint avec cette combinaison AGP/Kotlin
        // (bug connu dans Lint ou l'une de ses dépendances, cf. log de build). Le projet
        // n'utilise pas androidx.lifecycle.LiveData (Room/DataStore uniquement), donc ce
        // check n'a de toute façon aucune pertinence ici.
        disable += "NullSafeMutableLiveData"
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)

    // Compose (mobile)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // Compose for TV
    implementation(libs.tv.foundation)
    implementation(libs.tv.material)

    // Lecteur vidéo (ExoPlayer / Media3)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.exoplayer.smoothstreaming)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.database)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)

    // Persistance
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    // Module téléchargement Films & Séries — FilmDownloadWorker (reprise après
    // redémarrage app/process, retries, notifications de progression).
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Réseau
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Images (logo de la chaîne dans l'OSD, étape 8a)
    implementation(libs.coil.compose)

    // Module TV : WebView desktop + curseur D-pad (Films & Séries, mode TV)
    implementation(project(":tvflix"))
    implementation(project(":tvbro"))

}

