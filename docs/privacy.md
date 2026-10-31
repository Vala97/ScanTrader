# Informativa privacy

ScanTrader è un’app Android che scansiona carte da collezione e le salva in collezioni sul telefono. Non ha un account ScanTrader, non mostra pubblicità e non ha un servizio di analisi proprio.

Contatto: [issue su GitHub](https://github.com/Vala97/ScanTrader/issues) del repository [Vala97/ScanTrader](https://github.com/Vala97/ScanTrader).

## Cosa resta sul telefono

- Le immagini della fotocamera sono elaborate sul dispositivo (OCR, impronta dell’arte, pack neurale se lo hai scaricato). L’app non le carica su un server di ScanTrader.
- La collezione (nomi, set, quantità, lingua, condizione, foil, prezzi) sta nel database locale dell’app.
- Il token API CardTrader, se lo scrivi in Impostazioni, e la password del DLS Tracker, se colleghi il tracker, sono cifrati con una chiave dell’Android Keystore. Non sono dentro l’APK.

## Chiamate di rete

L’app parla con questi servizi, solo per le funzioni indicate.

- **Scryfall** (`api.scryfall.com`, immagini su `cards.scryfall.io`): catalogo e prezzi in euro di Magic, e le immagini delle carte. Non serve un token.
- **TCGdex** (`api.tcgdex.net`): catalogo Pokémon (inglese, italiano, giapponese, cinese). Non serve un token.
- **CardTrader** (`api.cardtrader.com`): solo se in Impostazioni hai salvato il tuo token. L’app lo manda come `Authorization: Bearer`. Serve per i prezzi Magic dalle inserzioni e per i cataloghi di Riftbound, One Piece, Dragon Ball Super Fusion World, Yu-Gi-Oh! e Union Arena, comprese le immagini di quelle carte. Senza token queste chiamate non partono: Magic resta su Scryfall, gli altri giochi avvisano che manca il token.
- **Hugging Face** (`huggingface.co/HanClinto/milo`): download opzionale del modello Milo e del catalogo di embedding per Magic. Dopo il download il riconoscimento resta sul telefono.
- **GitHub** (`github.com` e `githubusercontent.com`), repository Vala97/ScanTrader: indice e file `.stcat` dei cataloghi Pokémon (`releases/latest`), e il controllo aggiornamenti dell’app sul tag fisso `app-update` (file `app-update.json` e APK). L’aggiornamento in-app è spento nelle build Play (`-Pscantrader.githubSelfUpdate=false`).
- **DLS Tracker**, solo se lo colleghi tu: l’indirizzo è quello che scrivi (HTTPS). L’app manda le carte salvate (non la foto) e legge l’inventario. È il tracker, non l’app, a scrivere poi su CardTrader.

Le richieste portano lo User-Agent `ScanTrader/1.0`, con il link al repository GitHub.

Una build compilata con `-Pscantrader.ctGalleryUrl` può scaricare una galleria `.stcat` da quell’indirizzo. Senza quella proprietà l’indirizzo è vuoto e il download non parte.

## OCR e telemetria di ML Kit

Il testo sulla carta è letto in locale con ML Kit Text Recognition (`com.google.mlkit:text-recognition` e le varianti cinese e giapponese, versione 16.0.1), tramite i servizi Google Play. L’immagine e il testo letto non sono il contenuto che ScanTrader invia.

Google documenta che gli SDK ML Kit mandano a Google, in HTTPS, dati di diagnostica e di utilizzo: informazioni sul dispositivo, nome e versione dell’app, metriche di prestazioni, configurazione dell’API, dimensione (non il contenuto) di input e output, tipo di evento, codici di errore. Per le funzioni che passano dai servizi Play, Google indica anche un identificatore del dispositivo e un identificatore di installazione, non pensati per riconoscere la persona. In logcat questi eventi possono comparire come `FIREBASE_ML_SDK`. L’app non include l’SDK Firebase come dipendenza diretta: il nome è quello del componente di log di ML Kit.

La pagina di Google sul data disclosure di ML Kit non elenca, per il riconoscimento del testo, un’opzione per spegnere questi invii. Questa app non li disattiva.

## Cosa non fa l’app

Niente pubblicità. Niente analytics di ScanTrader. Nessun backend ScanTrader che riceve la collezione o le foto.

# Privacy notice

ScanTrader is an Android app that scans collectible cards and stores collections on the phone. It has no ScanTrader account, shows no ads, and has no first-party analytics service.

Contact: [GitHub issues](https://github.com/Vala97/ScanTrader/issues) on [Vala97/ScanTrader](https://github.com/Vala97/ScanTrader).

## What stays on the phone

- Camera images are processed on the device (OCR, art fingerprint, and the neural pack if you downloaded it). The app does not upload them to a ScanTrader server.
- The collection (names, sets, quantities, language, condition, foil, prices) stays in the app’s local database.
- A CardTrader API token entered in Settings, and a DLS Tracker password if you link the tracker, are encrypted with an Android Keystore key. They are not compiled into the APK.

## Network calls

The app contacts these services only for the features named here.

- **Scryfall** (`api.scryfall.com`, images on `cards.scryfall.io`): Magic catalog, euro prices, and card images. No token.
- **TCGdex** (`api.tcgdex.net`): Pokémon catalog (English, Italian, Japanese, Chinese). No token.
- **CardTrader** (`api.cardtrader.com`): only after you save your own token in Settings. The app sends it as `Authorization: Bearer`. It is used for Magic prices from listings and for the Riftbound, One Piece, Dragon Ball Super Fusion World, Yu-Gi-Oh!, and Union Arena catalogs, including those card images. Without a token those calls are not made: Magic stays on Scryfall, and the other games say the token is missing.
- **Hugging Face** (`huggingface.co/HanClinto/milo`): optional download of the Milo model and the Magic embedding catalog. Recognition stays on the phone after that.
- **GitHub** (`github.com` and `githubusercontent.com`), repository Vala97/ScanTrader: Pokémon `.stcat` catalog index and files (`releases/latest`), and the in-app update check on the fixed `app-update` tag (`app-update.json` and the APK). In-app update is off in Play builds (`-Pscantrader.githubSelfUpdate=false`).
- **DLS Tracker**, only if you connect it: the address is the HTTPS URL you enter. The app sends saved cards (not the photo) and reads inventory. The tracker, not the app, is what later writes to CardTrader.

Requests use the User-Agent `ScanTrader/1.0` and include the link to the GitHub repository.

A build made with `-Pscantrader.ctGalleryUrl` may download a `.stcat` gallery from that address. Without the property the address is empty and that download does not run.

## OCR and ML Kit telemetry

Text on the card is read on the device with ML Kit Text Recognition (`com.google.mlkit:text-recognition` and the Chinese and Japanese variants, version 16.0.1), through Google Play services. ScanTrader does not upload the camera image or the recognized text as that payload.

Google documents that ML Kit SDKs send diagnostic and usage data to Google over HTTPS: device information, app package name and version, performance metrics, API configuration, input and output sizes (not the content), event type, and error codes. For features that go through Play services, Google also lists a device identifier and a per-installation identifier that are not intended to identify a person. Logcat can show these events as `FIREBASE_ML_SDK`. This app does not depend on the Firebase SDK directly; that name belongs to ML Kit’s logging component.

Google’s ML Kit data-disclosure page does not list a setting, for text recognition, that turns these uploads off. This app does not disable them.

## What the app does not do

No ads. No ScanTrader analytics. No ScanTrader backend that receives the collection or the photos.
