# Changelog

Tutte le modifiche rilevanti a ScanTrader sono documentate in questo file.
Il formato segue [Keep a Changelog](https://keepachangelog.com/it-IT/1.1.0/).

## [Non rilasciato]

### Aggiunto
- Riconoscimento neurale **Pokémon**: import di un pack `.stcat` (gallery Milo su catalogo TCGdex EN) da **Impostazioni → Riconoscimento Pokémon → Importa .stcat**.
- Strumento `tools/build_pokemon_stcat` (Python) per generare `pokemon.stcat`, con modalità smoke su pochi set.
- Selezione della fotocamera posteriore da usare per la scansione (`CameraDevices`).
- Conversione dei frame CameraX in bitmap dritte con gestione rotazione/EXIF (`CameraBitmaps`), così il ritaglio colpisce titolo e numero di collezione.
- Auto-Scan con salvataggio automatico in collezione dopo un'attesa configurabile.
- Nuovi test: `CameraBitmapsTest`, `EmbeddingCatalogStcatTest`, `GalleryMergeTest` e casi aggiuntivi per OCR, fusione dei match e catalogo Pokémon.

### Modificato
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