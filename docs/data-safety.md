# Sicurezza dei dati (Play Console)

Testo da incollare nella scheda **Sicurezza dei dati** di Google Play. Descrive quello che l’app fa davvero al commit di `main` (versione letta da `app/version.properties`). Non è una modifica al comportamento: l’informativa per chi usa l’app resta [`docs/privacy.md`](privacy.md).

ScanTrader non vende dati, non mostra pubblicità e non fa tracking proprio. Non esiste un account ScanTrader. I dati dell’app stanno sul telefono e si cancellano disinstallando. CardTrader e il DLS Tracker, se l’utente li collega, sono account di quei servizi.

Le chiamate escono in HTTPS. L’utente non può chiedere a ScanTrader di cancellare un account, perché non c’è un account.

## Risposte per la scheda

| Dato | Raccolto | Condiviso | Con chi | Perché | Obbligatorio |
| --- | --- | --- | --- | --- | --- |
| Foto e video | No | No | — | OCR, impronta dell’arte e pack neurale restano sul telefono. L’immagine della carta non è il payload inviato. | — |
| Info personali, posizione, contatti, audio, cronologia web | No | No | — | Nessun permesso e nessuna chiamata di questo tipo. Il manifest chiede solo la fotocamera, Internet e `REQUEST_INSTALL_PACKAGES`. | — |
| Attività nell’app (nome carta, set, numero, lingua cercati) | Sì, effimero | Sì | Scryfall, TCGdex; CardTrader solo se l’utente ha salvato il token | Funzione dell’app: catalogo e prezzi | Sì, per riconoscere la carta |
| Diagnostica e ID dispositivo / installazione | Sì | Sì | Google, via ML Kit Text Recognition e i servizi Play | Analisi di Google. In logcat il componente si chiama `FIREBASE_ML_SDK`. L’app non dipende dall’SDK Firebase. Il testo letto e l’immagine della carta non sono quel payload. Google non documenta un interruttore per spegnerlo sul riconoscimento del testo. | Non disattivabile |
| Token CardTrader e password del tracker | No, come raccolta del produttore | Inviati solo al servizio che l’utente ha collegato | `api.cardtrader.com`; l’URL HTTPS del DLS Tracker scritto dall’utente | Funzione richiesta dall’utente. Sul telefono sono cifrati con l’Android Keystore e non sono dentro l’APK. | Solo se l’utente li salva |
| Collezione (nome, set, quantità, lingua, condizione, foil, prezzo) | Resta sul telefono | Solo se l’utente condivide il CSV o ha collegato il tracker | Foglio di condivisione di Android; DLS Tracker | Funzione dell’app | No |

Hugging Face (`huggingface.co`) e GitHub (`github.com`, `githubusercontent.com`) ricevono il download del modello Milo, dei cataloghi `.stcat` e, fuori dalle build Play, il controllo dell’APK sul tag `app-update`. Non ricevono la collezione.

## Build Play e `REQUEST_INSTALL_PACKAGES`

Le build Play passano `-Pscantrader.githubSelfUpdate=false`: l’app non scarica né installa un APK da GitHub. Il permesso `android.permission.REQUEST_INSTALL_PACKAGES` è comunque dichiarato in `AndroidManifest.xml` anche in quelle build. In console va detto che la build Play non installa pacchetti. Toglierlo dal manifest delle build Play è un cambiamento a parte, non fatto qui.

## Cosa segnare in console

- Dati raccolti: sì. Qualcosa esce dal telefono (query di catalogo, e diagnostica di ML Kit verso Google).
- Dati condivisi: sì, con i servizi della tabella. Non venduti. Non usati per pubblicità né per tracking di ScanTrader.
- Cifratura in transito: sì.
- L’utente può chiedere la cancellazione a ScanTrader: no, non c’è un account. I dati locali si cancellano disinstallando l’app.
- Foto: non raccolte e non condivise da ScanTrader. Il permesso fotocamera serve solo all’elaborazione sul dispositivo.
- ID dispositivo: sì, tramite ML Kit / servizi Play, per diagnostica di Google, non collegati a un account ScanTrader.

# Data safety (Play Console)

Paste-in text for the Play **Data safety** form. It matches what the app does on `main`. The in-app notice remains [`docs/privacy.md`](privacy.md).

ScanTrader does not sell data, show ads, or run its own tracking. There is no ScanTrader account. App data stays on the phone and is removed on uninstall. CardTrader and DLS Tracker, if the user connects them, are accounts on those services.

Traffic is HTTPS. The user cannot ask ScanTrader to delete an account, because there is no account.

## Form answers

| Data | Collected | Shared | With whom | Why | Required |
| --- | --- | --- | --- | --- | --- |
| Photos and videos | No | No | — | OCR, the art fingerprint, and the neural pack stay on the phone. The card image is not the payload that is sent. | — |
| Personal info, location, contacts, audio, web history | No | No | — | No permission and no call of that kind. The manifest asks only for the camera, Internet, and `REQUEST_INSTALL_PACKAGES`. | — |
| App activity (card name, set, collector number, language looked up) | Yes, ephemeral | Yes | Scryfall, TCGdex; CardTrader only after the user saves a token | App functionality: catalog and prices | Yes, to identify the card |
| Diagnostics and device / install IDs | Yes | Yes | Google, via ML Kit Text Recognition and Play services | Google’s diagnostics. Logcat shows `FIREBASE_ML_SDK`. The app does not depend on the Firebase SDK. The recognized text and the card image are not that payload. Google does not document a switch that turns this off for text recognition. | Cannot be turned off |
| CardTrader token and tracker password | No, as developer collection | Sent only to the service the user connected | `api.cardtrader.com`; the HTTPS DLS Tracker URL the user entered | Feature the user asked for. On the phone they are encrypted with the Android Keystore and are not inside the APK. | Only if the user saves them |
| Collection (name, set, quantity, language, condition, foil, price) | Stays on the phone | Only if the user shares the CSV or linked the tracker | Android share sheet; DLS Tracker | App functionality | No |

Hugging Face (`huggingface.co`) and GitHub (`github.com`, `githubusercontent.com`) receive the Milo model download, the `.stcat` catalogs and, outside Play builds, the APK update check on the `app-update` tag. They do not receive the collection.

## Play builds and `REQUEST_INSTALL_PACKAGES`

Play builds pass `-Pscantrader.githubSelfUpdate=false`: the app does not download or install an APK from GitHub. `android.permission.REQUEST_INSTALL_PACKAGES` is still declared in `AndroidManifest.xml` for those builds. Say in the console that the Play build does not install packages. Removing the permission from Play manifests is a separate change and is not done here.

## What to tick

- Data collected: yes. Something leaves the phone (catalog queries, and ML Kit diagnostics to Google).
- Data shared: yes, with the services in the table. Not sold. Not used for ads or for ScanTrader tracking.
- Encrypted in transit: yes.
- User can ask ScanTrader to delete an account: no account exists. Local data is removed by uninstalling.
- Photos: not collected and not shared by ScanTrader. The camera permission is only for on-device processing.
- Device IDs: yes, via ML Kit / Play services, for Google diagnostics, not linked to a ScanTrader account.
