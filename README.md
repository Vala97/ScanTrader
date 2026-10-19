# ScanTrader

App Android non ufficiale: scansiona carte **Magic: The Gathering** e **Pokémon**, salvale in collezioni locali ed esporta un CSV da caricare su CardTrader.

Non è affiliata a Wizards of the Coast, The Pokémon Company, Nintendo o CardTrader.

## Cosa fa (MVP)

1. Scan carta singola o sessione (più carte di fila)
2. OCR on-device (ML Kit) + lookup Scryfall / TCGdex
3. Conferma lingua, condizione, foil, quantità, collezione
4. Export CSV CardTrader (un file per gioco) tramite share Android

Nessun account, nessun backend, nessun costo di API. Unico costo eventuale: account sviluppatore Google Play (~25 USD) se pubblichi.

## Aprire il progetto

1. Installa [Android Studio](https://developer.android.com/studio) (include SDK e JDK).
2. File → Open → cartella `ScanTrader`.
3. Lascia che Gradle sincronizzi, poi Run su un telefono USB o un emulatore.

`minSdk` 26, `compileSdk` 35. JDK 17.

Da terminale, con SDK Android installato e `local.properties` (`sdk.dir=...`):

```bat
gradlew.bat :app:assembleDebug
```

## Import su CardTrader

Nella collezione tocca Condividi. Carica il CSV da CardTrader (un file per MTG e uno per Pokémon se la lista è mista). I prezzi restano da impostare sul sito.

## Cataloghi

- MTG: https://api.scryfall.com
- Pokémon: https://api.tcgdex.net
