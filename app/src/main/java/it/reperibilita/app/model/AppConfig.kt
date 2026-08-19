package it.reperibilita.app.model

enum class ScheduleSourceMode { LOCAL_FILE, SHAREPOINT }

enum class GraphAuthMode { CLIENT_CREDENTIALS, DELEGATED_INTERACTIVE }

enum class MailSendMode { SMTP, GRAPH_API, DISABLED }

data class SharePointConfig(
    // Metodo A (consigliato con autenticazione CLIENT_CREDENTIALS/app-only): percorso diretto
    // del file. Rispetta i permessi Application (Sites.ReadWrite.All) sull'intero tenant, senza
    // passare dal meccanismo di condivisione a link - che con un token app-only spesso fallisce
    // con "accesso negato" se il link e' condiviso con persone specifiche.
    val siteUrl: String = "",          // es. https://contoso.sharepoint.com/sites/NomeSito
    // Percorso RELATIVO ALLA LIBRERIA documenti predefinita (senza ripetere "Documenti
    // condivisi"/"Shared Documents", gia' risolta a parte). Es. /Reperibilita/Calendario.xlsx
    val driveItemPath: String = "",

    // Metodo B (funziona meglio con DELEGATED_INTERACTIVE, dove il token rappresenta un utente
    // reale che ha accesso al link): link di condivisione copiato da SharePoint/OneDrive
    // (Condividi -> Copia collegamento). Risolto via Graph "/shares/{encoded}/driveItem".
    val sharingUrl: String = "",

    val worksheetName: String = ScheduleSheetSchema.SHEET_NAME
)

data class EntraAppConfig(
    val tenantId: String = "",
    val clientId: String = "",
    val clientSecret: String = "",     // solo per CLIENT_CREDENTIALS; conservato cifrato
    // Deve corrispondere ESATTAMENTE al redirect URI mostrato da Azure (App registration ->
    // Authentication -> piattaforma Android), URL-encoded (%2B/%3D) - diverso dal formato usato
    // nell'intent-filter di BrowserTabActivity in AndroidManifest.xml, che invece va decodificato
    // (Android confronta i path degli intent-filter dopo la decodifica URL).
    val redirectUri: String = "msauth://it.reperibilita.app/3bjzgvOZp5X2lSTJP40%2BNMYt58E%3D",
    // Casella mittente per l'invio mail: obbligatoria in modalita' CLIENT_CREDENTIALS
    // (POST /users/{upn}/sendMail), opzionale in DELEGATED_INTERACTIVE (default /me/sendMail).
    val graphSenderUpn: String = ""
)

data class SmtpConfig(
    val host: String = "",
    val port: Int = 587,
    val username: String = "",
    val password: String = "",         // conservato cifrato
    val useStartTls: Boolean = true,
    val fromAddress: String = ""
)

data class NotificationConfig(
    val serviceErrorPhoneNumber: String = "",   // numero SMS per notifiche di errore
    val infoEmailRecipients: List<String> = emptyList(),   // destinatari mail informative cambio turno
    val summaryEmailRecipients: List<String> = emptyList(), // destinatari mail riepilogo/log periodico
    val errorEmailRecipients: List<String> = emptyList()
)

data class AppConfig(
    val scheduleSourceMode: ScheduleSourceMode = ScheduleSourceMode.LOCAL_FILE,
    val localFileUri: String? = null,
    val sharePoint: SharePointConfig = SharePointConfig(),
    val graphAuthMode: GraphAuthMode = GraphAuthMode.CLIENT_CREDENTIALS,
    val entraApp: EntraAppConfig = EntraAppConfig(),
    val mailSendMode: MailSendMode = MailSendMode.GRAPH_API,
    val smtp: SmtpConfig = SmtpConfig(),
    val notifications: NotificationConfig = NotificationConfig(),
    // Rete di sicurezza, non il meccanismo principale: il vero cambio turno e' guidato da un
    // trigger preciso riarmato ad ogni controllo esattamente sul prossimo confine di turno (vedi
    // ScheduleCheckWorker.schedulePreciseTrigger), quindi questo intervallo serve solo a coprire
    // il caso in cui quel trigger si perda. Default settimanale (10080 min) invece degli originari
    // 15 minuti, proprio perche' non serve piu' per la puntualita' del cambio.
    val checkIntervalMinutes: Int = 10_080,
    // Interruttore generale: se false, il worker non esegue NESSUN controllo (ne' periodico ne'
    // manuale da "Esegui controllo ora"/"Forza nuovo tentativo") - nessun inoltro, SMS, mail o
    // scrittura su Excel. Serve per poter fermare del tutto l'automazione (es. manutenzione,
    // cambio SIM in corso) senza disinstallare l'app o perdere la configurazione. L'inoltro
    // eventualmente gia' attivo sulla SIM NON viene toccato/disattivato: questo interruttore ferma
    // solo il controllo automatico, non modifica lo stato attuale della rete.
    val automationEnabled: Boolean = true,
    val writeBackToExcel: Boolean = true,
    val usePrivilegedUssd: Boolean = true, // se false forza sempre il fallback via dialer
    // Codice USSD di attivazione inoltro incondizionato; {number} viene sostituito col numero
    // della persona reperibile. Default = codice GSM standard (TIM/Vodafone/WindTre in Italia).
    val ussdActivateTemplate: String = "**21*{number}#",
    val ussdDeactivateCode: String = "##21#",
    // Codice di interrogazione stato (senza modificare l'inoltro): usato per la verifica
    // post-attivazione, disponibile solo sul percorso USSD privilegiato (vedi
    // CallForwardingManager.verifyForwardingNumber). Default = codice GSM standard.
    val ussdStatusQueryCode: String = "*#21#",
    val manualOverrideActive: Boolean = false,
    val manualOverrideNumber: String = "",
    val manualOverridePersonName: String = ""
)
