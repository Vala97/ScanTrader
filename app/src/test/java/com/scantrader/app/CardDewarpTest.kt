package com.scantrader.app

import com.scantrader.app.scan.CardDewarp
import com.scantrader.app.scan.CardFrameGeometry
import com.scantrader.app.vision.MiloEmbedder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CardDewarpTest {
    @Test
    fun guideCornersStayCardShapedAndAreAbsentWithoutAPreview() {
        assertNull(CardFrameGeometry.guideCorners(960, 1280, 0f, 0f))
        val corners = CardFrameGeometry.guideCorners(960, 1280, 1080f, 2400f)
        assertTrue(corners != null)
        val width = corners!![2] - corners[0]
        val height = corners[5] - corners[1]
        assertEquals(CardFrameGeometry.ASPECT, width / height, 0.03f)
        val square = CardFrameGeometry.frameIn(960f, 1280f)
        assertTrue(abs(square.width / square.height - CardFrameGeometry.ASPECT) > 0.2f)
    }

    @Test
    fun dewarpDropsTheBackgroundAroundTheCard() {
        val width = 40
        val height = 60
        val pixels = IntArray(width * height) { BLUE }
        val left = 8
        val top = 6
        val right = 32
        val bottom = 54
        for (y in top until bottom) {
            for (x in left until right) {
                pixels[y * width + x] = RED
            }
        }
        val corners = floatArrayOf(
            left.toFloat(), top.toFloat(),
            right.toFloat(), top.toFloat(),
            right.toFloat(), bottom.toFloat(),
            left.toFloat(), bottom.toFloat(),
        )
        val card = CardDewarp.warp(CardDewarp.Image(width, height, pixels), corners)
        assertTrue(card.width > 8)
        assertTrue(card.height > card.width)
        val interior = card.pixels.filterIndexed { index, _ ->
            val x = index % card.width
            val y = index / card.width
            x in 2 until card.width - 2 && y in 2 until card.height - 2
        }
        val reds = interior.count { (it and 0x00ffffff) == 0x00ff0000 }
        assertTrue("reds=$reds of ${interior.size}", reds > interior.size * 9 / 10)
        assertTrue(interior.none { (it and 0x00ffffff) == 0x000000ff })
    }

    @Test
    fun fullSizeStillIsReadOnlyInsideTheGuideAndScaledDown() {
        // Manual still decoded at full size (3072×4080): the old path copied all
        // 12.5 MP into an IntArray (50 MB) plus a 2048-side output (12 MB).
        val corners = CardFrameGeometry.guideCorners(3072, 4080, 1080f, 2400f)!!
        val region = CardDewarp.sourceRegion(corners, 3072, 4080)
        assertTrue(region.scale < 1f)
        assertTrue(maxOf(region.scaledWidth, region.scaledHeight) <= CardDewarp.MAX_SIDE)
        assertTrue(region.scaledWidth.toLong() * region.scaledHeight * 4 < 4L * 1024 * 1024)
        val local = region.mapCorners(corners)
        val (w, h) = CardDewarp.outputSize(local)
        assertTrue("out ${w}x$h", maxOf(w, h) <= CardDewarp.MAX_SIDE)
        assertEquals(CardFrameGeometry.ASPECT, w.toFloat() / h, 0.03f)
        for (i in 0 until 4) {
            assertTrue(local[i * 2] >= -0.5f && local[i * 2] <= region.scaledWidth + 0.5f)
            assertTrue(local[i * 2 + 1] >= -0.5f && local[i * 2 + 1] <= region.scaledHeight + 0.5f)
        }
    }

    @Test
    fun analysisFrameKeepsScaleOneAndTheSameWarp() {
        val width = 574
        val height = 1280
        val corners = CardFrameGeometry.guideCorners(width, height, 1080f, 2400f)!!
        val region = CardDewarp.sourceRegion(corners, width, height)
        assertEquals(1f, region.scale, 0f)
        val pixels = IntArray(width * height) { i -> 0xff000000.toInt() or ((i * 7919) and 0x00ffffff) }
        val full = CardDewarp.warp(CardDewarp.Image(width, height, pixels), corners)
        val sub = IntArray(region.width * region.height) { i ->
            val x = region.left + i % region.width
            val y = region.top + i / region.width
            pixels[y * width + x]
        }
        val local = CardDewarp.warp(
            CardDewarp.Image(region.width, region.height, sub),
            region.mapCorners(corners, region.width, region.height),
        )
        assertEquals(full.width, local.width)
        assertEquals(full.height, local.height)
        assertTrue(full.pixels.contentEquals(local.pixels))
    }

    @Test
    fun forceCpuSkipsNnapiAndXnnpack() {
        assertEquals(listOf("cpu"), MiloEmbedder.providerOrder(forceCpuOnly = true, nnapi = true))
        assertEquals(
            listOf("nnapi+xnnpack", "xnnpack", "cpu"),
            MiloEmbedder.providerOrder(forceCpuOnly = false, nnapi = true),
        )
        assertEquals(
            listOf("xnnpack", "cpu"),
            MiloEmbedder.providerOrder(forceCpuOnly = false, nnapi = false),
        )
        assertTrue(!MiloEmbedder.forceCpuOnly)
    }
}

private const val RED = 0xffff0000.toInt()
private const val BLUE = 0xff0000ff.toInt()
