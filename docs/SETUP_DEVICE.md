# Gestione del device e affidabilità dell'inoltro chiamata

Questa è la decisione tecnica più importante del progetto: da essa dipende se l'inoltro di
chiamata viene impostato in modo **verificato** (l'app legge la conferma della rete) oppure
solo **inviato** (l'app apre il codice USSD nel dialer di sistema, ma non può leggerne l'esito).

## Il vincolo di Android

`TelephonyManager.sendUssdRequest()`, l'unica API pubblica per inviare un codice USSD e
leggerne la risposta in modo programmatico, richiede il permesso `MODIFY_PHONE_STATE`.

Questo permesso è di tipo `signature|privileged`: **non può essere concesso a runtime** come i
normali permessi "pericolosi" (fotocamera, SMS, ecc.), nemmeno chiedendolo esplicitamente
all'utente. Le uniche vie per ottenerlo sono:

1. **App installata come app di sistema privilegiata** (`/system/priv-app`), su un device con
   un OS Android personalizzato/AOSP di cui si ha il controllo (es. un firmware aziendale, un
   dispositivo Android Enterprise fully-managed con provisioning custom). Richiede competenze di
   build/flash del firmware o accordo con l'OEM/system integrator del device.
2. **App firmata con la platform key** del device (stessa logica del punto 1, variante).
3. **Device rooted**, concedendo il permesso manualmente con
   `adb shell pm grant it.reperibilita.app android.permission.MODIFY_PHONE_STATE`.
   Utile in **fase di test/sviluppo**, sconsigliato in produzione (un device rooted ha una
   superficie di attacco molto più ampia ed è più difficile da mettere in conformità IT).
4. **Carrier privileges** (meccanismo GSMA/Android in cui l'operatore mobile inserisce
   nell'applet della SIM l'hash del certificato dell'app): tecnicamente supportato da Android,
   ma richiede la collaborazione diretta dell'operatore telefonico e in pratica non è disponibile
   per una generica SIM aziendale di un operatore consumer/business standard.

Per la maggior parte delle aziende, **nessuna di queste opzioni è immediatamente disponibile**
senza un investimento specifico (firmware custom, rooting accettato, o accordo con l'operatore).

## Cosa fa l'app di conseguenza

`CallForwardingManager` (in `data/callforwarding/`) implementa entrambi i percorsi e sceglie
automaticamente:

- se `MODIFY_PHONE_STATE` risulta concesso (e l'opzione "Usa USSD privilegiato" è attiva nelle
  Impostazioni) → usa `sendUssdRequest`, legge la risposta della rete, e la registra nel log
  come **verificata**;
- altrimenti → apre il dialer di sistema con il codice USSD (`Intent.ACTION_CALL`, permesso
  `CALL_PHONE`, concedibile normalmente). Il comando viene inviato, ma l'esito **non è
  verificabile dall'app**: viene loggato come "inviato, esito non verificato (fallback dialer)".

## Raccomandazione pratica

- **Se potete gestire il device dedicato alla reperibilità** (es. è un telefono aziendale
  configurato da IT): valutate un device Android Enterprise fully-managed con firmware che
  consenta il provisioning come app privilegiata, oppure — più semplice da ottenere nel breve
  termine — un device rooted usato *solo* per questo scopo, isolato dal resto della rete
  aziendale, con `adb shell pm grant` eseguito una volta in fase di provisioning.
- **Se non potete/volete gestire il root o un firmware custom**: usate il fallback dialer.
  Funziona in produzione, ma **aggiungete un controllo umano periodico** (es. una chiamata di
  test settimanale al numero di reperibilità) per accorgervi di eventuali fallimenti silenziosi
  del comando USSD lato rete/operatore, dato che l'app non può rilevarli da sola in questa
  modalità.
- In entrambi i casi, **configurate sempre** il numero SMS di servizio e le mail di errore nelle
  Impostazioni: qualunque eccezione nel flusso (lettura calendario, invio SMS, invio mail,
  scrittura su Excel) viene comunque notificata, indipendentemente dalla strategia di inoltro.

## Verifica manuale del codice USSD sul vostro operatore

I codici di default nell'app (`**21*{number}#` per attivare l'inoltro incondizionato, `##21#`
per disattivarlo) sono lo standard GSM e funzionano con la maggior parte degli operatori europei
(TIM, Vodafone, WindTre, ecc. in Italia). Prima di andare in produzione, verificate manualmente
dal telefono dedicato che il vostro operatore/piano supporti l'inoltro chiamata e che il codice
sia quello corretto; se necessario sono personalizzabili nelle Impostazioni dell'app.
