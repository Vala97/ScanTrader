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

    @GET("cards/{id}")
    suspend fun cardById(
        @Path("id") id: String,
    ): ScryfallCard

    @GET("cards/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("unique") unique: String = "prints",
    ): ScryfallSearchResponse
}

data class ScryfallSearchResponse(
    val data: List<ScryfallCard> = emptyList(),
)

data class ScryfallCard(
    val id: String,
    val name: String,
    val set: String,
    @SerializedName("set_name") val setName: String? = null,
    @SerializedName("collector_number") val collectorNumber: String = "",
    @SerializedName("image_uris") val imageUris: ScryfallImages? = null,
    @SerializedName("card_faces") val cardFaces: List<ScryfallFace>? = null,
    @SerializedName("tcgplayer_id") val tcgplayerId: Long? = null,
    val prices: ScryfallPrices? = null,
) {
    fun bestImageUrl(): String? {
        // Prefer art_crop for reverse-image ranking (illustration only).
        val fromUris = imageUris?.artCrop
            ?: imageUris?.normal
            ?: imageUris?.large
            ?: imageUris?.small
            ?: cardFaces?.firstOrNull()?.imageUris?.artCrop
            ?: cardFaces?.firstOrNull()?.imageUris?.normal
            ?: cardFaces?.firstOrNull()?.imageUris?.large
            ?: cardFaces?.firstOrNull()?.imageUris?.small
        if (!fromUris.isNullOrBlank()) return fromUris
        // CDN fallback from Scryfall id (works even if image_uris was omitted).
        if (id.length < 2) return null
        return "https://cards.scryfall.io/art_crop/front/${id[0]}/${id[1]}/$id.jpg"
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
