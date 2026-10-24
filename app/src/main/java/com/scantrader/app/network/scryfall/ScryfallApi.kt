package com.scantrader.app.network.scryfall

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface ScryfallApi {
    @GET("cards/{set}/{number}")
    suspend fun cardBySetAndNumber(
        @Path("set") set: String,
        @Path("number") number: String,
    ): ScryfallCard

    /** Localized printing (`printed_name`) for a set and collector number. */
    @GET("cards/{set}/{number}/{lang}")
    suspend fun cardBySetNumberLang(
        @Path("set") set: String,
        @Path("number") number: String,
        @Path("lang") lang: String,
    ): ScryfallCard

    @GET("cards/{id}")
    suspend fun cardById(
        @Path("id") id: String,
    ): ScryfallCard

    @GET("cards/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("unique") unique: String = "prints",
    ): ScryfallSearchResponse

    /** One closest English (or printed) card. 404 when nothing is close. */
    @GET("cards/named")
    suspend fun namedFuzzy(
        @Query("fuzzy") fuzzy: String,
    ): ScryfallCard
}

data class ScryfallSearchResponse(
    val data: List<ScryfallCard> = emptyList(),
)

data class ScryfallCard(
    val id: String,
    val name: String,
    /** Localized title when searching a non-English print (e.g. Italian). */
    @SerializedName("printed_name") val printedName: String? = null,
    val set: String,
    @SerializedName("set_name") val setName: String? = null,
    @SerializedName("collector_number") val collectorNumber: String = "",
    /** Scryfall language code of this printing (`en`, `it`, …). */
    val lang: String? = null,
    /** Shared by every printing of the same card. Used to find an EUR price elsewhere. */
    @SerializedName("oracle_id") val oracleId: String? = null,
    /** `YYYY-MM-DD`. Tie-break when several prints share the cheapest EUR. */
    @SerializedName("released_at") val releasedAt: String? = null,
    @SerializedName("image_uris") val imageUris: ScryfallImages? = null,
    @SerializedName("card_faces") val cardFaces: List<ScryfallFace>? = null,
    @SerializedName("tcgplayer_id") val tcgplayerId: Long? = null,
    val prices: ScryfallPrices? = null,
) {
    /**
     * Full card face for UI feedback (confirm / collection) and visual ranking.
     * Prefer `normal`/`large` over `art_crop` so the user sees the real matched print.
     */
    fun bestImageUrl(): String? {
        val fromUris = imageUris?.normal
            ?: imageUris?.large
            ?: imageUris?.small
            ?: imageUris?.artCrop
            ?: cardFaces?.firstOrNull()?.imageUris?.normal
            ?: cardFaces?.firstOrNull()?.imageUris?.large
            ?: cardFaces?.firstOrNull()?.imageUris?.small
            ?: cardFaces?.firstOrNull()?.imageUris?.artCrop
        if (!fromUris.isNullOrBlank()) return fromUris
        // CDN fallback from Scryfall id (works even if image_uris was omitted).
        if (id.length < 2) return null
        return "https://cards.scryfall.io/normal/front/${id[0]}/${id[1]}/$id.jpg"
    }
}

data class ScryfallPrices(
    val usd: String? = null,
    val eur: String? = null,
    @SerializedName("eur_foil") val eurFoil: String? = null,
    @SerializedName("usd_foil") val usdFoil: String? = null,
)

data class ScryfallImages(
    val small: String? = null,
    val normal: String? = null,
    val large: String? = null,
    @SerializedName("art_crop") val artCrop: String? = null,
)

data class ScryfallFace(
    @SerializedName("image_uris") val imageUris: ScryfallImages? = null,
)
