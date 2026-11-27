// ============================================================================
//  Panda Fit - build.gradle.kts (raiz)
//
//  AGP 9 incluye el soporte de Kotlin de forma nativa: NO se aplica el plugin
//  "org.jetbrains.kotlin.android" (el build falla si se anade).
//  Solo se declaran los plugins de Kotlin que SI se necesitan:
//    * kotlin-compose       -> compilador de Compose (obligatorio con Kotlin 2.x)
//    * kotlin-serialization -> JSON tipado para el cliente de Gemini y Firestore
//
//  FIREBASE ES OPCIONAL PARA COMPILAR: el plugin de Google Services solo se
//  activa si existe app/google-services.json. Esto permite compilar, instalar y
//  usar Panda Fit (modo local + datos simulados) antes de crear el proyecto de
//  Firebase en la consola. Al pegar el archivo, la sincronizacion en la nube se
//  activa sola sin tocar nada mas.
// ============================================================================
import java.io.File

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // Version fija: los plugins declarados con `plugins {}` en un subproyecto
    // deben venir de la raiz o del catalogo de plugins.
    id("com.google.gms.google-services") version "4.4.3" apply false
}

// Bandera que lee app/build.gradle.kts para decidir si aplica el plugin de
// Google Services (evita el error "File google-services.json is missing").
extra["pandaFirebaseConfigured"] = File(rootDir, "app/google-services.json").exists()
