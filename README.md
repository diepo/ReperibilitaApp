# Reperibilità App

App Android che gestisce automaticamente l'inoltro di chiamata sulla SIM di un telefono dedicato
alla reperibilità, in base a un calendario turni letto da un file Excel (locale o su SharePoint).
Ad ogni cambio turno invia SMS alla persona entrante e a quella uscente, invia mail informative,
scrive lo stato sul file Excel sorgente e, in caso di errore, notifica un numero di servizio via
SMS e una lista di indirizzi via mail.

**Prima di tutto, leggi [docs/SETUP_DEVICE.md](docs/SETUP_DEVICE.md).** Il modo in cui viene
impostato l'inoltro di chiamata dipende in modo sostanziale da come il device viene gestito, ed è
la decisione con il maggior impatto sull'affidabilità del sistema.

## Stato del progetto

Questo repository contiene un progetto Android Studio completo (Kotlin + Jetpack Compose) con
tutti i moduli richiesti implementati:

- lettura calendario da file `.xlsx` locale o da SharePoint (Microsoft Graph Workbook API)
- inoltro chiamata via USSD (percorso privilegiato verificato + fallback dialer)
- invio SMS di notifica (attivazione/fine turno) tramite la SIM del device
- invio email via SMTP o via Microsoft Graph (`sendMail`), a scelta
- autenticazione Entra ID sia app-only (client credentials) sia delegata (login interattivo MSAL)
- log persistente di ogni azione (Room), consultabile dalla GUI
- mail di riepilogo giornaliero con tutte le azioni/log
- GUI con override manuale della reperibilità (imposta un numero a mano)
- write-back dello stato sul file Excel sorgente dopo ogni cambio turno

**Il progetto compila con successo**: `./gradlew assembleDebug` completa senza errori e produce
`app/build/outputs/apk/debug/app-debug.apk`. Non è stato invece possibile testarlo su un device o
emulatore reale in questo ambiente (nessun device disponibile): resta da fare prima di qualunque
uso in produzione. Vedi "Prossimi passi" più sotto.

## Struttura del progetto

```
app/src/main/java/it/reperibilita/app/
  model/            data class (ShiftEntry, AppConfig, ...)
  data/excel/        lettura/scrittura calendario (locale + SharePoint)
  data/auth/          autenticazione Entra (client credentials + MSAL delegato)
  data/graph/          client HTTP verso Microsoft Graph (site/drive/workbook/mail)
  data/mail/           invio email (SMTP / Graph)
  data/sms/            invio SMS via SmsManager
  data/callforwarding/ impostazione inoltro chiamata (USSD)
  data/log/            log persistente (Room)
  data/config/         configurazione cifrata + stato operativo
  domain/              logica di dominio (scheduler turni, orchestrazione cambio turno)
  worker/              WorkManager periodico + riavvio dopo boot
  ui/                  schermate Compose (Stato, Impostazioni, Override, Log)
templates/
  Calendario_Reperibilita.xlsx   file di esempio con lo schema atteso
docs/
  SETUP_DEVICE.md      come ottenere un inoltro chiamata affidabile (leggere per primo)
  SETUP_ENTRA.md        come creare l'App Registration su Entra e i permessi Graph necessari
```

## Build

Requisiti: Android Studio (Koala o successivo) oppure JDK 17 + Android SDK command-line tools.

```bash
./gradlew assembleDebug
```

`minSdk` è 26 (Android 8.0), richiesto da `TelephonyManager.sendUssdRequest`.

Note dalla build verificata in questo ambiente (utili se compili da riga di comando, non da
Android Studio):

- **`local.properties`** contiene `sdk.dir` con il percorso dell'SDK usato in questa sessione
  (`C:\devtools\android-sdk`). Sostituiscilo con il percorso del tuo SDK (Android Studio lo
  rigenera automaticamente al primo sync se lo elimini).
- Il progetto richiede un **repository Maven aggiuntivo** (già incluso in
  `settings.gradle.kts`) per una dipendenza transitiva di MSAL non pubblicata su Maven
  Central/Google: `com.microsoft.device.display:display-mask`, disponibile solo sul
  feed Microsoft Duo SDK.
- Se compili su Windows con un JDK moderno (16+) e ottieni l'errore
  `java.io.IOException: Unable to establish loopback connection` durante l'avvio del daemon
  Gradle, è un problema noto quando i socket AF_UNIX non funzionano correttamente
  sull'ambiente/rete della macchina: imposta la variabile d'ambiente
  `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=<percorso-breve-senza-spazi>` prima di
  lanciare `gradlew`.
- Se il daemon Gradle si "perde" (`Gradle build daemon disappeared unexpectedly`) o crasha con
  errori di memoria nativa, il sistema è probabilmente a corto di memoria virtuale disponibile
  (pagefile quasi esaurito): chiudi altre applicazioni oppure riduci gli heap in
  `gradle.properties` (`org.gradle.jvmargs`, `kotlin.daemon.jvmargs`), già impostati
  conservativamente (768 MB, Serial GC) per adattarsi ad ambienti con poca memoria libera.

## Configurazione (dalla GUI dell'app)

Tutte le opzioni sono nella schermata **Impostazioni**:

1. **Sorgente calendario**: file `.xlsx` locale (selezionato tramite file picker) oppure
   SharePoint (link di condivisione del file — "Condividi" → "Copia collegamento" — e nome foglio).
2. **Autenticazione Entra ID**: app-only (client secret) oppure login utente delegato (MSAL).
   Vedi [docs/SETUP_ENTRA.md](docs/SETUP_ENTRA.md) per creare l'App Registration e i permessi.
3. **Invio email**: Microsoft Graph oppure SMTP (host/porta/utente/password/STARTTLS).
4. **Notifiche**: numero SMS di servizio per gli errori, liste di indirizzi mail (informative,
   riepilogo giornaliero, errori).
5. **Automazione**: intervallo di controllo (minimo 15 minuti, limite imposto da WorkManager),
   scrittura dello stato sul file Excel sorgente, codici USSD di attivazione/disattivazione.

La schermata **Override** permette di forzare a mano l'inoltro su un numero specifico,
bypassando il calendario, finché non viene disattivato.

## Schema del file Excel

Vedi [templates/Calendario_Reperibilita.xlsx](templates/Calendario_Reperibilita.xlsx) (foglio
`Reperibilita` + foglio `Istruzioni`). Colonne: `DataInizio | DataFine | NomePersona |
NumeroInoltro | EmailPersona | Note | Stato` (l'ultima è scritta automaticamente dall'app).

## Prossimi passi consigliati prima della produzione

1. Installare l'APK (`app/build/outputs/apk/debug/app-debug.apk`) su un device reale e verificare
   che l'app si avvii e le schermate funzionino (finora verificato solo che il progetto compili,
   non il comportamento a runtime: nessun device/emulatore disponibile in questo ambiente).
2. Registrare l'App Registration su Entra seguendo `docs/SETUP_ENTRA.md` e valorizzare
   client id/tenant/secret o abilitare il login delegato.
3. Decidere e implementare il percorso di gestione del device secondo
   `docs/SETUP_DEVICE.md` (fondamentale per l'affidabilità dell'inoltro chiamata).
4. Testare l'intero flusso end-to-end su un device reale con una SIM di test, prima di collegare
   numeri reali del personale.
5. Rivedere la ritenzione dei log (`LogRepository.purgeOlderThanDays`, non ancora schedulata
   automaticamente) e la rotazione/pulizia periodica se il volume di log cresce molto.
