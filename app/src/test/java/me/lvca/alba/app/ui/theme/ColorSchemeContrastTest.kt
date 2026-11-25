package me.lvca.alba.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import me.lvca.alba.app.ui.designsystem.theme.AlbaDarkColorScheme
import me.lvca.alba.app.ui.designsystem.theme.AlbaLightColorScheme
import me.lvca.alba.app.ui.designsystem.theme.albaColors
import org.junit.Assert.assertTrue
import org.junit.Test

/** The static iCloud schemes: contrast, a complete set of roles and a surface ramp in the right order. */
class ColorSchemeContrastTest {
    private val schemes = mapOf("light" to AlbaLightColorScheme, "dark" to AlbaDarkColorScheme)

    private fun ratio(a: Color, b: Color): Double {
        val (light, dark) = listOf(a.luminance() + .05, b.luminance() + .05).sortedDescending()
        return light / dark
    }

    private fun assertContrast(name: String, foreground: Color, background: Color, minimum: Double) {
        val actual = ratio(foreground, background)
        assertTrue("$name is %.2f:1, below %.1f:1".format(actual, minimum), actual >= minimum)
    }

    /** A saturated colour in Material's baseline violet range (its primary #6750A4 has a hue of about 262). */
    private fun Color.isPurple(): Boolean {
        val max = maxOf(red, green, blue)
        val min = minOf(red, green, blue)
        val delta = max - min
        if (max == 0f || delta / max <= .15f) return false
        val hue = when (max) {
            red -> 60f * (((green - blue) / delta).mod(6f))
            green -> 60f * ((blue - red) / delta + 2f)
            else -> 60f * ((red - green) / delta + 4f)
        }
        return hue in 255f..335f
    }

    private val ColorScheme.surfaces get() = mapOf(
        "surface" to surface, "background" to background, "surfaceDim" to surfaceDim, "surfaceBright" to surfaceBright,
        "surfaceContainerLowest" to surfaceContainerLowest, "surfaceContainerLow" to surfaceContainerLow,
        "surfaceContainer" to surfaceContainer, "surfaceContainerHigh" to surfaceContainerHigh,
        "surfaceContainerHighest" to surfaceContainerHighest,
    )

    @Test fun primaryReadsAsTextOnEverySurface() = schemes.forEach { (mode, scheme) ->
        // 4.5:1 is text contrast, which also covers the 3:1 icons need.
        scheme.surfaces.forEach { (name, surface) -> assertContrast("$mode primary on $name", scheme.primary, surface, 4.5) }
    }

    @Test fun contentRolesReadOnTheirContainers() = schemes.forEach { (mode, scheme) ->
        with(scheme) {
            listOf(
                "onPrimary" to (onPrimary to primary), "onPrimaryContainer" to (onPrimaryContainer to primaryContainer),
                "onSecondaryContainer" to (onSecondaryContainer to secondaryContainer),
                "onTertiaryContainer" to (onTertiaryContainer to tertiaryContainer),
                "onErrorContainer" to (onErrorContainer to errorContainer), "error" to (error to surface),
                "onSurfaceVariant" to (onSurfaceVariant to surfaceContainerHighest),
                // The snackbar's action.
                "inversePrimary" to (inversePrimary to inverseSurface), "inverseOnSurface" to (inverseOnSurface to inverseSurface),
            ).forEach { (name, pair) -> assertContrast("$mode $name", pair.first, pair.second, 4.5) }
            assertContrast("$mode outline", outline, surface, 3.0)
        }
    }

    @Test fun containersRiseInOrder() {
        fun ramp(scheme: ColorScheme) = with(scheme) {
            listOf(surfaceContainerLowest, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest)
                .map { it.luminance() }
        }
        val light = ramp(AlbaLightColorScheme)
        val dark = ramp(AlbaDarkColorScheme)
        assertTrue("light containers darken as they rise: $light", light.zipWithNext().all { (a, b) -> a > b })
        assertTrue("dark containers lighten as they rise: $dark", dark.zipWithNext().all { (a, b) -> a < b })
        assertTrue("dark surface is near-black", AlbaDarkColorScheme.surface.luminance() < .01f)
    }

    @Test fun noRoleKeepsMaterialsBaselinePurple() = schemes.forEach { (mode, scheme) ->
        with(scheme) {
            listOf(primary, onPrimary, primaryContainer, onPrimaryContainer, inversePrimary, secondary, onSecondary,
                secondaryContainer, onSecondaryContainer, tertiary, onTertiary, tertiaryContainer, onTertiaryContainer,
                background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant, surfaceTint, inverseSurface,
                inverseOnSurface, outline, outlineVariant, scrim, surfaceBright, surfaceDim, surfaceContainerLowest,
                surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest, primaryFixed,
                primaryFixedDim, onPrimaryFixed, onPrimaryFixedVariant, secondaryFixed, secondaryFixedDim, onSecondaryFixed,
                onSecondaryFixedVariant, tertiaryFixed, tertiaryFixedDim, onTertiaryFixed, onTertiaryFixedVariant,
            ).forEach { color ->
                assertTrue("$mode has a purple role: ${Integer.toHexString(color.toArgb())}", !color.isPurple())
            }
        }
    }

    @Test fun statusColorsReadOnEverySurface() = schemes.forEach { (mode, scheme) ->
        val colors = albaColors(scheme, isDark = mode == "dark")
        (scheme.surfaces + ("viewer canvas" to colors.viewer.canvas) + ("viewer card" to colors.viewer.card)).forEach { (name, surface) ->
            assertContrast("$mode favorite on $name", colors.favorite, surface, 3.0)
            assertContrast("$mode success on $name", colors.success, surface, 3.0)
        }
    }
}
