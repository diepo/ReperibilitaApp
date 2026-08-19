import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// keystore.properties (alla radice del progetto, MAI da versionare) contiene percorso e password
// del keystore di firma "release", usato per generare l'AAB caricabile su Play Console. Assente =
// build release semplicemente non firmata (utile per compilare senza avere il keystore a portata,
// es. su una macchina diversa): assembleRelease/bundleRelease falliranno solo se effettivamente
// invocati senza queste credenziali disponibili.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
val hasKeystoreProperties = keystorePropertiesFile.exists()
if (hasKeystoreProperties) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "it.reperibilita.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.reperibilita.app"
        // minSdk 26 richiesto da TelephonyManager.sendUssdRequest (percorso device gestito)
        minSdk = 26
        targetSdk = 34
        versionCode = 44
        versionName = "1.8.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasKeystoreProperties) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasKeystoreProperties) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Necessario per alcune librerie (java.time, dipendenze mail) su minSdk < 26 non serve qui
        // ma teniamo il desugaring attivo per compatibilita' futura.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            // android-mail e android-activation (JavaMail) spediscono gli stessi file di metadati
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE.txt"
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // --- Core / UI (Jetpack Compose) ---
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // --- Background scheduling ---
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // --- Persistenza log (Room) ---
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // --- Config sicura (credenziali cifrate) ---
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // --- Rete / Microsoft Graph ---
    // Le chiamate Graph usate qui (site/drive lookup, Workbook range, sendMail) hanno risposte
    // dalla forma molto eterogenea (celle Excel di tipo misto: stringa/numero/data seriale).
    // Si usa OkHttp + org.json (incluso in Android) invece di Retrofit+Moshi per gestirle senza
    // dover modellare DTO per ogni variante di cella; Moshi resta solo per serializzare AppConfig.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    // --- Auth Entra ID: login delegato interattivo (MSAL) ---
    implementation("com.microsoft.identity.client:msal:5.2.0")

    // --- Excel: lettura/scrittura file locale ---
    // Apache POI viene evitato di proposito: dipende da javax.xml.stream (StAX) assente
    // di default su Android e causa NoClassDefFoundError a runtime. fastexcel usa SAX,
    // presente nella piattaforma Android, ed e' molto piu' leggero.
    implementation("org.dhatim:fastexcel:0.17.0")
    implementation("org.dhatim:fastexcel-reader:0.17.0")

    // --- Invio email via SMTP (fallback a Graph Mail) ---
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
