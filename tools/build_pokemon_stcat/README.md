# Build Pokémon neural gallery (`pokemon.stcat`)

Creates an on-device Milo embedding index for **TCGdex English** cards (same art covers most EU-language prints).

## Requirements

- Python 3.10+
- ~2 GB free disk for image cache (full EN catalog)
- Time: smoke subset minutes; full EN catalog can take 1–3+ hours depending on network/CPU

```bat
cd tools\build_pokemon_stcat
pip install -r requirements.txt
```

## Smoke (Base + Ascended Heroes)

```bat
python build_pokemon_stcat.py --sets base1,sv10 --out pokemon-smoke.stcat
```

## Full EN catalog

```bat
python build_pokemon_stcat.py --out pokemon.stcat
```

Outputs:

- `pokemon.stcat` — copy/import into the app
- `cache/milo.onnx` — shared with the Android pack
- `cache/images/` — downloaded TCGdex webp (reused on rebuild)

## Install on device

**Settings → Riconoscimento Pokémon → Importa .stcat**, or:

```bat
adb push pokemon.stcat /sdcard/Download/pokemon.stcat
```

Then import from Downloads in the app. Requires the Milo model (`milo.onnx`) — install the MTG neural pack once, or the import flow will download the model if missing.

To ship the same file inside the app, rename it (suggested id `pokemon-en`) and follow [`tools/publish_stcat`](../publish_stcat/README.md). That script only writes `stcat-index.json` and prints the `gh` commands; it does not upload.

## License

Milo ONNX is **AGPL-3.0** ([HanClinto/milo](https://huggingface.co/HanClinto/milo)). The generated gallery is a derived index; keep the pack opt-in like the MTG gallery. Card images come from TCGdex CDN for build only — do not redistribute the image cache.
