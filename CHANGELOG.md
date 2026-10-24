# Changelog

Tutte le modifiche rilevanti a ScanTrader sono documentate in questo file.
Il formato segue [Keep a Changelog](https://keepachangelog.com/it-IT/1.1.0/).

## [Non rilasciato]

### Aggiunto
- Durante la schermata di scan lo schermo resta acceso (`FLAG_KEEP_SCREEN_ON`), così il telefono non va in blocco da solo. Il flag si toglie uscendo dallo scan.
- Scelta della **lente** nel pannello Impostazioni scan (e in Impostazioni): Automatica, Principale, Ultra-grandangolo, Teleobiettivo. Si elencano le fotocamere posteriori, comprese le lenti fisiche della multi-camera logica (nome in base alla focale). Se una lente fisica non si può aprire, si usano gli zoom 0,5× / 1× / 2× / 5× dentro l’intervallo dello zoom. Con una sola scelta il selettore resta nascosto. La preferenza è salvata; torcia e auto-scan restano attivi.
- Schermata **Informazioni**: versione visibile letta da `BuildConfig` (`Versione V1.0 (build 1)`). Le build debug aggiungono il suffisso `· debug`.
- Riconoscimento neurale **Pokémon**: import di un pack `.stcat` (gallery Milo su catalogo TCGdex EN) da **Impostazioni → Riconoscimento Pokémon → Importa .stcat**.
- Strumento `tools/build_pokemon_stcat` (Python) per generare `pokemon.stcat`, con modalità smoke su pochi set.
- Selezione della fotocamera posteriore da usare per la scansione (`CameraDevices`).
- Conversione dei frame CameraX in bitmap dritte con gestione rotazione/EXIF (`CameraBitmaps`), così il ritaglio colpisce titolo e numero di collezione.
- Auto-Scan con salvataggio automatico in collezione dopo un'attesa configurabile.
- Nuovi test: `CameraBitmapsTest`, `EmbeddingCatalogStcatTest`, `GalleryMergeTest` e casi aggiuntivi per OCR, fusione dei match e catalogo Pokémon.

### Modificato
- Export CSV CardTrader allineato a `POST /product_imports`: lingue con i codici (`en`, `it`, `fr`, `de`, `es`, `pt`, `jp`, `kr`, `zh-CN`), Damaged esportato come `Poor`, colonna `price_cents` in centesimi. Un file per gioco; su CardTrader va scelto aggiungi allo stock.
- Icona dell’app: medaglione con il robot e la sfera ciano, al posto dell’icona blu. È usata come icona adattiva (anche monocromatica) e compare in piccolo in **Informazioni**.
- Interfaccia ridisegnata in stile Material 3 con palette blu, in chiaro e in scuro: sfondi blu navy, primario blu acceso, accenti ciano. Tipografia, angoli, spaziatura, card e controlli sono allineati su tutte le schermate (home, scan e pannello impostazioni, conferma, collezione, informazioni, dialoghi e banner).
- Scelta del gioco prima dello scan: card grandi con i loghi Pokémon e Magic. Gli stessi loghi, in piccolo, compaiono nel selettore del gioco sulla schermata di scan.
- Versione dell’app impostata a `versionName` `1.0` (`versionCode` 1). Il salvataggio automatico in conferma parte solo se il match supera la soglia forte (74%); i match deboli restano da confermare a mano.

### Corretto
- Scan Magic che non trovava la carta (es. «Pioppo Tagliaerbacce», 83/150) con Scryfall in 429. Le chiamate sono in coda, distanziate (~10 al secondo), con cache anche dei «non trovato» e pausa dopo un HTTP 429 (`Retry-After`). In auto-scan, durante la pausa, compare «Scryfall occupato, riprovo…» e non partono nuove query. Lo stesso testo OCR non viene chiesto di nuovo a ogni frame.
- Il numero e il set si leggono su tutta la fascia bassa della carta (sinistra, centro e destra, circa il 15% in basso), non solo in basso a sinistra. Un intervallo di copyright (`1993-2008`, anche `01993-20O8`) e la riga Wizards non diventano più set o numero. `N3/150` torna `83/150`. Se il titolo in alto è quasi uguale a una frase del testo completo, si usa quella.
- Se il pack Magic non è installato, lo scan lo segnala: il riconoscimento resta sull’OCR.
- Dopo il salvataggio, tornando allo scan, lente e zoom restano quelli di prima (anche il pinch e il preset della lente). Il rapporto 1× che la fotocamera riporta al bind non sostituisce l’inquadratura.
- Aprendo **Impostazioni scan** l’anteprima non si blocca più e il riquadro della carta resta della stessa misura. Il pannello è un overlay: non ridimensiona la preview e non rifà il bind della fotocamera.
- Dopo il salvataggio, automatico o manuale, si torna subito allo scan dello stesso gioco, con la fotocamera pronta e l’auto-scan riarmato. Non si ripresenta la scelta Pokémon / Magic.
- Parser OCR molto più robusto (nomi, set, numeri di collezione) e lookup del catalogo migliorato per MTG e Pokémon.
- Fusione dei risultati (OCR + fingerprint + gallery neurale) e gestione dei pack neurali rivisti.
- Schermate di scansione, conferma e impostazioni riorganizzate (impostazioni generali scorrevoli: lingua, condizione, attesa auto-scan, fotocamera).
- README aggiornato con le istruzioni per il pack Pokémon.
- Dipendenza `androidx.exifinterface` 1.3.7.

### Note
- Cache immagini e file `.stcat` generati sono esclusi da Git (`.gitignore`).

## 2026-10-19

### Aggiunto
- Matching visivo ibrido: re-ranking con fingerprint e gallery on-device per MTG, pack neurale Milo opzionale, fix ONNX per pagine da 16 KB e diagnostica logcat per le scansioni fallite.
- MVP Android iniziale: scansione carte MTG/Pokémon, collezioni, export CSV per CardTrader.