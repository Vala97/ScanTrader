package com.scantrader.app.network.tcgdex

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Pokémon catalog for en, it, fr, de, es, pt, ja, ko, zh-cn and zh-tw.
 * The same card id is shared by English and Italian. Japanese and Chinese are separate sets.
 */
interface TcgdexApi {
    @GET("v2/{lang}/cards")
    suspend fun search(
        @Path("lang") lang: String,
        @Query("name") name: String? = null,
        @Query("localId") localId: String? = null,
    ): List<TcgdexCardBrief>

    @GET("v2/{lang}/cards/{id}")
    suspend fun card(
        @Path("lang") lang: String,
        @Path("id") id: String,
    ): TcgdexCard
}

/** One request returns every card in a set for that language (briefs, not full pricing). */
interface TcgdexSetApi {
    @GET("v2/{lang}/sets/{id}")
    suspend fun set(
        @Path("lang") lang: String,
        @Path("id") id: String,
    ): TcgdexSetDetails
}

data class TcgdexSetDetails(
    val id: String = "",
    val name: String = "",
    val cardCount: TcgdexCardCount? = null,
    val cards: List<TcgdexCardBrief> = emptyList(),
)

data class TcgdexCardBrief(
    val id: String,
    val localId: String = "",
    val name: String = "",
    val image: String? = null,
)

data class TcgdexCard(
    val id: String,
    val localId: String = "",
    val name: String = "",
    val image: String? = null,
    val set: TcgdexSet? = null,
    val pricing: TcgdexPricing? = null,
)

data class TcgdexSet(
    val id: String = "",
    val name: String = "",
    val cardCount: TcgdexCardCount? = null,
)

data class TcgdexCardCount(
    val official: Int? = null,
    val total: Int? = null,
)

data class TcgdexPricing(
    val cardmarket: TcgdexCardmarket? = null,
)

data class TcgdexCardmarket(
    val avg: Double? = null,
    @SerializedName("avg-holo") val avgHolo: Double? = null,
    val trend: Double? = null,
    @SerializedName("trend-holo") val trendHolo: Double? = null,
    val low: Double? = null,
)
