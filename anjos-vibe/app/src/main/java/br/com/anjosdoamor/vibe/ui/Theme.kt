package br.com.anjosdoamor.vibe.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Paleta da Anjos do Amor sobre fundo escuro.
 * Fundo escuro nao e estilo: o app e usado no escuro, e tela clara cega.
 */
object Brand {
    val Roxo = Color(0xFF7D387D)
    val Rosa = Color(0xFFF686BD)
    val Magenta = Color(0xFFE12B8D)

    val Fundo = Color(0xFF170A14)
    val Superficie = Color(0xFF241028)

    val Texto = Color(0xFFF5E9F1)
    val TextoFraco = Color(0xFFB292A8)
    val Perigo = Color(0xFFFF6B6B)
}

private val Scheme = darkColorScheme(
    primary = Brand.Magenta,
    onPrimary = Color.White,
    secondary = Brand.Rosa,
    onSecondary = Color(0xFF2A0E22),
    tertiary = Brand.Roxo,
    background = Brand.Fundo,
    onBackground = Brand.Texto,
    surface = Brand.Superficie,
    onSurface = Brand.Texto,
    surfaceVariant = Color(0xFF321634),
    onSurfaceVariant = Brand.TextoFraco,
    error = Brand.Perigo
)

private val AppTypography = Typography(
    displayLarge = TextStyle(
        fontSize = 56.sp,
        fontWeight = FontWeight.Light,
        letterSpacing = (-1).sp
    ),
    titleLarge = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp
    ),
    bodyMedium = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal
    ),
    labelSmall = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp
    )
)

@Composable
fun AnjosVibeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = AppTypography,
        content = content
    )
}
