# ScanTrader

App Android non ufficiale: scansiona carte **Magic: The Gathering** e **Pokémon**, salvale in collezioni locali ed esporta un CSV da caricare su CardTrader.

Non è affiliata a Wizards of the Coast, The Pokémon Company, Nintendo o CardTrader.

**V1.0** (build 1). La stessa stringa è in **Informazioni** (`Versione V1.0 (build 1)`; le build debug aggiungono `· debug`).

## Cosa fa

- **Scelta del gioco** con i loghi Pokémon e Magic, prima dello scan e nel selettore sulla schermata di scan.
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
- Interfaccia **Material 3 blu** (chiaro e scuro). L’icona è il medaglione blu e oro (cristallo, teschio, due uccelli e i simboli elementali) su fondo blu navy; la stessa compare in **Informazioni**.

Nessun account, nessun backend a pagamento. Unico costo eventuale: account sviluppatore Google Play (~25 USD) se pubblichi.

### Pack neurali

- **MTG**: **Impostazioni** → download da Hugging Face `HanClinto/milo` (~60 MB, AGPL-3.0). Senza pack il riconoscimento resta sull’OCR e lo scan lo segnala.
- **Pokémon**: genera `pokemon.stcat` con [`tools/build_pokemon_stcat`](tools/build_pokemon_stcat/README.md), poi **Impostazioni → Riconoscimento Pokémon → Importa .stcat**.

## Aprire il progetto

1. Installa [Android Studio](https://developer.android.com/studio) (include SDK e JDK).
2. File → Open → cartella `ScanTrader`.
3. Lascia che Gradle sincronizzi, poi Run su un telefono USB o un emulatore.

`minSdk` 26, `compileSdk` 35. La build vuole **JDK 17** (sorgenti e bytecode), Android Gradle Plugin **8.13.2** e Gradle **8.13** (lo wrapper è già nel repo).

Da terminale, con SDK Android installato e `local.properties` (`sdk.dir=...`):

```bat
gradlew.bat :app:assembleDebug
```

## Import su CardTrader

Nella collezione tocca Condividi. Esce **un file per gioco** (`nome-mtg.csv`, `nome-pokemon.csv`). Se la lista mescola Magic e Pokémon, la condivisione allega entrambi.

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
