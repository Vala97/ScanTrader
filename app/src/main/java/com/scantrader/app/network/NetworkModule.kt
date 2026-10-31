package com.scantrader.app.network

import com.scantrader.app.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkModule {
    fun client(): OkHttpClient {
        val userAgent = Interceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "ScanTrader/1.0 (Android; +https://github.com/Vala97/ScanTrader)")
                    .header("Accept", "application/json")
                    .build(),
            )
        }
        return OkHttpClient.Builder()
            .addInterceptor(userAgent)
            .addInterceptor(StatusRetryInterceptor())
            .addInterceptor(logging())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun retrofit(baseUrl: String, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    /** Bearer token is read on each call and is not attached to the Scryfall client. */
    fun cardTraderClient(token: () -> String?): OkHttpClient {
        val auth = Interceptor { chain ->
            val bearer = token()?.trim().orEmpty()
            val builder = chain.request().newBuilder()
                .header("Accept", "application/json")
                .header("User-Agent", "ScanTrader/1.0 (Android; +https://github.com/Vala97/ScanTrader)")
            if (bearer.isNotEmpty()) builder.header("Authorization", "Bearer $bearer")
            chain.proceed(builder.build())
        }
        return OkHttpClient.Builder()
            .addInterceptor(auth)
            .addInterceptor(StatusRetryInterceptor())
            .addInterceptor(logging())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** BASIC in debug so URLs are visible. Release stays silent, and the bearer token is never written. */
    private fun logging(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            redactHeader("Authorization")
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
}
