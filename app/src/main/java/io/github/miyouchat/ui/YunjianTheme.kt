package io.github.miyouchat.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.github.miyouchat.data.Appearance
import io.github.miyouchat.data.ThemePalette
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val Day = lightColorScheme(
    primary = Color(0xFF416E95), onPrimary = Color.White,
    primaryContainer = Color(0xFFDFEBF7), onPrimaryContainer = Color(0xFF234461),
    secondary = Color(0xFF5C7188), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEBF1F7), onSecondaryContainer = Color(0xFF344B63),
    background = Color(0xFFF5F8FC), onBackground = Color(0xFF203044),
    surface = Color.White, onSurface = Color(0xFF203044),
    surfaceVariant = Color(0xFFEDF2F8), onSurfaceVariant = Color(0xFF5C6B7F),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F9FC),
    surfaceContainer = Color(0xFFF0F4F9), surfaceContainerHigh = Color(0xFFE8EFF6),
    surfaceContainerHighest = Color(0xFFE1EAF3),
    outline = Color(0xFF788A9E), outlineVariant = Color(0xFFDCE5EF),
    inverseSurface = Color(0xFF23364A), inverseOnSurface = Color(0xFFF4F7FC), inversePrimary = Color(0xFFA9CEEF), surfaceTint = Color(0xFF416E95))
private val Night = darkColorScheme(
    primary = Color(0xFFA9CEEF), onPrimary = Color(0xFF173752),
    primaryContainer = Color(0xFF294963), onPrimaryContainer = Color(0xFFDFEDFA),
    secondary = Color(0xFFB2C6DB), onSecondary = Color(0xFF1D3349),
    secondaryContainer = Color(0xFF283C51), onSecondaryContainer = Color(0xFFDBE8F5),
    background = Color(0xFF111B27), onBackground = Color(0xFFE5EDF6),
    surface = Color(0xFF182432), onSurface = Color(0xFFE5EDF6),
    surfaceVariant = Color(0xFF263749), onSurfaceVariant = Color(0xFFB3C3D4),
    surfaceContainerLowest = Color(0xFF101925), surfaceContainerLow = Color(0xFF152130),
    surfaceContainer = Color(0xFF1D2B3C), surfaceContainerHigh = Color(0xFF25364A),
    surfaceContainerHighest = Color(0xFF2D4056),
    outline = Color(0xFF879DB4), outlineVariant = Color(0xFF354A61),
    inverseSurface = Color(0xFFE1EBF5), inverseOnSurface = Color(0xFF1B2B3D), inversePrimary = Color(0xFF416E95), surfaceTint = Color(0xFFA9CEEF))

private fun text(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif,fontWeight = weight,fontSize = size.sp,lineHeight = height.sp)
private val Type = Typography(
    headlineLarge = text(30,38,FontWeight.SemiBold),headlineMedium = text(26,34,FontWeight.SemiBold),
    headlineSmall = text(22,30,FontWeight.SemiBold),titleLarge = text(21,28,FontWeight.SemiBold),
    titleMedium = text(16,24,FontWeight.Medium),titleSmall = text(14,20,FontWeight.Medium),
    bodyLarge = text(16,24),bodyMedium = text(14,21),bodySmall = text(12,18),
    labelLarge = text(14,20,FontWeight.Medium),labelMedium = text(12,16,FontWeight.Medium),labelSmall = text(11,16,FontWeight.Medium))

object YunjianMotion { const val FEEDBACK = 120; const val SHORT = 160; const val PAGE = 220 }

internal fun yunjianColors(palette: ThemePalette, dark: Boolean): ColorScheme {
    if (palette == ThemePalette.MIST_BLUE) return if (dark) Night else Day
    // Distinct page, chrome and bubble colors; a near-white tint alone is hard to tell apart.
    val tones = when (palette) {
        ThemePalette.ICE_BLUE -> listOf(0xFF125FBA, 0xFF8CC9FF, 0xFF08305B, 0xFFDCEEFF, 0xFFEEF7FF, 0xFFC0DFFF, 0xFF0B2544, 0xFF102B49, 0xFF1D4D7C)
        ThemePalette.BAMBOO -> listOf(0xFF236A43, 0xFF99DFAC, 0xFF123C23, 0xFFE0F0E2, 0xFFF0F8EF, 0xFFB9DDBE, 0xFF102D1C, 0xFF173A29, 0xFF20553B)
        ThemePalette.LILAC -> listOf(0xFF7146A0, 0xFFD7AFFB, 0xFF3E1E56, 0xFFEEE0FA, 0xFFF8F0FF, 0xFFD6B9F1, 0xFF2B173B, 0xFF382349, 0xFF58306F)
        ThemePalette.BLOSSOM -> listOf(0xFFA33364, 0xFFFFA9CE, 0xFF5C1836, 0xFFFBE0EB, 0xFFFFF0F6, 0xFFF2B9D1, 0xFF3B1428, 0xFF4C2234, 0xFF71314F)
        else -> error("Default palette handled above")
    }
    val primary = Color(if (dark) tones[1] else tones[0])
    val ink = if (dark) Color(0xFFE6EDF1) else Color(0xFF24333B)
    val onPrimary = if (dark) Color(tones[2]) else Color.White
    val background = Color(if (dark) tones[6] else tones[3])
    val surface = Color(if (dark) tones[7] else tones[4])
    val container = Color(if (dark) tones[8] else tones[5])
    fun layer(amount: Float) = lerp(surface, container, amount)
    return (if (dark) Night else Day).copy(
        primary = primary, onPrimary = onPrimary, primaryContainer = container, onPrimaryContainer = ink,
        secondary = primary, onSecondary = onPrimary, secondaryContainer = layer(0.45f), onSecondaryContainer = ink,
        tertiary = primary, onTertiary = onPrimary, tertiaryContainer = container, onTertiaryContainer = ink,
        background = background, onBackground = ink,
        surface = surface, onSurface = ink,
        surfaceVariant = layer(0.40f), onSurfaceVariant = if (dark) Color(0xFFCBD8E0) else Color(0xFF43535D),
        surfaceContainerLowest = if (dark) background else Color.White,
        surfaceContainerLow = surface, surfaceContainer = layer(0.20f),
        surfaceContainerHigh = layer(0.45f), surfaceContainerHighest = layer(0.65f),
        outline = if (dark) Color(0xFFA2B4C0) else Color(0xFF647681), outlineVariant = layer(0.85f),
        inverseSurface = if (dark) Color(0xFFE6EDF1) else Color(0xFF24333B),
        inverseOnSurface = if (dark) Color(0xFF24333B) else Color(0xFFE6EDF1), inversePrimary = Color(if (dark) tones[0] else tones[1]),
        surfaceTint = primary)
}

@Composable fun YunjianTheme(appearance: Appearance = Appearance(), content: @Composable () -> Unit) {
    val dark = appearance.dark(isSystemInDarkTheme())
    ThemeSystemBars(dark)
    MaterialTheme(colorScheme = yunjianColors(appearance.palette, dark),typography = Type,
        shapes = Shapes(RoundedCornerShape(6.dp),RoundedCornerShape(10.dp),RoundedCornerShape(16.dp),RoundedCornerShape(22.dp),RoundedCornerShape(28.dp)),content = content)
}

@Composable internal fun ThemeSystemBars(dark: Boolean) {
    val view = LocalView.current
    SideEffect {
        var context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        val window = (view.parent as? DialogWindowProvider)?.window ?: (context as? Activity)?.window
        window?.let {
            WindowCompat.getInsetsController(it,view).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
        }
    }
}
