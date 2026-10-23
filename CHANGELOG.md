# Changelog

Tutte le modifiche rilevanti a ScanTrader sono documentate in questo file.
Il formato segue [Keep a Changelog](https://keepachangelog.com/it-IT/1.1.0/).

## [Non rilasciato]

### Aggiunto
- Scelta della **lente** nel pannello Impostazioni scan (e in Impostazioni): Automatica, Principale, Ultra-grandangolo, Teleobiettivo. Si elencano le fotocamere posteriori, comprese le lenti fisiche della multi-camera logica (nome in base alla focale). Se una lente fisica non si può aprire, si usano gli zoom 0,5× / 1× / 2× / 5× dentro l’intervallo dello zoom. Con una sola scelta il selettore resta nascosto. La preferenza è salvata; torcia e auto-scan restano attivi.
- Schermata **Informazioni**: versione visibile letta da `BuildConfig` (`Versione V1.0 (build 1)`). Le build debug aggiungono il suffisso `· debug`.
- Riconoscimento neurale **Pokémon**: import di un pack `.stcat` (gallery Milo su catalogo TCGdex EN) da **Impostazioni → Riconoscimento Pokémon → Importa .stcat**.
- Strumento `tools/build_pokemon_stcat` (Python) per generare `pokemon.stcat`, con modalità smoke su pochi set.
- Selezione della fotocamera posteriore da usare per la scansione (`CameraDevices`).
- Conversione dei frame CameraX in bitmap dritte con gestione rotazione/EXIF (`CameraBitmaps`), così il ritaglio colpisce titolo e numero di collezione.
- Auto-Scan con salvataggio automatico in collezione dopo un'attesa configurabile.
- Nuovi test: `CameraBitmapsTest`, `EmbeddingCatalogStcatTest`, `GalleryMergeTest` e casi aggiuntivi per OCR, fusione dei match e catalogo Pokémon.

### Modificato
- Interfaccia ridisegnata in stile Material 3 con palette blu, in chiaro e in scuro: sfondi blu navy, primario blu acceso, accenti ciano. Tipografia, angoli, spaziatura, card e controlli sono allineati su tutte le schermate (home, scan e pannello impostazioni, conferma, collezione, informazioni, dialoghi e banner).
- Scelta del gioco prima dello scan: card grandi con i loghi Pokémon e Magic. Gli stessi loghi, in piccolo, compaiono nel selettore del gioco sulla schermata di scan.
- Versione dell’app impostata a `versionName` `1.0` (`versionCode` 1). Il salvataggio automatico in conferma parte solo se il match supera la soglia forte (74%); i match deboli restano da confermare a mano.

### Corretto
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