# ============================================================================
#  Panda Fit - Reglas ProGuard/R8
#  Mantiene la serializacion de kotlinx (respuestas de Gemini y Firestore)
# ============================================================================
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class com.pandafit.app.**$$serializer { *; }
-keepclassmembers class com.pandafit.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.pandafit.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Firebase / Firestore
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# Health Connect
-keep class androidx.health.connect.client.** { *; }

# Modelos de datos serializados
-keep class com.pandafit.app.data.model.** { *; }

# Corrutinas
-dontwarn kotlinx.coroutines.**
