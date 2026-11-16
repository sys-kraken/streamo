package com.streamo.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamo.app.data.LibraryStore
import com.streamo.app.resources.Res
import com.streamo.app.resources.logo
import org.jetbrains.compose.resources.painterResource

// Pure black and white, like YouTube's dark and light themes but without the red.
private val Black = Color(0xFF000000)
private val White = Color(0xFFFFFFFF)

private val dark = darkColorScheme(
    primary = White,
    onPrimary = Black,
    primaryContainer = Color(0xFF272727),
    onPrimaryContainer = White,
    secondary = White,
    onSecondary = Black,
    tertiary = White,
    onTertiary = Black,
    background = Black,
    onBackground = White,
    surface = Black,
    onSurface = White,
    surfaceVariant = Color(0xFF272727),
    onSurfaceVariant = Color(0xFFAAAAAA),
    surfaceContainerLowest = Black,
    surfaceContainerLow = Color(0xFF0F0F0F),
    surfaceContainer = Color(0xFF181818),
    surfaceContainerHigh = Color(0xFF212121),
    surfaceContainerHighest = Color(0xFF2A2A2A),
    secondaryContainer = Color(0xFF3F3F3F),
    onSecondaryContainer = White,
    inverseSurface = White,
    inverseOnSurface = Black,
    outline = Color(0xFF3F3F3F),
    outlineVariant = Color(0xFF272727),
    error = White,
    onError = Black,
)

/** YouTube's softer dark grey, for people who find pure black harsh. Still no colour. */
private val dim = dark.copy(
    background = Color(0xFF0F0F0F),
    surface = Color(0xFF0F0F0F),
    surfaceVariant = Color(0xFF2A2A2A),
    surfaceContainerLowest = Color(0xFF0F0F0F),
    surfaceContainerLow = Color(0xFF161616),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF272727),
    surfaceContainerHighest = Color(0xFF303030),
    onSurfaceVariant = Color(0xFFB0B0B0),
    primaryContainer = Color(0xFF2A2A2A),
    outlineVariant = Color(0xFF2A2A2A),
)

private val light = lightColorScheme(
    primary = Black,
    onPrimary = White,
    primaryContainer = Color(0xFFF2F2F2),
    onPrimaryContainer = Black,
    secondary = Black,
    onSecondary = White,
    tertiary = Black,
    onTertiary = White,
    background = White,
    onBackground = Color(0xFF0F0F0F),
    surface = White,
    onSurface = Color(0xFF0F0F0F),
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFF606060),
    surfaceContainerLowest = White,
    surfaceContainerLow = Color(0xFFF9F9F9),
    surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFECECEC),
    surfaceContainerHighest = Color(0xFFE5E5E5),
    secondaryContainer = Color(0xFFE5E5E5),
    onSecondaryContainer = Black,
    inverseSurface = Color(0xFF0F0F0F),
    inverseOnSurface = White,
    outline = Color(0xFFD9D9D9),
    outlineVariant = Color(0xFFE5E5E5),
    error = Black,
    onError = White,
)

/** Tighter, YouTube-like text sizes. */
private val typography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        bodyLarge = t.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 14.sp, lineHeight = 19.sp),
        bodySmall = t.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = t.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
        labelSmall = t.labelSmall.copy(fontSize = 11.sp),
    )
}

/** Whether the dark theme is showing; Android uses it to colour the status bar icons. */
val LocalDarkTheme = staticCompositionLocalOf { true }

/** "system", "dark" or "light", from Settings. */
@Composable
fun isDarkTheme(): Boolean {
    val settings by LibraryStore.settings.collectAsState()
    val system = isSystemInDarkTheme()
    return when (settings.theme) {
        "dark" -> true
        "light" -> false
        else -> system
    }
}

@Composable
fun StreamoTheme(darkTheme: Boolean = isDarkTheme(), content: @Composable () -> Unit) {
    val settings by LibraryStore.settings.collectAsState()
    val colors = when {
        !darkTheme -> light
        settings.darkStyle == "dim" -> dim
        else -> dark
    }
    MaterialTheme(colorScheme = colors, typography = typography) {
        androidx.compose.runtime.CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
            // Surface gives all text the "on background" color by default.
            Surface(color = colors.background, contentColor = colors.onBackground, content = content)
        }
    }
}

/** The "Streamo" wordmark (black artwork, drawn white on the dark theme) with the tagline underneath. */
@Composable
fun BrandTitle(modifier: Modifier = Modifier) {
    Column(modifier) {
        Image(
            painterResource(Res.drawable.logo), contentDescription = "Streamo",
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
            modifier = Modifier.height(20.dp).aspectRatio(793f / 159f),
        )
        Text(
            "YouTube premium for pirates",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp, letterSpacing = 0.3.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
