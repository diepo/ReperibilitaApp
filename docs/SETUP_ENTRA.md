# Setup App Registration su Microsoft Entra ID

Serve per due cose indipendenti, entrambe configurabili dall'app:
- leggere/scrivere il calendario su **SharePoint** (Microsoft Graph Workbook API);
- inviare **email** via Microsoft Graph (`sendMail`), in alternativa a SMTP.

Se usate solo file locale + SMTP, questo passaggio non serve.

## 1. Creare l'App Registration

1. Portale Azure → **Microsoft Entra ID** → **App registrations** → **New registration**.
2. Nome: es. "Reperibilità App". Account supportati: single tenant (a meno di esigenze diverse.
3. **Redirect URI**: solo se userete il login delegato (MSAL) — tipo "Public client/native
   (mobile & desktop)", valore `msauth://it.reperibilita.app/<HASH_FIRMA_BASE64>` (vedi punto 4).
4. Annotare **Application (client) ID** e **Directory (tenant) ID**: vanno inseriti nelle
   Impostazioni dell'app.

## 2. Scegliere il flusso di autenticazione (l'app supporta entrambi)

### A. App-only / client credentials (consigliato per l'automazione headless)

Nessun utente loggato: l'app si autentica come applicazione. Adatto perché il worker gira in
background senza interazione.

1. **Certificates & secrets** → **New client secret**. Copiare il valore (visibile una sola
   volta) e inserirlo nelle Impostazioni dell'app come "Client secret".
2. **API permissions** → **Add a permission** → **Microsoft Graph** → **Application permissions**:
   - `Mail.Send` (per l'invio email via Graph)
   - `Sites.ReadWrite.All` oppure, più restrittivo, `Sites.Selected` con accesso concesso solo al
     sito SharePoint specifico (consigliato per il principio del minimo privilegio; richiede una
     chiamata Graph aggiuntiva da un amministratore per assegnare il sito, non gestita dall'app)
3. **Grant admin consent** per il tenant (richiede un ruolo da amministratore Entra).
4. Nelle Impostazioni dell'app, impostare anche **"Casella mittente mail (UPN)"**: in modalità
   app-only, `sendMail` richiede sempre una casella specifica da cui inviare (es.
   `reperibilita@azienda.it`), non esiste un "mittente implicito".

### B. Login utente delegato (interattivo, via MSAL)

L'app apre il browser per il login la prima volta (dalla schermata Impostazioni, pulsante
"Accedi con Microsoft"); i token successivi vengono rinnovati in automatico finché il refresh
token resta valido.

1. In **Authentication**, aggiungere una piattaforma **Android**:
   - Package name: `it.reperibilita.app`
   - Signature hash: generarlo con
     ```bash
     keytool -exportcert -alias <alias_keystore> -keystore <path_keystore> | openssl sha1 -binary | openssl base64
     ```
     Il redirect URI risultante (`msauth://it.reperibilita.app/<hash>`) va incollato sia qui su
     Azure sia nel campo "Redirect URI" delle Impostazioni dell'app.
2. **API permissions** → **Delegated permissions**: `Mail.Send`, `Sites.ReadWrite.All` (o
   `Files.ReadWrite.All` se si preferisce accedere al drive senza passare da Sites).
3. Se il tenant richiede consenso admin anche per i permessi delegati, un amministratore deve
   effettuare il primo login e concederlo (oppure usare "Grant admin consent" in portale).

## 3. Permessi minimi in sintesi

| Funzione | Permesso Application (client credentials) | Permesso Delegated (login utente) |
|---|---|---|
| Invio mail | `Mail.Send` | `Mail.Send` |
| Lettura/scrittura calendario su SharePoint | `Sites.ReadWrite.All` (o `Sites.Selected`) | `Sites.ReadWrite.All` |

## 4. Individuare il file su SharePoint

Nelle Impostazioni dell'app, due metodi alternativi (basta compilarne uno):

### Metodo A — URL sito + percorso file (consigliato con CLIENT_CREDENTIALS / app-only)

- **URL sito SharePoint**: es. `https://contoso.sharepoint.com/sites/NomeSito` (l'indirizzo che
  vedi in barra quando navighi il sito, non il link del singolo file).
- **Percorso file nella libreria documenti**: percorso **relativo alla libreria documenti
  predefinita** ("Documenti condivisi"/"Shared Documents") — **senza ripetere il nome della
  libreria**, perché l'app risolve già quella libreria a parte. Esempi:
  - file nella radice della libreria: `/Calendario_Reperibilita.xlsx`
  - file in una sottocartella: `/Reperibilita/Calendario_Reperibilita.xlsx`

  Un errore comune (che produce un 404) è scrivere `/Documenti condivisi/Calendario_Reperibilita.xlsx`:
  significherebbe cercare una sottocartella "Documenti condivisi" dentro la libreria "Documenti
  condivisi", che non esiste.

Questo metodo accede al file tramite il suo percorso reale, rispettando direttamente i permessi
Application (`Sites.ReadWrite.All`) concessi sul tenant — **funziona in modo affidabile con
l'autenticazione app-only**.

### Metodo B — link di condivisione (funziona meglio con DELEGATED_INTERACTIVE)

- **Link di condivisione file SharePoint**: da "Condividi" → "Copia collegamento" (es.
  `https://contoso.sharepoint.com/:x:/s/NomeSito/XXXXXXXXXXXXXXXXXXXXXXXXXX?e=YYYYYY`), risolto
  via Graph `/shares/{id}/driveItem`.

**Attenzione con CLIENT_CREDENTIALS**: se il link è condiviso "con persone specifiche" (il tipo
di default quando si clicca Condividi), Graph spesso rifiuta l'accesso con un token app-only —
l'identità dell'applicazione non è "una persona" presente nell'elenco autorizzato del link, a
prescindere dai permessi Application concessi sul tenant. In questo caso l'errore restituito è
tipicamente un messaggio del tipo "you do not have permission to open this file". Se lo vedi,
passa al Metodo A, oppure — se vuoi comunque usare il link — imposta il link come "Chiunque nel
[tenant] con il link" invece che "Persone specifiche" (SharePoint → Condividi → cambia
destinatari), il che a volte risolve, ma il Metodo A resta il più affidabile.

In entrambi i casi, il **Nome foglio** deve corrispondere esattamente al nome del foglio nel
file (`Reperibilita`, vedi `templates/Calendario_Reperibilita.xlsx`).

## 5. Note di sicurezza

- Il client secret e le password SMTP sono salvati con `EncryptedSharedPreferences` (chiave
  gestita da Android Keystore): non risiedono mai in chiaro sul device. Restano comunque un
  segreto applicativo su un device fisico — trattate il device come materiale sensibile e
  ruotate il secret se il device viene smarrito o dismesso.
- Preferite `Sites.Selected` a `Sites.ReadWrite.All` quando possibile: limita l'accesso
  dell'app al solo sito SharePoint della reperibilità invece che a tutto il tenant.
