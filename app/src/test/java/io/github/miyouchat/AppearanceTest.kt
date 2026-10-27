package io.github.miyouchat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.miyouchat.data.*
import io.github.miyouchat.ui.yunjianColors
import org.junit.Assert.*
import org.junit.Test

class AppearanceTest {
    private fun contrast(a: Color, b: Color): Double {
        val x = a.luminance().toDouble(); val y = b.luminance().toDouble()
        return (maxOf(x,y) + 0.05) / (minOf(x,y) + 0.05)
    }
    @Test fun everyPaletteKeepsTextReadableInBothModes() {
        for (palette in ThemePalette.entries) for (dark in listOf(false,true)) {
            val c = yunjianColors(palette,dark)
            val pairs = listOf(c.primary to c.onPrimary, c.primaryContainer to c.onPrimaryContainer,
                c.secondaryContainer to c.onSecondaryContainer, c.background to c.onBackground,
                c.surface to c.onSurface, c.surfaceVariant to c.onSurfaceVariant, c.surfaceContainerHighest to c.onSurface)
            for ((background,text) in pairs) assertTrue("${palette.id} dark=$dark contrast=${contrast(background,text)}", contrast(background,text) >= 4.5)
        }
    }
    @Test fun choicesHaveStableIdsAndUnknownSavedValuesFallBack() {
        assertEquals(ThemePalette.entries.size,ThemePalette.entries.map { it.id }.toSet().size)
        assertEquals(ThemePalette.ICE_BLUE,ThemePalette.fromId("ice_blue"))
        assertEquals(ThemePalette.MIST_BLUE,ThemePalette.fromId("removed"))
        assertEquals(ThemeMode.SYSTEM,ThemeMode.fromId(null))
    }
    @Test fun palettePagesAndSentBubblesRemainVisiblyDifferent() {
        fun distance(a: Color, b: Color): Float = kotlin.math.sqrt(
            (a.red-b.red)*(a.red-b.red) + (a.green-b.green)*(a.green-b.green) + (a.blue-b.blue)*(a.blue-b.blue))
        for (dark in listOf(false,true)) for ((index,palette) in ThemePalette.entries.withIndex()) {
            for (other in ThemePalette.entries.drop(index+1)) {
                val a = yunjianColors(palette,dark); val b = yunjianColors(other,dark)
                assertTrue("Page colors too close: $palette / $other, dark=$dark",distance(a.background,b.background) >= 0.07f)
                assertTrue("Bubble colors too close: $palette / $other, dark=$dark",distance(a.primaryContainer,b.primaryContainer) >= 0.10f)
            }
        }
    }
    @Test fun darkModeOverrideAndSystemModeStayIndependentOfPalette() {
        for (palette in ThemePalette.entries) {
            assertTrue(Appearance(palette,ThemeMode.DARK).dark(false))
            assertFalse(Appearance(palette,ThemeMode.LIGHT).dark(true))
            assertTrue(Appearance(palette).dark(true)); assertFalse(Appearance(palette).dark(false))
        }
    }
}
