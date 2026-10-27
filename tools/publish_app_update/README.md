# Pubblicare un aggiornamento dell’app

L’app legge un indice a URL fisso, non `releases/latest`:

`https://github.com/Vala97/ScanTrader/releases/download/app-update/app-update.json`

`releases/latest` resta riservato ai cataloghi Pokémon (tag `stcat`, release normale). Il release `app-update` va creato con `--prerelease`. Se diventa un release normale, i telefoni non trovano più `stcat-index.json`.

Lo script **non** crea il release e **non** chiama `gh`. Stampa solo i comandi. Non genera né legge un keystore.

## Versione

`app/version.properties` è l’unico posto dove stanno `VERSION_CODE` e `VERSION_NAME`. L’APK di release li prende da lì. Lo script, in ordine:

1. `--version-code` e `--version-name` insieme;
2. altrimenti `output-metadata.json` accanto all’APK (lo scrive Gradle);
3. altrimenti `app/version.properties`.

Prima di un tag `v*` alza `VERSION_CODE` (intero, maggiore di quello già in giro) e, se serve, `VERSION_NAME`.

## Comando

Da qualsiasi cartella, con l’APK già compilato (firmato, se lo distribuisci):

```bat
python tools\publish_app_update\publish_app_update.py app\build\outputs\apk\release\app-release.apk --notes "Correzioni allo scan e nuovo export."
```

Scrive `app-update.json` nella cartella corrente e, se il file non si chiama già `ScanTrader.apk`, ne copia uno con quel nome (è il nome dell’asset su GitHub, quello dentro `apkUrl`). `--min-sdk 26` è facoltativo. `--asset-name` cambia il nome pubblicato.

Poi esegui a mano i comandi `gh` stampati, con `gh` autenticato. Il primo crea `app-update` come prerelease. I successivi usano `gh release upload app-update --clobber` e `gh release edit app-update --prerelease`.

## Cosa contiene l’indice

```json
{
  "versionCode": 2,
  "versionName": "1.1",
  "apkUrl": "https://github.com/Vala97/ScanTrader/releases/download/app-update/ScanTrader.apk",
  "sha256": "…64 caratteri hex…",
  "notes": "Testo italiano nel dialogo.",
  "minSdk": 26
}
```

`minSdk` si può omettere. L’app scarta URL non https, host diversi da `github.com` e `*.githubusercontent.com`, e hash che non sono 64 cifre hex. Dopo il download controlla lo sha256: se non torna, il file parziale si cancella e non si installa nulla.

## Workflow

Su un tag `v*` (per esempio `v1.1`), `.github/workflows/release-apk.yml` compila l’APK firmato e carica `ScanTrader.apk` più `app-update.json` sul release `app-update`. Senza i quattro secret di firma il job di build non parte. I secret e il keystore non vanno nel git: vedi il README del repository.
