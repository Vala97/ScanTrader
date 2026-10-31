package com.scantrader.app.network.cardtrader

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

interface CardTraderApi {
    @GET("expansions")
    suspend fun expansions(): List<CtExpansion>

    @GET("blueprints/export")
    suspend fun blueprints(@Query("expansion_id") expansionId: Int): List<CtBlueprint>

    /** Keys are blueprint ids. Each value is the cheapest listings (up to 25). */
    @GET("marketplace/products")
    suspend fun products(@Query("blueprint_id") blueprintId: Int): Map<String, List<CtProduct>>
}

data class CtExpansion(
    val id: Int,
    @SerializedName("game_id") val gameId: Int = 0,
    val code: String = "",
    val name: String = "",
)

data class CtBlueprint(
    val id: Int,
    val name: String = "",
    @SerializedName("expansion_id") val expansionId: Int? = null,
    @SerializedName("scryfall_id") val scryfallId: String? = null,
    val version: String? = null,
    @SerializedName("game_id") val gameId: Int? = null,
    @SerializedName("fixed_properties") val fixedProperties: Map<String, Any?>? = null,
    @SerializedName("image_url") val imageUrl: String? = null,
)

data class CtProduct(
    val id: Long = 0,
    @SerializedName("blueprint_id") val blueprintId: Long = 0,
    val price: CtMoney? = null,
    @SerializedName("properties_hash") val properties: CtProperties? = null,
)

data class CtMoney(
    val cents: Int = 0,
    val currency: String = "",
)

data class CtProperties(
    val condition: String? = null,
    @SerializedName("mtg_language") val mtgLanguage: String? = null,
    @SerializedName("mtg_foil") val mtgFoil: Boolean? = null,
    @SerializedName("pokemon_language") val pokemonLanguage: String? = null,
    @SerializedName("riftbound_language") val riftboundLanguage: String? = null,
    @SerializedName("riftbound_foil") val riftboundFoil: Boolean? = null,
    @SerializedName("onepiece_language") val onepieceLanguage: String? = null,
    @SerializedName("dragonball_language") val dragonballLanguage: String? = null,
    @SerializedName("yugioh_language") val yugiohLanguage: String? = null,
    @SerializedName("union_arena_language") val unionArenaLanguage: String? = null,
) {
    fun otherLanguage(): String? = onepieceLanguage ?: dragonballLanguage ?: yugiohLanguage ?: unionArenaLanguage
}

object CardTraderCatalog {
    fun expansionId(expansions: List<CtExpansion>, setCode: String, gameId: Int = 1): Int? {
        val code = setCode.trim()
        if (code.isEmpty()) return null
        return expansions.firstOrNull { it.gameId == gameId && it.code.equals(code, ignoreCase = true) }?.id
            ?: expansions.firstOrNull { it.code.equals(code, ignoreCase = true) }?.id
    }

    fun blueprintId(blueprints: List<CtBlueprint>, scryfallIds: List<String>): Int? {
        val wanted = scryfallIds.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return null
        return blueprints.firstOrNull { blueprint ->
            blueprint.scryfallId?.trim()?.lowercase() in wanted
        }?.id
    }
}
