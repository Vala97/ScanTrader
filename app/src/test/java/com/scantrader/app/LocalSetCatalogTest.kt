package com.scantrader.app

import com.scantrader.app.data.catalog.CatalogLookup
import com.scantrader.app.data.catalog.DownloadedMtgSet
import com.scantrader.app.data.catalog.LocalSetCatalog
import com.scantrader.app.data.catalog.MtgSetSource
import com.scantrader.app.data.catalog.PokemonSetSource
import com.scantrader.app.data.catalog.ScryfallSetDownloader
import com.scantrader.app.data.catalog.StoredPkmCard
import com.scantrader.app.data.catalog.TcgdexSetDownloader
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.network.scryfall.ScryfallApi
import com.scantrader.app.network.scryfall.ScryfallCard
import com.scantrader.app.network.scryfall.ScryfallClient
import com.scantrader.app.network.scryfall.ScryfallFace
import com.scantrader.app.network.scryfall.ScryfallGate
import com.scantrader.app.network.scryfall.ScryfallSearchResponse
import com.scantrader.app.network.scryfall.ScryfallSetApi
import com.scantrader.app.network.scryfall.ScryfallSetPage
import com.scantrader.app.network.tcgdex.TcgdexApi
import com.scantrader.app.network.tcgdex.TcgdexCard
import com.scantrader.app.network.tcgdex.TcgdexCardBrief
import com.scantrader.app.network.tcgdex.TcgdexSetApi
import com.scantrader.app.network.tcgdex.TcgdexSetDetails
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class LocalSetCatalogTest {
    @Test
    fun cachedFraSetResolvesItalianCollectorWithoutNetwork() = runBlocking {
        val logs = mutableListOf<String>()
        val idCalls = mutableListOf<String>()
        val (lookup, scope) = cachedLookup(logs, idCalls)
        try {
            val started = System.nanoTime()
            val hits = lookup.identify(
                Game.MTG,
                ParsedCardText(
                    nameGuess = "Teoria Perfezionata",
                    collectorNumber = "34",
                    setCode = "fra",
                    printedLang = "it",
                ),
            )
            val ms = (System.nanoTime() - started) / 1_000_000
            val hit = hits.single()
            assertEquals("Teoria Perfezionata", hit.name)
            assertEquals("Perfected Contemplation", hit.catalogName)
            assertEquals("fra", hit.setCode)
            assertEquals("34", hit.collectorNumber)
            assertEquals("it", hit.catalogLang)
            assertEquals("it", hit.printedLang)
            assertEquals("fra-34-it", hit.scryfallId)
            assertEquals("fra-34", hit.catalogScryfallId)
            assertEquals("foil+nonfoil", hit.finish)
            assertEquals(0.98f, hit.ocrScore)
            assertTrue(idCalls.isEmpty())
            assertTrue(logs.any { it.matches(Regex("""catalog local hit set=fra ms=\d+""")) })
            assertTrue("local hit took ${ms}ms", ms < 100)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun largeCachedSetStaysUnder100ms() = runBlocking {
        val dir = Files.createTempDirectory("set-catalog-large").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val local = LocalSetCatalog(dir, errorMtg(), errorPokemon(), scope)
        val cards = ArrayList<ScryfallCard>(3_200)
        for (n in 1..1_600) {
            if (n == 34) continue
            cards += ScryfallCard(
                id = "fra-$n",
                name = "Filler $n",
                set = "fra",
                collectorNumber = n.toString(),
                lang = "en",
            )
            cards += ScryfallCard(
                id = "fra-$n-it",
                name = "Filler $n",
                printedName = "Riempitivo $n",
                set = "fra",
                collectorNumber = n.toString(),
                lang = "it",
            )
        }
        cards += teoriaEn
        cards += teoriaIt
        local.installMtg("fra", cards)
        val lookup = CatalogLookup(unusedScryfall(), emptyTcgdex(), local)
        try {
            lookup.identify(
                Game.MTG,
                ParsedCardText(nameGuess = "Teoria Perfezionata", collectorNumber = "34", setCode = "fra"),
            )
            val started = System.nanoTime()
            val hits = lookup.identify(
                Game.MTG,
                ParsedCardText(
                    nameGuess = "Teoria Perfezionata",
                    collectorNumber = "034",
                    setCode = "fra",
                    printedLang = "it",
                ),
            )
            val ms = (System.nanoTime() - started) / 1_000_000
            assertEquals("fra-34-it", hits.single().scryfallId)
            assertTrue("large set local hit took ${ms}ms", ms < 100)
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun cachedSetMissFallsBackOnlineAndFuzzyStillWorks() = runBlocking {
        val idCalls = mutableListOf<String>()
        val (lookup, scope) = cachedLookup(mutableListOf(), idCalls) { set, number ->
            if (set == "eld" && number == "36") {
                ScryfallCard(
                    id = "eld-36",
                    name = "Elrond",
                    set = "eld",
                    collectorNumber = "36",
                    lang = "en",
                )
            } else {
                null
            }
        }
        try {
            val wrong = lookup.identify(
                Game.MTG,
                ParsedCardText(nameGuess = "Ravening Warg", collectorNumber = "36", setCode = "eld"),
            )
            // The cached printing is Elrond. The title disagrees, so it must not be accepted.
            assertTrue(wrong.isEmpty())
            assertTrue(idCalls.any { it == "eld/36" })

            idCalls.clear()
            val goblin = lookup.identify(
                Game.MTG,
                ParsedCardText(nameGuess = "Goblir", collectorNumber = "65", setCode = "hob"),
            )
            assertEquals("Down, Down to Goblin-town", goblin.single().name)
            assertEquals("65", goblin.single().collectorNumber)
            assertTrue(idCalls.any { it == "hob/65" })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun adventureFaceResolvesFromCache() = runBlocking {
        val (lookup, scope) = cachedLookup(mutableListOf(), mutableListOf())
        try {
            val hits = lookup.identify(
                Game.MTG,
                ParsedCardText(
                    nameGuess = "Maestro di Spada Invaghi",
                    collectorNumber = "105",
                    setCode = "eld",
                    printedLang = "it",
                ),
            )
            val hit = hits.single()
            assertEquals("Maestro di Spada Invaghito // Conquistare il Favore", hit.name)
            assertEquals("Smitten Swordmaster // Curry Favor", hit.catalogName)
            assertEquals("eld-105-it", hit.scryfallId)
            assertEquals("eld-105", hit.catalogScryfallId)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun reloadFromDiskAndKeepNameLookupStrict() = runBlocking {
        val dir = Files.createTempDirectory("set-catalog").toFile()
        val logs = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val original = LocalSetCatalog(dir, errorMtg(), errorPokemon(), scope, log = { logs += it })
        original.installMtg("fra", listOf(shock("1"), shock("2"), teoriaEn, teoriaIt))
        val reloaded = LocalSetCatalog(dir, errorMtg(), errorPokemon(), scope, log = { logs += it })
        val calls = mutableListOf<String>()
        val lookup = CatalogLookup(client(calls), emptyTcgdex(), reloaded)
        try {
            val byNumber = lookup.identify(
                Game.MTG,
                ParsedCardText(nameGuess = "Shock", collectorNumber = "2", setCode = "FRA"),
            )
            assertEquals("2", byNumber.single().collectorNumber)
            assertTrue(calls.isEmpty())

            val italian = lookup.identify(
                Game.MTG,
                ParsedCardText(
                    nameGuess = "Teoria Perfezionata",
                    collectorNumber = "34",
                    setCode = "fra",
                    printedLang = "it",
                ),
            )
            assertEquals("Teoria Perfezionata", italian.single().name)
            assertEquals("fra-34-it", italian.single().scryfallId)
            assertTrue(calls.isEmpty())

            val ambiguous = lookup.identify(
                Game.MTG,
                ParsedCardText(nameGuess = "Shock", collectorNumber = null, setCode = "fra"),
            )
            // Two Shock printings: do not guess locally.
            assertTrue(ambiguous.isEmpty() || calls.isNotEmpty())
            assertTrue(calls.any { it.startsWith("search:") || it.startsWith("fuzzy:") })
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun uniqueExactNameHitsLocallyAndIncompleteSetDoesNotGuess() = runBlocking {
        val dir = Files.createTempDirectory("set-catalog-name").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val local = LocalSetCatalog(dir, errorMtg(), errorPokemon(), scope)
        local.installMtg("fra", listOf(shock("1"), teoriaEn, teoriaIt), complete = true)
        val calls = mutableListOf<String>()
        val lookup = CatalogLookup(client(calls), emptyTcgdex(), local)
        try {
            calls.clear()
            val named = lookup.identify(
                Game.MTG,
                ParsedCardText(nameGuess = "Shock", collectorNumber = null, setCode = "fra"),
            )
            assertEquals("Shock", named.single().name)
            assertEquals("1", named.single().collectorNumber)
            assertEquals(0.96f, named.single().ocrScore)
            assertTrue(calls.isEmpty())

            val partial = LocalSetCatalog(dir, errorMtg(), errorPokemon(), scope)
            partial.installMtg("eld", listOf(swordEn, swordIt), complete = false)
            val calls2 = mutableListOf<String>()
            val lookup2 = CatalogLookup(client(calls2), emptyTcgdex(), partial)
            val byNumber = lookup2.identify(
                Game.MTG,
                ParsedCardText(
                    nameGuess = "Maestro di Spada Invaghito",
                    collectorNumber = "105",
                    setCode = "eld",
                    printedLang = "it",
                ),
            )
            assertEquals("eld-105-it", byNumber.single().scryfallId)
            assertTrue(calls2.isEmpty())
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun prefetchDoesNotBlockTheLookup() = runBlocking {
        val dir = Files.createTempDirectory("set-catalog-prefetch").toFile()
        val release = CompletableDeferred<DownloadedMtgSet>()
        val started = CompletableDeferred<Unit>()
        var downloads = 0
        val source = object : MtgSetSource {
            override suspend fun download(setCode: String): DownloadedMtgSet {
                downloads += 1
                started.complete(Unit)
                return release.await()
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val local = LocalSetCatalog(dir, source, errorPokemon(), scope)
        val calls = mutableListOf<String>()
        val lookup = CatalogLookup(client(calls), emptyTcgdex(), local)
        try {
            val first = withTimeout(2_000) {
                lookup.identify(
                    Game.MTG,
                    ParsedCardText(nameGuess = "Shock", collectorNumber = "1", setCode = "fra"),
                )
            }
            assertEquals("Shock", first.single().name)
            withTimeout(2_000) { started.await() }
            assertEquals(1, downloads)
            assertTrue(release.isActive)
            release.complete(DownloadedMtgSet(listOf(shock("1"), teoriaEn, teoriaIt), complete = true))
            withTimeout(2_000) { local.awaitIdle() }
            calls.clear()
            val second = lookup.identify(
                Game.MTG,
                ParsedCardText(
                    nameGuess = "Teoria Perfezionata",
                    collectorNumber = "34",
                    setCode = "fra",
                    printedLang = "it",
                ),
            )
            assertEquals("Teoria Perfezionata", second.single().name)
            assertEquals("fra-34-it", second.single().scryfallId)
            assertTrue(calls.isEmpty())
        } finally {
            if (release.isActive) release.cancel()
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun softCacheIsServedWhileRefreshRunsAndHardCacheIsDropped() = runBlocking {
        val dir = Files.createTempDirectory("set-catalog-expiry").toFile()
        var clock = 1_700_000_000_000L
        val downloaded = mutableListOf<String>()
        val source = object : MtgSetSource {
            override suspend fun download(setCode: String): DownloadedMtgSet {
                downloaded += setCode
                return DownloadedMtgSet(listOf(shock("1"), shock("9")), complete = true)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val local = LocalSetCatalog(dir, source, errorPokemon(), scope, now = { clock })
        try {
            local.installMtg("fra", listOf(shock("1")), fetchedAtMs = clock - LocalSetCatalog.SOFT_MS - 1_000)
            val stale = local.mtgSnapshot("fra")
            assertEquals(listOf("1"), stale?.cards?.map { it.collectorNumber })
            local.awaitIdle()
            assertEquals(listOf("fra"), downloaded)
            assertEquals(listOf("1", "9"), local.mtgSnapshot("fra")?.cards?.map { it.collectorNumber })

            downloaded.clear()
            local.installMtg(
                "fra",
                listOf(shock("1")),
                fetchedAtMs = clock - LocalSetCatalog.HARD_MS - 1_000,
            )
            assertNull(local.mtgSnapshot("fra"))
            local.awaitIdle()
            assertEquals(listOf("fra"), downloaded)
            assertEquals(2, local.mtgSnapshot("fra")?.cards?.size)
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun cachedPokemonSetResolvesWithoutNetwork() = runBlocking {
        val dir = Files.createTempDirectory("set-catalog-pkm").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val local = LocalSetCatalog(dir, errorMtg(), errorPokemon(), scope)
        local.installPokemon(
            setId = "sv10",
            lang = "en",
            cards = listOf(
                StoredPkmCard(
                    id = "sv10-012",
                    localId = "012",
                    name = "Destined Rival",
                    image = "https://assets.tcgdex.net/en/sv/sv10/012",
                ),
            ),
            setName = "Destined Rivals",
            officialCount = 182,
        )
        local.installPokemon(
            setId = "sv10",
            lang = "it",
            cards = listOf(
                StoredPkmCard(
                    id = "sv10-012",
                    localId = "012",
                    name = "Rivale Destinato",
                    image = "https://assets.tcgdex.net/it/sv/sv10/012",
                ),
            ),
            setName = "Rivali Predestinati",
            officialCount = 182,
        )
        val searches = mutableListOf<String>()
        val lookup = CatalogLookup(
            unusedScryfall(),
            object : TcgdexApi {
                override suspend fun search(lang: String, name: String?, localId: String?): List<TcgdexCardBrief> {
                    searches += "$lang:${name.orEmpty()}"
                    return emptyList()
                }

                override suspend fun card(lang: String, id: String): TcgdexCard = error("unused")
            },
            local,
        )
        try {
            val started = System.nanoTime()
            val hits = lookup.identify(
                Game.POKEMON,
                ParsedCardText(nameGuess = "Rivale Destinato", collectorNumber = "12", setCode = "dri"),
            )
            val ms = (System.nanoTime() - started) / 1_000_000
            val hit = hits.single()
            assertEquals("Rivale Destinato", hit.name)
            assertEquals("Destined Rival", hit.catalogName)
            assertEquals("sv10", hit.setCode)
            assertEquals("012", hit.collectorNumber)
            assertEquals("sv10-012", hit.tcgdexId)
            assertEquals("it", hit.catalogLang)
            assertTrue(searches.isEmpty())
            assertTrue("pokemon local hit took ${ms}ms", ms < 100)
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun setDownloaderFollowsPagesAndStops() = runBlocking {
        val pages = mutableListOf<Int>()
        val api = object : ScryfallSetApi {
            override suspend fun search(
                query: String,
                unique: String,
                includeMultilingual: Boolean,
                page: Int,
            ): ScryfallSetPage {
                pages += page
                assertEquals("set:fra", query)
                assertEquals("prints", unique)
                assertTrue(includeMultilingual)
                return if (page == 1) {
                    ScryfallSetPage(listOf(shock("1")), hasMore = true)
                } else {
                    ScryfallSetPage(listOf(teoriaIt), hasMore = false)
                }
            }
        }
        val downloaded = ScryfallSetDownloader(api, ScryfallGate(minSpacingMs = 0L, now = { 0L }), maxPages = 5)
            .download("FRA")
        assertEquals(listOf(1, 2), pages)
        assertTrue(downloaded.complete)
        assertEquals(listOf("1", "34"), downloaded.cards.map { it.collectorNumber })

        val truncated = ScryfallSetDownloader(
            api,
            ScryfallGate(minSpacingMs = 0L, now = { 0L }),
            maxPages = 1,
        ).download("fra")
        assertTrue(!truncated.complete)
        assertEquals(1, truncated.cards.size)
    }

    @Test
    fun pokemonSet404IsAnEmptyList() = runBlocking {
        val api = object : TcgdexSetApi {
            override suspend fun set(lang: String, id: String): TcgdexSetDetails = throw notFound()
        }
        val downloaded = TcgdexSetDownloader(api).download("sv10", "zh-cn")
        assertTrue(downloaded.cards.isEmpty())
    }

    private fun cachedLookup(
        logs: MutableList<String>,
        idCalls: MutableList<String>,
        onlineByNumber: (String, String) -> ScryfallCard? = { _, _ -> null },
    ): Pair<CatalogLookup, CoroutineScope> {
        val dir = Files.createTempDirectory("set-catalog-hit").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val local = LocalSetCatalog(dir, errorMtg(), errorPokemon(), scope, log = { logs += it })
        local.installMtg("fra", listOf(shock("1"), teoriaEn, teoriaIt, elrond))
        local.installMtg("eld", listOf(swordEn, swordIt, elrond))
        local.installMtg(
            "hob",
            listOf(
                ScryfallCard(
                    id = "hob-1",
                    name = "Elrond",
                    set = "hob",
                    collectorNumber = "1",
                    lang = "en",
                ),
            ),
        )
        val api = object : ScryfallApi {
            override suspend fun cardBySetAndNumber(set: String, number: String): ScryfallCard {
                idCalls += "$set/$number"
                onlineByNumber(set, number)?.let { return it }
                if (set == "hob" && number == "65") return goblinTown
                throw notFound()
            }

            override suspend fun cardBySetNumberLang(set: String, number: String, lang: String): ScryfallCard =
                error("lang $set/$number/$lang")

            override suspend fun cardById(id: String): ScryfallCard = error("unused")

            override suspend fun search(query: String, unique: String): ScryfallSearchResponse {
                idCalls += "search:$query"
                throw notFound()
            }

            override suspend fun namedFuzzy(fuzzy: String): ScryfallCard {
                idCalls += "fuzzy:$fuzzy"
                throw notFound()
            }

            override suspend fun namedFuzzyInSet(fuzzy: String, set: String): ScryfallCard {
                idCalls += "fuzzy:$set:$fuzzy"
                throw notFound()
            }
        }
        return CatalogLookup(ScryfallClient(api, ScryfallGate(minSpacingMs = 0L, now = { 0L })), emptyTcgdex(), local) to scope
    }

    private fun client(calls: MutableList<String>): ScryfallClient {
        val api = object : ScryfallApi {
            override suspend fun cardBySetAndNumber(set: String, number: String): ScryfallCard {
                calls += "$set/$number"
                if (set == "fra" && number == "1") return shock("1")
                throw notFound()
            }

            override suspend fun cardBySetNumberLang(set: String, number: String, lang: String): ScryfallCard =
                error("unused")

            override suspend fun cardById(id: String): ScryfallCard = error("unused")

            override suspend fun search(query: String, unique: String): ScryfallSearchResponse {
                calls += "search:$query"
                if (query.contains("Shock")) return ScryfallSearchResponse(listOf(shock("1")))
                throw notFound()
            }

            override suspend fun namedFuzzy(fuzzy: String): ScryfallCard {
                calls += "fuzzy:$fuzzy"
                throw notFound()
            }

            override suspend fun namedFuzzyInSet(fuzzy: String, set: String): ScryfallCard {
                calls += "fuzzy:$set:$fuzzy"
                throw notFound()
            }
        }
        return ScryfallClient(api, ScryfallGate(minSpacingMs = 0L, now = { 0L }))
    }

    private fun unusedScryfall(): ScryfallClient = ScryfallClient(
        object : ScryfallApi {
            override suspend fun cardBySetAndNumber(set: String, number: String): ScryfallCard = error("unused")
            override suspend fun cardBySetNumberLang(set: String, number: String, lang: String): ScryfallCard =
                error("unused")
            override suspend fun cardById(id: String): ScryfallCard = error("unused")
            override suspend fun search(query: String, unique: String): ScryfallSearchResponse = error("unused")
            override suspend fun namedFuzzy(fuzzy: String): ScryfallCard = error("unused")
            override suspend fun namedFuzzyInSet(fuzzy: String, set: String): ScryfallCard = error("unused")
        },
        ScryfallGate(minSpacingMs = 0L, now = { 0L }),
    )

    private fun emptyTcgdex(): TcgdexApi = object : TcgdexApi {
        override suspend fun search(lang: String, name: String?, localId: String?): List<TcgdexCardBrief> =
            emptyList()

        override suspend fun card(lang: String, id: String): TcgdexCard = error("unused")
    }

    private fun errorMtg(): MtgSetSource = object : MtgSetSource {
        override suspend fun download(setCode: String): DownloadedMtgSet = error("mtg download $setCode")
    }

    private fun errorPokemon(): PokemonSetSource = object : PokemonSetSource {
        override suspend fun download(setId: String, lang: String) = error("pkm download $setId $lang")
    }

    private fun notFound(): HttpException {
        val body = "no".toResponseBody("text/plain".toMediaType())
        return HttpException(Response.error<String>(404, body))
    }

    private fun shock(number: String) = ScryfallCard(
        id = "fra-$number",
        name = "Shock",
        set = "fra",
        setName = "Final Fantasy",
        collectorNumber = number,
        lang = "en",
        finishes = listOf("nonfoil", "foil"),
    )

    private val teoriaEn = ScryfallCard(
        id = "fra-34",
        name = "Perfected Contemplation",
        set = "fra",
        setName = "Final Fantasy",
        collectorNumber = "34",
        lang = "en",
        finishes = listOf("nonfoil", "foil"),
    )

    private val teoriaIt = teoriaEn.copy(
        id = "fra-34-it",
        lang = "it",
        printedName = "Teoria Perfezionata",
    )

    private val elrond = ScryfallCard(
        id = "eld-36",
        name = "Elrond",
        set = "eld",
        collectorNumber = "36",
        lang = "en",
    )

    private val swordEn = ScryfallCard(
        id = "eld-105",
        name = "Smitten Swordmaster // Curry Favor",
        set = "eld",
        setName = "Throne of Eldraine",
        collectorNumber = "105",
        lang = "en",
        cardFaces = listOf(
            ScryfallFace(name = "Smitten Swordmaster"),
            ScryfallFace(name = "Curry Favor"),
        ),
    )

    private val swordIt = swordEn.copy(
        id = "eld-105-it",
        lang = "it",
        cardFaces = listOf(
            ScryfallFace(name = "Smitten Swordmaster", printedName = "Maestro di Spada Invaghito"),
            ScryfallFace(name = "Curry Favor", printedName = "Conquistare il Favore"),
        ),
    )

    private val goblinTown = ScryfallCard(
        id = "goblin-town",
        name = "Down, Down to Goblin-town",
        set = "hob",
        setName = "The Hobbit",
        collectorNumber = "65",
        lang = "en",
    )
}
