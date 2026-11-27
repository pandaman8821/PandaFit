// ============================================================================
//  Panda Fit - app/build.gradle.kts
//  Target: Android 15 (API 35)  |  compileSdk 35  |  minSdk 26
//  Stack: Kotlin + Jetpack Compose (Material 3) + Firebase + Health Connect
//         + Google Gemini (cliente unificado REST v1beta, sin dependencias
//           de SDK de IA que caducan cada pocos meses).
// ============================================================================
// ---------------------------------------------------------------------------
//  Firebase es OPCIONAL para compilar: el plugin de Google Services se aplica
//  solo si existe app/google-services.json (lo pegas desde tu consola Firebase).
//  Sin el, Panda Fit compila y funciona en modo local + datos simulados.
// ---------------------------------------------------------------------------
val firebaseConfigured = rootProject.extra["pandaFirebaseConfigured"] == true
if (firebaseConfigured) {
    apply(plugin = "com.google.gms.google-services")
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.pandafit.app"

    // compileSdk 36 es OBLIGATORIO para las versiones actuales de Health Connect
    // y AndroidX Activity (lo exige su AAR metadata). El comportamiento en
    // ejecucion lo decide targetSdk, que SI se queda en 35 = Android 15.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pandafit.app"
        minSdk = 26
        // Target: Android 15 (API 35). La app se comporta como Android 15 en
        // todos los dispositivos, incluidos los que corren Android 16/17.
        targetSdk = 35
        // Numeracion de compilaciones (desde 2026-11-19): 0.0.1, 0.0.2 ... 0.0.10,
        // luego 0.1.1 ... 0.1.10, 0.2.1 ... y asi sucesivamente (tras 0.9.10 -> 1.0.1).
        // SUBIR "compilacion" en 1 en cada APK que se entregue: el APK se llama
        // PandaFit-v<versionName>.apk. versionCode sigue creciendo (30 + compilacion)
        // para que Android acepte instalar encima de las versiones anteriores (v1-v30).
        // 0.1.8: versionCode y versionName van ESCRITOS A MANO porque F-Droid los
        // lee del archivo (no sabe calcularlos). Al subir "compilacion", cambia
        // tambien las dos lineas de abajo; si no coinciden, el build se para.
        val compilacion = 18
        versionCode = 48
        versionName = "0.1.8"
        val k = compilacion - 1
        check(versionCode == 30 + compilacion && versionName == "${k / 100}.${(k / 10) % 10}.${k % 10 + 1}") {
            "versionCode/versionName no coinciden con compilacion = $compilacion"
        }

        // Health Connect 1.1 exige minSdk 26 y permite declarar los permisos
        // de salud como "rationale" en el manifiesto.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // 0.1.8: DOS versiones de la app.
    //   completa -> la de siempre (Firebase + ubicacion de Google Play Services).
    //   fdroid   -> 100 % software libre para F-Droid: sin Firebase ni Google
    //               Play Services (sin nube; ubicacion propia de Android).
    // Las dos comparten todo el codigo de src/main; solo cambia
    // servicios/ServiciosSabor.kt (src/completa y src/fdroid).
    flavorDimensions += "distribucion"
    productFlavors {
        create("completa") {
            dimension = "distribucion"
            isDefault = true
            // El release de esta version se firma con la clave de depuracion para
            // poder instalarlo en pruebas sin keystore propio. La de F-Droid sale
            // SIN firmar: F-Droid la firma con su propia clave.
            signingConfig = signingConfigs.getByName("debug")
        }
        create("fdroid") {
            dimension = "distribucion"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/*.kotlin_module"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        // Avisos desactivados A PROPOSITO, con motivo:
        //   OldTargetApi                        -> targetSdk 35 (Android 15) es un requisito del proyecto.
        //   InvalidFragmentVersionForActivityResult -> falso positivo: no se usa Fragment en ningun sitio
        //                                        (todo es Compose + ComponentActivity).
        //   ObsoleteSdkInt                      -> el calificador mipmap-anydpi-v26 es obligatorio para
        //                                        <adaptive-icon> (AAPT2 falla sin el).
        //   UseTomlInstead                      -> el desugaring se declara con su propia configuracion
        //                                        (coreLibraryDesugaring), no admite alias del catalogo.
        //   GradleDependency / NewerVersionAvailable / AndroidGradlePluginVersion -> se avisa de versiones
        //                                        mas nuevas; el proyecto fija las versiones verificadas.
        disable += setOf(
            "OldTargetApi",
            "InvalidFragmentVersionForActivityResult",
            "ObsoleteSdkInt",
            "UseTomlInstead",
            "GradleDependency",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion"
        )
    }
}

dependencies {
    // --- AndroidX base -------------------------------------------------------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // --- Compose Material 3 (Liquid Glass) -----------------------------------
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    debugImplementation(libs.compose.ui.tooling)

    // --- Corrutinas + serializacion -----------------------------------------
    implementation(libs.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // --- SOLO version "completa" (no libres; la de F-Droid no los lleva) -----
    // Firebase: nube aunque se caiga el Bluetooth.
    "completaImplementation"(platform("com.google.firebase:firebase-bom:34.5.0"))
    "completaImplementation"("com.google.firebase:firebase-firestore")
    "completaImplementation"("com.google.firebase:firebase-auth")
    "completaImplementation"("com.google.firebase:firebase-analytics")
    "completaImplementation"("com.google.firebase:firebase-crashlytics")
    // GPS de alta precision de Google Play Services.
    "completaImplementation"("com.google.android.gms:play-services-location:21.3.0")
    "completaImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    // --- Health Connect (datos reales de salud; AndroidX, Apache 2.0) --------
    implementation(libs.health.connect.client)

    // --- Mapa de calles del entreno (OpenStreetMap, gratis y sin clave) -----
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // --- Desugaring (java.time en API 26) -----------------------------------
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // --- Test ----------------------------------------------------------------
    testImplementation(libs.junit)
}

// Desugaring de java.time para minSdk 26 (lo usa el analisis de sueno/pasos).
