package com.lagradost.cloudstream3.ui.poster

import com.lagradost.cloudstream3.ui.poster.PosterFit.Decision
import com.lagradost.cloudstream3.ui.poster.PosterFit.Fill
import com.lagradost.cloudstream3.ui.poster.PosterFit.Scale
import com.lagradost.cloudstream3.ui.poster.PosterFit.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterFitTest {
    private val wide = 1280 to 720
    private val square = 600 to 600
    private val portrait = 300 to 450
    private val unknown = -1 to -1 // placeholders have no intrinsic size

    private fun decide(size: Pair<Int, Int>, mode: Mode, readable: Boolean = true) =
        PosterFit.decide(size.first, size.second, mode, readable)

    @Test fun fitWholeFitsWideImagesOnly() {
        assertEquals(Decision(Scale.Fit, Fill.Blur), decide(wide, Mode.FitWhole))
        assertEquals(Decision(Scale.Fit, Fill.Blur), decide(square, Mode.FitWhole))
        assertEquals(Decision(Scale.Crop, Fill.None), decide(portrait, Mode.FitWhole))
        assertEquals(Decision(Scale.Crop, Fill.None), decide(unknown, Mode.FitWhole))
    }

    @Test fun fillPosterAlwaysCrops() {
        for (size in listOf(wide, square, portrait, unknown)) {
            assertEquals(Decision(Scale.Crop, Fill.None), decide(size, Mode.FillPoster))
        }
    }

    /**
     * A memory-cache hit, a hardware bitmap or a non-bitmap drawable may not expose its pixels.
     * That may only change the decoration, never fit vs crop.
     */
    @Test fun unreadablePixelsNeverChangeTheScale() {
        for (mode in Mode.entries) for (size in listOf(wide, square, portrait, unknown)) {
            val readable = decide(size, mode, readable = true)
            val hardware = decide(size, mode, readable = false)
            assertEquals("$mode $size", readable.scale, hardware.scale)
        }
        assertEquals(Fill.Solid, decide(wide, Mode.FitWhole, readable = false).fill)
    }

    @Test fun sameImageSameDecisionEveryBind() {
        // The decision is a pure function of size and setting: repeated binds (first load,
        // scroll back, recycled view) can't disagree.
        val first = decide(wide, Mode.FitWhole)
        repeat(100) { assertEquals(first, decide(wide, Mode.FitWhole)) }
    }

    /**
     * The old XML settings screen stores the value strings, AppSettings stores the shared enum's
     * names and PosterFit mirrors them: all three must agree. (The shared module targets a newer
     * JVM than these tests, so its enum is checked by source.)
     */
    @Test fun settingValuesAgreeEverywhere() {
        val names = Mode.entries.map { it.name }
        val arrays = java.io.File("src/main/res/values/array.xml").readText()
        val xmlValues = Regex("""<string-array name="poster_fit_mode_values"[^>]*>(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
            .find(arrays)!!.groupValues[1]
            .let { Regex("<item>(\\w+)</item>").findAll(it).map { m -> m.groupValues[1] }.toList() }
        assertEquals(names, xmlValues)

        val shared = java.io.File("../shared/src/commonMain/kotlin/com/lagradost/cloudstream4/DevicePreferenceStore.kt").readText()
        val enumBody = Regex("enum class PosterFitMode \\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL).find(shared)!!.groupValues[1]
        val sharedNames = Regex("^\\s*(\\w+),", RegexOption.MULTILINE).findAll(enumBody).map { it.groupValues[1] }.toList()
        assertEquals(names, sharedNames)
        assertTrue("default must be FitWhole", shared.contains("POSTER_FIT_MODE_KEY, PosterFitMode.FitWhole"))
    }
}
