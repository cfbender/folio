package dev.cfbender.folio.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import dev.cfbender.folio.R

private val Light = lightColorScheme(
    primary = Color(0xFF8A4B2D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCC),
    onPrimaryContainer = Color(0xFF351000),
    secondary = Color(0xFF6F5B4E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF1DFD0),
    onSecondaryContainer = Color(0xFF28190F),
    tertiary = Color(0xFF5E6135),
    background = Color(0xFFFBF6EC),
    onBackground = Color(0xFF221E19),
    surface = Color(0xFFFBF6EC),
    onSurface = Color(0xFF221E19),
    surfaceVariant = Color(0xFFEEE3D3),
    onSurfaceVariant = Color(0xFF52463B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F0E3),
    surfaceContainer = Color(0xFFF2EADB),
    surfaceContainerHigh = Color(0xFFECE3D3),
    surfaceContainerHighest = Color(0xFFE6DDCC),
    outline = Color(0xFF85776A),
    outlineVariant = Color(0xFFD6C8B6),
    inverseSurface = Color(0xFF37312B),
    inverseOnSurface = Color(0xFFFBEEDF),
    inversePrimary = Color(0xFFFFB693),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB693),
    onPrimary = Color(0xFF532007),
    primaryContainer = Color(0xFF6E3619),
    onPrimaryContainer = Color(0xFFFFDBCC),
    secondary = Color(0xFFDBC2B1),
    onSecondary = Color(0xFF3D2D22),
    secondaryContainer = Color(0xFF554337),
    onSecondaryContainer = Color(0xFFF8DECC),
    tertiary = Color(0xFFC7CA95),
    background = Color(0xFF171513),
    onBackground = Color(0xFFEAE1D6),
    surface = Color(0xFF171513),
    onSurface = Color(0xFFEAE1D6),
    surfaceVariant = Color(0xFF52463B),
    onSurfaceVariant = Color(0xFFD6C8B6),
    surfaceContainerLowest = Color(0xFF110F0D),
    surfaceContainerLow = Color(0xFF1F1C19),
    surfaceContainer = Color(0xFF23201D),
    surfaceContainerHigh = Color(0xFF2E2A27),
    surfaceContainerHighest = Color(0xFF393531),
    outline = Color(0xFF9F9183),
    outlineVariant = Color(0xFF52463B),
    inverseSurface = Color(0xFFEAE1D6),
    inverseOnSurface = Color(0xFF37312B),
    inversePrimary = Color(0xFF8A4B2D),
)

val Literata = FontFamily(
    Font(R.font.literata_regular, FontWeight.Normal),
    Font(R.font.literata_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.literata_bold, FontWeight.Bold),
    Font(R.font.literata_bold_italic, FontWeight.Bold, FontStyle.Italic),
)

@Composable
fun FolioTheme(content: @Composable () -> Unit) {
    val base = Typography()
    // Titles are set in the book face; controls and labels stay in the system sans.
    val typography = base.copy(
        displaySmall = base.displaySmall.copy(fontFamily = Literata),
        headlineLarge = base.headlineLarge.copy(fontFamily = Literata),
        headlineMedium = base.headlineMedium.copy(fontFamily = Literata),
        headlineSmall = base.headlineSmall.copy(fontFamily = Literata),
        titleLarge = base.titleLarge.copy(fontFamily = Literata),
    )
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = typography,
        content = content,
    )
}
