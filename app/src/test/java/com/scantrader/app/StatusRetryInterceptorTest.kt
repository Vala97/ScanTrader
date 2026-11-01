package com.scantrader.app

import com.scantrader.app.network.StatusRetry
import com.scantrader.app.network.StatusRetryInterceptor
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusRetryInterceptorTest {
    @Test
    fun retries429UsingRetryAfterThenReturnsSuccess() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        server.start()
        val waits = mutableListOf<Long>()
        try {
            val client = client(waits)
            val response = client.newCall(Request.Builder().url(server.url("/card")).build()).execute()
            response.use {
                assertEquals(200, it.code)
                assertEquals("ok", it.body?.string())
            }
            assertEquals(2, server.requestCount)
            assertEquals(listOf(1_000L), waits)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun retries503WithExponentialBackoff() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200).setBody("back"))
        server.start()
        val waits = mutableListOf<Long>()
        try {
            val client = client(waits)
            val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
            response.use { assertEquals(200, it.code) }
            assertEquals(listOf(500L), waits)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun stopsAfterTheBoundedAttemptCount() {
        val server = MockWebServer()
        repeat(5) { server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0")) }
        server.start()
        val waits = mutableListOf<Long>()
        try {
            val client = client(waits)
            val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
            response.use { assertEquals(429, it.code) }
            assertEquals(StatusRetryInterceptor.DEFAULT_ATTEMPTS, server.requestCount)
            assertEquals(StatusRetryInterceptor.DEFAULT_ATTEMPTS - 1, waits.size)
            assertTrue(waits.all { it == 200L })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun doesNotRetryOtherStatusCodes() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()
        val waits = mutableListOf<Long>()
        try {
            val client = client(waits)
            val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
            response.use { assertEquals(500, it.code) }
            assertEquals(1, server.requestCount)
            assertTrue(waits.isEmpty())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun httpDateRetryAfterIsADelayFromNowAndIsCapped() {
        val now = 1_700_000_000_000L
        assertEquals(
            2_000L,
            StatusRetry.delayMs("Thu, 01 Jan 1970 00:00:02 GMT", attempt = 0, nowMs = 0L),
        )
        assertEquals(500L, StatusRetry.delayMs(null, attempt = 0, nowMs = now))
        assertEquals(1_000L, StatusRetry.delayMs(null, attempt = 1, nowMs = now))
        assertEquals(30_000L, StatusRetry.delayMs("120", attempt = 0, nowMs = now))
    }

    private fun client(waits: MutableList<Long>): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(StatusRetryInterceptor(sleep = { waits += it }))
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
}
