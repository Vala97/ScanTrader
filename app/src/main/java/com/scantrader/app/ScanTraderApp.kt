package com.scantrader.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.scantrader.app.data.catalog.CardLanguageResolver
import com.scantrader.app.data.export.BlueprintNames
import com.scantrader.app.data.catalog.CardTraderPrices
import com.scantrader.app.data.catalog.CatalogLookup
import com.scantrader.app.data.catalog.LocalSetCatalog
import com.scantrader.app.data.catalog.ScryfallEurPrices
import com.scantrader.app.data.catalog.ScryfallSetDownloader
import com.scantrader.app.data.catalog.TcgdexSetDownloader
import com.scantrader.app.data.db.AppDatabase
import com.scantrader.app.data.prefs.SecretStore
import com.scantrader.app.data.prefs.UserPreferences
import com.scantrader.app.network.cardtrader.CardTraderApi
import com.scantrader.app.data.repo.CardRepository
import com.scantrader.app.network.NetworkModule
import com.scantrader.app.network.scryfall.ScryfallApi
import com.scantrader.app.network.scryfall.ScryfallClient
import com.scantrader.app.network.scryfall.ScryfallSetApi
import com.scantrader.app.network.tcgdex.CachingTcgdexApi
import com.scantrader.app.network.tcgdex.TcgdexApi
import com.scantrader.app.network.tcgdex.TcgdexResponseCache
import com.scantrader.app.network.tcgdex.TcgdexSetApi
import java.io.File
import com.scantrader.app.scan.CardOcr
import com.scantrader.app.scan.ScanSession
import com.scantrader.app.update.AppUpdateController
import com.scantrader.app.update.DisabledUpdateSource
import com.scantrader.app.update.GithubReleaseUpdateSource
import com.scantrader.app.update.PackageUpdateInstaller
import com.scantrader.app.vision.GalleryMatcher
import com.scantrader.app.vision.NeuralPackManager
import com.scantrader.app.vision.PokemonCatalogController
import com.scantrader.app.vision.VisualRanker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ScanTraderApp : Application(), ImageLoaderFactory {
    lateinit var database: AppDatabase
        private set
    lateinit var repository: CardRepository
        private set
    lateinit var catalog: CatalogLookup
        private set
    lateinit var prefs: UserPreferences
        private set
    lateinit var ocr: CardOcr
        private set
    lateinit var visualRanker: VisualRanker
        private set
    lateinit var neuralPack: NeuralPackManager
        private set
    lateinit var pokemonCatalogs: PokemonCatalogController
        private set
    lateinit var galleryMatcher: GalleryMatcher
        private set
    lateinit var scryfall: ScryfallClient
        private set
    lateinit var scryfallEur: ScryfallEurPrices
        private set
    lateinit var secrets: SecretStore
        private set
    lateinit var cardTraderPrices: CardTraderPrices
        private set
    lateinit var languageResolver: CardLanguageResolver
        private set
    lateinit var blueprintNames: BlueprintNames
        private set
    lateinit var appUpdates: AppUpdateController
        private set
    val scanSession = ScanSession()

    private val http by lazy { NetworkModule.client() }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.create(this)
        repository = CardRepository(database)
        val scryfallRetrofit = NetworkModule.retrofit("https://api.scryfall.com/", http)
        scryfall = ScryfallClient(scryfallRetrofit.create(ScryfallApi::class.java))
        val tcgdexRetrofit = NetworkModule.retrofit("https://api.tcgdex.net/", http)
        val tcgdex = CachingTcgdexApi(
            tcgdexRetrofit.create(TcgdexApi::class.java),
            TcgdexResponseCache(File(cacheDir, "tcgdex")),
        )
        val localSets = LocalSetCatalog(
            dir = File(cacheDir, "set-catalog"),
            mtgSource = ScryfallSetDownloader(
                scryfallRetrofit.create(ScryfallSetApi::class.java),
                scryfall.gate,
            ),
            pokemonSource = TcgdexSetDownloader(tcgdexRetrofit.create(TcgdexSetApi::class.java)),
            scope = appScope,
        )
        catalog = CatalogLookup(scryfall, tcgdex, localSets)
        scryfallEur = ScryfallEurPrices(scryfall)
        secrets = SecretStore(this)
        cardTraderPrices = CardTraderPrices(
            NetworkModule.retrofit(
                "https://api.cardtrader.com/api/v2/",
                NetworkModule.cardTraderClient { secrets.getCardTraderToken() },
            ).create(CardTraderApi::class.java),
            token = { secrets.getCardTraderToken() },
        )
        languageResolver = CardLanguageResolver(scryfall, tcgdex)
        blueprintNames = BlueprintNames(scryfall, tcgdex) { repository.updateCard(it) }
        prefs = UserPreferences(this)
        ocr = CardOcr()
        visualRanker = VisualRanker(http)
        neuralPack = NeuralPackManager(this, http)
        pokemonCatalogs = PokemonCatalogController(
            visionDir = File(filesDir, "vision"),
            baseHttp = http,
            scope = appScope,
            onChanged = { neuralPack.ensurePokemonReady() },
        )
        galleryMatcher = GalleryMatcher(neuralPack, scryfall, tcgdex)
        val updateClient = http.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val updateSource = if (BuildConfig.GITHUB_SELF_UPDATE) {
            GithubReleaseUpdateSource(updateClient)
        } else {
            DisabledUpdateSource
        }
        appUpdates = AppUpdateController(
            context = this,
            source = updateSource,
            prefs = prefs,
            client = updateClient,
            installer = PackageUpdateInstaller(this),
            versionCode = BuildConfig.VERSION_CODE,
            sdkInt = android.os.Build.VERSION.SDK_INT,
            scope = appScope,
        )
        appScope.launch {
            val defaults = prefs.currentDefaults()
            scanSession.zoomRatio = defaults.zoomRatio
            scanSession.cameraId = defaults.cameraId
            scanSession.lensKey = defaults.lensKey
            scanSession.language = defaults.language
            scanSession.condition = defaults.condition
            // Warm neural pack if already downloaded (no network).
            neuralPack.ensureLoaded()
        }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { http }
            .crossfade(true)
            .respectCacheHeaders(false)
            .build()
}
