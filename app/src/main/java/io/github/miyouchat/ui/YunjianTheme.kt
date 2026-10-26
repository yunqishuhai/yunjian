package io.github.miyouchat.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
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
    inverseSurface = Color(0xFF23364A), inverseOnSurface = Color(0xFFF4F7FC), inversePrimary = Color(0xFFA9CEEF))
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
    inverseSurface = Color(0xFFE1EBF5), inverseOnSurface = Color(0xFF1B2B3D), inversePrimary = Color(0xFF416E95))

private fun text(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif,fontWeight = weight,fontSize = size.sp,lineHeight = height.sp)
private val Type = Typography(
    headlineLarge = text(30,38,FontWeight.SemiBold),headlineMedium = text(26,34,FontWeight.SemiBold),
    headlineSmall = text(22,30,FontWeight.SemiBold),titleLarge = text(21,28,FontWeight.SemiBold),
    titleMedium = text(16,24,FontWeight.Medium),titleSmall = text(14,20,FontWeight.Medium),
    bodyLarge = text(16,24),bodyMedium = text(14,21),bodySmall = text(12,18),
    labelLarge = text(14,20,FontWeight.Medium),labelMedium = text(12,16,FontWeight.Medium),labelSmall = text(11,16,FontWeight.Medium))

object YunjianMotion { const val FEEDBACK = 120; const val SHORT = 160; const val PAGE = 220 }

@Composable fun YunjianTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val view = LocalView.current
    SideEffect {
        var context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        (context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window,view).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = if (dark) Night else Day,typography = Type,
        shapes = Shapes(RoundedCornerShape(6.dp),RoundedCornerShape(10.dp),RoundedCornerShape(16.dp),RoundedCornerShape(22.dp),RoundedCornerShape(28.dp)),content = content)
}
