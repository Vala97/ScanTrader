# ScanTrader

App Android non ufficiale: scansiona carte **Magic: The Gathering**, **Pokémon**, **Riftbound**, **One Piece**, **Dragon Ball Super Fusion World**, **Yu-Gi-Oh!** e **Union Arena**, salvale in collezioni locali ed esporta un CSV da caricare su CardTrader.

Non è affiliata a Wizards of the Coast, The Pokémon Company, Nintendo, Riot Games, Bandai, Konami o CardTrader.

La versione è solo in `app/version.properties` (`VERSION_NAME` e `VERSION_CODE`; oggi 1.1, build 2). **Informazioni** la legge da lì (`Versione V1.1 (build 2)`; le build debug aggiungono `· debug`).

## Cosa fa

- **Scelta del gioco** prima dello scan (sette giochi, la lista scorre) e nel selettore sulla schermata di scan, che scorre in orizzontale.
- Scan di una carta o di una sessione. **Auto-scan** riconosce la carta e, se il match supera la soglia forte (74%), la **salva da solo** dopo un’attesa configurabile, poi **torna allo scan dello stesso gioco** con fotocamera pronta e auto-scan riarmato. I match deboli restano da confermare a mano.
- Durante lo scan lo **schermo resta acceso**.
- **Lente e zoom**: Automatica, Principale, Ultra-grandangolo, Teleobiettivo. Se una lente fisica non si apre, i preset 0,5× / 1× / 2× / 5× restano dentro l’intervallo dello zoom. Lente e zoom (anche il pinch) restano dopo il salvataggio.
- Riconoscimento **ibrido**: OCR, fingerprint dell’arte e **pack neurali** Milo on-device (opzionali, AGPL). Magic usa una gallery Scryfall; Pokémon una gallery TCGdex in inglese.
- **Nomi localizzati**: una carta italiana (o in un’altra lingua) si risolve dal nome stampato e dal numero di collezione, non solo dal nome inglese.
- **Codice set.** In basso l’OCR legge il codice stampato: `0181 • DFT • IT`, `0181 DFT IT`, oppure il set attaccato alla lingua e all’artista (`ELDITTAYLOR` → `ELD`). Sulle carte vecchie vale `74/165` (numero / carte nel set): un copyright o il costo di mana non diventano set o numero. Su Pokémon il codice sta accanto alla frazione (`ASC 072/217`, `D30C006/128`).
- **«Rileva lingua dalla carta»** in Impostazioni generali (attivo di default). In conferma la lingua proposta è quella del titolo letto; se il confronto non è chiaro, resta l’ultima lingua scelta. Con l’interruttore spento si usa solo l’ultima lingua.
- **Prezzi in euro.** Magic usa Scryfall (`prices.eur` / `prices.eur_foil`), senza token: stampa esatta, poi la stampa inglese dello stesso set e numero, poi la ristampa di carta più economica (a parità, la più recente). I fallback sono segnati come approssimati (`Scryfall (circa)`). Pokémon usa Cardmarket e **non ha un prezzo foil**: in conferma non c’è l’interruttore foil e non si mostra un importo holo. Il prezzo in dollari non viene mostrato.
- **Token API CardTrader** opzionale, in Impostazioni, cifrato nel Keystore del telefono. Solo con un token il prezzo Magic è la media delle inserzioni (lingua, condizione e foil se ci sono). Senza token non parte nessuna chiamata a CardTrader.
- Chiamate Scryfall in coda (~10 al secondo), con cache e pausa dopo un HTTP 429.
- Conferma di lingua, condizione, foil (solo Magic), quantità e collezione, con la percentuale di match e la fonte del prezzo (`Scryfall`, `Scryfall (circa)`, `CardTrader`, `Cardmarket`).
- **Export CSV CardTrader**, un file per gioco, nel formato `POST /product_imports`.
- **Collezione divisa per gioco e per espansione**, con le sezioni che si chiudono.
- **Collegamento al DLS Tracker** (facoltativo): le carte salvate vanno nel registro del proprio utente e da lì su CardTrader. Vedi [DLS Tracker](#dls-tracker).
- Interfaccia **Material 3 blu** (chiaro e scuro). L’icona è il medaglione blu e oro (cristallo, teschio, due uccelli e i simboli elementali) su fondo blu navy; la stessa compare in **Informazioni**.

Nessun account, nessun backend a pagamento. Unico costo eventuale: account sviluppatore Google Play (~25 USD) se pubblichi.

### Pack neurali

- **MTG**: **Impostazioni** → download da Hugging Face `HanClinto/milo` (~60 MB, AGPL-3.0). Senza pack il riconoscimento resta sull’OCR e lo scan lo segnala.
- **Pokémon**: **Impostazioni → Riconoscimento Pokémon** scarica i `.stcat` dal release GitHub (`stcat-index.json` su `releases/latest`). L’importazione manuale di un `.stcat` resta in quella sezione, visibile solo con le informazioni di debug. I file si generano con [`tools/build_pokemon_stcat`](tools/build_pokemon_stcat/README.md) e l’indice con [`tools/publish_stcat`](tools/publish_stcat/README.md).

## Riftbound, One Piece, Dragon Ball, Yu-Gi-Oh!, Union Arena

Questi giochi non hanno un catalogo gratuito: il catalogo viene da **CardTrader** e serve il **token API** in Impostazioni. Senza token lo scan lo dice («Serve il token CardTrader»).

- **Riftbound**: alla prima carta si scarica tutto il gioco; poi resta sul telefono.
- **Gli altri quattro**: si scarica **un’espansione alla volta**, quando lo scanner legge un codice di quell’espansione. Resta sul telefono per 14 giorni; senza token o senza rete si usa quella già scaricata.

Il riconoscimento usa il **codice stampato** (espansione + numero), non l’arte:

| Gioco | Dove | Esempio | Note |
|---|---|---|---|
| Riftbound | in basso a sinistra | `OGN • 001/298` | `007a` = Alternate Art; un numero oltre il totale del set = Showcase (overnumbered); `s` = firmata |
| One Piece | in basso a destra | `OP02-013`, `ST10-014`, `EB01-001`, `P-041` | `P-` = promo |
| Dragon Ball Super FW | in basso a destra | `FB02-075`, `FS03-14` | |
| Yu-Gi-Oh! | sotto il disegno, a destra | `POTD-EN003`, `YGLD-ITA01` | la lingua del codice (`EN`, `IT`, …) diventa la lingua della carta |
| Union Arena | in basso a sinistra | `UE20BT/TSK-2-001` | stesso numero per più carte: decide il nome letto in alto |

L’OCR corregge gli scambi tipici (`0`/`O`, `1`/`l`/`I`, `5`/`S`, `8`/`B`). Su CardTrader i codici delle espansioni non sono unici fra i giochi (`op02` esiste anche in Yu-Gi-Oh!): si cerca solo nel gioco scelto.

**Salvataggio automatico** solo se espansione e numero indicano **una sola carta**. Se lo stesso codice ha più versioni (One Piece normale, Alternate Art e promo dei tornei, Dragon Ball `a`, Union Arena ★, Riftbound Showcase), la conferma mostra le versioni con le immagini e si sceglie a mano. I prodotti sigillati e alcune promo senza codice si trovano con **Cerca per nome** (solo nelle espansioni già scaricate).

**Foil.** Riftbound: Comune e Non comune possono essere foil o normali, Rara, Epica, Leggendaria e Showcase sono sempre foil (l’interruttore è bloccato). One Piece, Dragon Ball, Yu-Gi-Oh! e Union Arena non hanno il foil su CardTrader: l’interruttore non compare.

**Prezzo**: la media delle inserzioni CardTrader della stessa carta (lingua e condizione se ci sono).

## DLS Tracker

Facoltativo: serve solo a chi usa il DLS Tracker, un registro privato delle vendite. Senza collegamento l’app funziona come prima.

**Home → icona carrello (DLS Tracker)**: indirizzo del tracker (`https://…`), utente e password. La password resta cifrata nel Keystore del telefono. Ognuno entra col **suo** utente: le carte finiscono solo nel suo registro.

- **Scan → tracker.** Ogni carta salvata parte con un codice unico (`scan-…`). Se la rete cade, resta nella coda in uscita e riparte da sola; il tracker ignora un codice già ricevuto, quindi niente doppioni.
- **Tracker → CardTrader.** È **solo il tracker** a scrivere su CardTrader, mai l’app. Dopo 10 minuti (per correggere un errore) carica la carta con le Regole prezzi del registro, oppure aumenta la quantità di un annuncio identico già esistente.
- **«Manda al tracker»** nella collezione manda le copie non ancora mandate.
- **Vendite.** Quando il registro segna una vendita (CardTrader, Cardmarket o a mano), la collezione toglie le copie vendute; se la vendita viene annullata, le rimette. Vale solo per le righe partite dallo scanner.
- **Collezione «Cardtrader».** Specchio, in sola lettura, di tutti i propri annunci CardTrader presenti nel registro, aggiornato da solo. Non compare fra le destinazioni di «Salva in».
- **Inventario** (schermata DLS Tracker): i propri annunci divisi per gioco ed espansione, più lo stato della coda (in attesa, inviata, su CardTrader, errore).

Il tracker deve essere raggiungibile dal telefono (per esempio su Tailscale, con HTTPS). L’app non permette http in chiaro.

## Aprire il progetto

1. Installa [Android Studio](https://developer.android.com/studio) (include SDK e JDK).
2. File → Open → cartella `ScanTrader`.
3. Lascia che Gradle sincronizzi, poi Run su un telefono USB o un emulatore.

`minSdk` 26, `compileSdk` 35. La build vuole **JDK 17** (sorgenti e bytecode), Android Gradle Plugin **8.13.2** e Gradle **8.13** (lo wrapper è già nel repo).

Da terminale, con SDK Android installato e `local.properties` (`sdk.dir=...`):

```bat
gradlew.bat :app:assembleDebug
gradlew.bat :app:assembleRelease
```

`assembleRelease` attiva minify e `shrinkResources` (regole in `app/proguard-rules.pro`: ONNX Runtime, ML Kit, Room, Retrofit e i model Gson). Moshi e kotlinx.serialization non sono dipendenze. La release include solo l’ABI `arm64-v8a`. La debug tiene tutte le ABI, così un emulatore x86_64 parte ancora.

Senza keystore l’APK di release si compila lo stesso e resta non firmato (`app-release-unsigned.apk`): in log compare un avviso. Una build Play Store, senza l’aggiornamento da GitHub, si ottiene con `-Pscantrader.githubSelfUpdate=false` (il task resta `assembleRelease` / `assembleDebug`).

## Firma della release

Il keystore non va nel git (`*.jks`, `*.keystore` e `keystore.properties` sono già ignorati). Si crea una volta, fuori dal repository o nella root, e non si committa:

```bat
keytool -genkeypair -v -keystore keystore.jks -alias scantrader -keyalg RSA -keysize 2048 -validity 10000
```

`keystore.properties` nella root del repo (accanto a `settings.gradle.kts`), mai committato:

```properties
storeFile=keystore.jks
storePassword=la-password-del-keystore
keyAlias=scantrader
keyPassword=la-password-della-chiave
```

`storeFile` è relativo alla root del repo, oppure un percorso assoluto. Per ogni campo vale prima il file, poi la variabile d’ambiente: `SCANTRADER_KEYSTORE_FILE`, `SCANTRADER_KEYSTORE_PASSWORD`, `SCANTRADER_KEYSTORE_KEY_ALIAS`, `SCANTRADER_KEYSTORE_KEY_PASSWORD`. Se manca un campo o il file del keystore non c’è, la release resta non firmata e la build non fallisce.

Il workflow su tag `v*` (`.github/workflows/release-apk.yml`) firma solo se nel repository GitHub ci sono i secret `SCANTRADER_KEYSTORE_BASE64` (il file `.jks` in base64), `SCANTRADER_KEYSTORE_PASSWORD`, `SCANTRADER_KEYSTORE_KEY_ALIAS` e `SCANTRADER_KEYSTORE_KEY_PASSWORD`. Se ne manca uno, il job di compilazione non parte.

## Aggiornamento in-app

All’avvio, se «Controlla aggiornamenti automaticamente» è acceso (lo è di default), l’app legge al massimo una volta al giorno:

`https://github.com/Vala97/ScanTrader/releases/download/app-update/app-update.json`

Non usa `releases/latest`: quell’URL deve restare il release dei cataloghi (`stcat`). `app-update` si pubblica come **prerelease**. In Impostazioni generali, «Controlla aggiornamenti» forza il controllo. Se `versionCode` è maggiore di quello installato, un dialogo Material 3 mostra «Nuova versione … disponibile», le note, «Aggiorna» e «Più tardi». Il download va in cache, lo sha256 viene verificato e solo allora parte l’installazione. Senza il permesso «Installa app sconosciute» compare un messaggio che porta alle impostazioni.

Per una build destinata al Play Store il controllo GitHub si spegne con `-Pscantrader.githubSelfUpdate=false`. L’interfaccia `UpdateSource` è il punto in cui infilare gli aggiornamenti in-app di Play.

L’indice si prepara con [`tools/publish_app_update`](tools/publish_app_update/README.md): lo script non carica nulla. Il workflow sul tag `v*` carica l’APK firmato e `app-update.json` sul release `app-update`, e lo lascia prerelease.

## Import su CardTrader

Nella collezione tocca Condividi. Esce **un file per gioco** (`nome-mtg.csv`, `nome-pokemon.csv`, `nome-riftbound.csv`, …). Chi usa il [DLS Tracker](#dls-tracker) non ha bisogno del CSV. Se la lista mescola Magic e Pokémon, la condivisione allega entrambi.

Su CardTrader carica **un file alla volta** e scegli **aggiungi allo stock** (`add_to_stock`), non sostituire lo stock.

Colonne, in quest’ordine:

- Magic: `scryfall_id,name,expansion_code,collector_number,quantity,language,condition,foil,price_cents`
- Pokémon: `name,expansion_code,collector_number,quantity,language,condition,foil,tcgplayer_id,price_cents`

`name` è il nome inglese del blueprint (Scryfall `name`, o il nome TCGdex in inglese), non il titolo stampato sulla carta. In app la carta resta col titolo letto, per esempio «Stendardiere del Lancio della Rana»; nel CSV diventa `Frogtosser Banneret`. Una bifronte usa il nome con `//` (`Smitten Swordmaster // Curry Favor`). `scryfall_id` è quello della stampa inglese, quello che CardTrader ha sul blueprint.

Dopo l’aggiornamento **riesporta** la collezione. Le righe già salvate prendono il nome inglese e, per Magic, lo `scryfall_id` inglese al momento della condivisione. Se il nome catalogo c’è già, funziona anche senza rete; senza rete e senza nome catalogo si esporta il titolo salvato.

`language` usa i codici della reference CardTrader (proprietà `mtg_language`): `en`, `it`, `fr`, `de`, `es`, `pt`, `jp`, `kr`, `zh-CN`. Giapponese è `jp`, coreano `kr`, cinese semplificato `zh-CN`. `it` è la lingua della copia, non del titolo: una carta italiana di Morningtide resta `it`. Un set solo inglese (Masters 25) può avvisare e rimettere la lingua di default, ma la riga viene importata. La stessa colonna vale per Pokémon: la reference non elenca un secondo vocabolario e gli ordini usano `pokemon_language` con gli stessi codici (`en`).

`condition` resta `Near Mint`, `Slightly Played`, `Moderately Played`, `Heavily Played`. Damaged diventa `Poor` (valore documentato; esistono anche `Played` e, nel testo, Mint, che non è tra i `possible_values`).

`price_cents` è il prezzo in centesimi interi (3,50 EUR → `350`). Una foil Magic usa il prezzo foil se c’è. Pokémon non ha un prezzo foil: la riga è sempre `foil=false` e `price_cents` è il prezzo non foil (un holo non viene scritto). Se il prezzo manca o è ≤ 0 la cella resta vuota.

## Cataloghi

- MTG: https://api.scryfall.com
- Pokémon: https://api.tcgdex.net in inglese, italiano, giapponese, cinese semplificato e cinese tradizionale (`en`, `it`, `ja`, `zh-cn`, `zh-tw`)
- Riftbound, One Piece, Dragon Ball Super Fusion World, Yu-Gi-Oh!, Union Arena: https://api.cardtrader.com/api/v2 (`expansions`, `blueprints/export`), con il token
