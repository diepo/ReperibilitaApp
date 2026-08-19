# Moshi / Retrofit
-keep class it.reperibilita.app.data.graph.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn okhttp3.**
-dontwarn retrofit2.**

# MSAL
-keep class com.microsoft.identity.client.** { *; }
-dontwarn com.microsoft.identity.client.**

# JavaMail (android-mail)
-keep class javax.mail.** { *; }
-keep class com.sun.mail.** { *; }
-dontwarn javax.mail.**
-dontwarn com.sun.mail.**

# fastexcel
-dontwarn org.dhatim.fastexcel.**

# Percorsi opzionali mai esercitati a runtime su Android, ma referenziati da dipendenze
# transitive: R8 (AGP recenti) fallisce la build per "classi mancanti" anche se sono innocue,
# a meno di silenziarle esplicitamente qui.
# - javax.xml.stream (StAX) non esiste sulla piattaforma Android per design: fastexcel la usa
#   solo in un fallback (aalto-xml) che qui non viene mai raggiunto (vedi anche il commento in
#   build.gradle.kts sul perche' si e' scelto fastexcel invece di Apache POI proprio per questo).
-dontwarn javax.xml.stream.**
-dontwarn org.codehaus.stax2.**
-dontwarn com.fasterxml.aalto.**
# - dipendenze telemetry/analisi statica trascinate da MSAL, mai istanziate a runtime.
-dontwarn com.google.auto.value.**
-dontwarn io.opentelemetry.**
-dontwarn edu.umd.cs.findbugs.annotations.**
