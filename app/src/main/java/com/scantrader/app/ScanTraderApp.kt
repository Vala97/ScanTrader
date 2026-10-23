package com.scantrader.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.scantrader.app.data.catalog.CatalogLookup
import com.scantrader.app.data.db.AppDatabase
import com.scantrader.app.data.prefs.UserPreferences
import com.scantrader.app.data.repo.CardRepository
import com.scantrader.app.network.NetworkModule
import com.scantrader.app.network.scryfall.ScryfallApi
import com.scantrader.app.network.tcgdex.TcgdexApi
import com.scantrader.app.scan.CardOcr
import com.scantrader.app.scan.ScanSession
import com.scantrader.app.vision.GalleryMatcher
import com.scantrader.app.vision.NeuralPackManager
import com.scantrader.app.vision.VisualRanker
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
    lateinit var galleryMatcher: GalleryMatcher
        private set
    lateinit var scryfall: ScryfallApi
        private set
    val scanSession = ScanSession()

    private val http by lazy { NetworkModule.client() }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.create(this)
        repository = CardRepository(database)
        scryfall = NetworkModule.retrofit("https://api.scryfall.com/", http)
            .create(ScryfallApi::class.java)
        val tcgdex = NetworkModule.retrofit("https://api.tcgdex.net/", http)
            .create(TcgdexApi::class.java)
        catalog = CatalogLookup(scryfall, tcgdex)
        prefs = UserPreferences(this)
        ocr = CardOcr()
        visualRanker = VisualRanker(http)
        neuralPack = NeuralPackManager(this, http)
        galleryMatcher = GalleryMatcher(neuralPack, scryfall, tcgdex)
        appScope.launch {
            val defaults = prefs.currentDefaults()
            scanSession.zoomRatio = defaults.zoomRatio
            scanSession.cameraId = defaults.cameraId
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
