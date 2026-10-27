# Pubblicare i cataloghi Pokémon (`.stcat`)

L’app scarica l’indice da un URL stabile:

`https://github.com/Vala97/ScanTrader/releases/latest/download/stcat-index.json`

`releases/latest` è l’ultimo release **non** prerelease. Il release dei cataloghi (tag consigliato `stcat`) deve restare quello. Un release dell’app non va pubblicato come release normale: l’aggiornamento in-app usa il tag fisso `app-update`, creato con `--prerelease`, così `stcat` resta `releases/latest`. I file `.stcat` nell’indice usano un URL fissato sul tag, così un indice già installato continua a puntare allo stesso file.

Lo script **non** crea il release e **non** chiama `gh`. Stampa solo i comandi.

## Da dove arrivano i file

Nel repository non ci sono `.stcat`: sono in `.gitignore`. Si generano con [`tools/build_pokemon_stcat`](../build_pokemon_stcat/README.md) (Python 3.10+, immagini TCGdex in inglese, modello Milo ONNX). Milo è AGPL-3.0 ([HanClinto/milo](https://huggingface.co/HanClinto/milo)). La cache immagini non va ridistribuita; si pubblica solo il `.stcat` e l’indice.

Catalogo unico consigliato:

| Campo | Valore |
| --- | --- |
| File | `pokemon-en.stcat` (l’output del builder, rinominato) |
| id | `pokemon-en` |
| Nome in app | Pokémon (inglese) |
| Tag | `stcat` |

```bat
cd tools\build_pokemon_stcat
python build_pokemon_stcat.py --out pokemon.stcat
copy pokemon.stcat ..\publish_stcat\pokemon-en.stcat
```

Poi, da `tools\publish_stcat` (serve solo Python 3, nessun pacchetto). Il nome del file, senza estensione, è l’id:

```bat
python publish_stcat.py --version 2026.10.26 pokemon-en.stcat
```

Senza `id=`, l’id è il nome del file senza estensione. `--name pokemon-en=Pokémon (inglese)` cambia l’etichetta; per `pokemon-en` il nome italiano è già il default. Più file si elencano uno dopo l’altro. `--version` è la stessa per tutti i file di quella esecuzione (es. `2026.10.26` o `1.2`).

Lo script scrive `stcat-index.json` accanto a sé e stampa due comandi. Eseguirli a mano, con `gh` già autenticato sul repo `Vala97/ScanTrader`:

```bat
gh release create stcat --repo Vala97/ScanTrader --title "Cataloghi Pokémon 2026.10.26" --notes "Cataloghi .stcat per ScanTrader" pokemon-en.stcat stcat-index.json
```

Se il release `stcat` c’è già:

```bat
gh release upload stcat --repo Vala97/ScanTrader --clobber pokemon-en.stcat stcat-index.json
```

I percorsi nel comando vero sono quelli stampati dallo script (i file `.stcat` restano dove li hai generati).

## Cosa contiene l’indice

```json
{
  "catalogs": [
    {
      "id": "pokemon-en",
      "name": "Pokémon (inglese)",
      "version": "2026.10.26",
      "size": 12345678,
      "sha256": "…64 caratteri hex…",
      "url": "https://github.com/Vala97/ScanTrader/releases/download/stcat/pokemon-en.stcat"
    }
  ]
}
```

L’app ignora una riga senza `id`, `url` https o `sha256` di 64 cifre hex. Dopo il download controlla lo sha256 e che il file sia uno `.stcat` valido; se uno dei due fallisce, il catalogo già installato non viene sostituito.

## Cosa caricare

Sul release GitHub, come asset:

1. Ogni `.stcat` generato (per il catalogo inglese: il file prodotto da `build_pokemon_stcat.py`, pubblicato col nome `pokemon-en.stcat`).
2. `stcat-index.json` prodotto da questo script, con `version`, `size`, `sha256` e `url` di quegli stessi file.

Non committare i binari né l’indice nel git.
