package com.streamo.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamo.app.resources.Res
import com.streamo.app.resources.logo
import org.jetbrains.compose.resources.painterResource

// Colors from the Streamo logo.
val Flame = Color(0xFFE2401A)      // red of "STRE"
val Ember = Color(0xFFF2711F)      // orange of "EA", the mermaid's hair
val Kelp = Color(0xFF3FBF1F)       // green of "MO"
val Coral = Color(0xFFF38177)      // the tentacles and starfish
val Parchment = Color(0xFFF4E6CF)  // the cream ring and paper
val Ink = Color(0xFF120D0B)        // warm black background

/** Soft parchment used for the "Streamo" name and its tagline. */
val BrandText = Parchment.copy(alpha = 0.7f)

private val colors = darkColorScheme(
    primary = Ember,
    onPrimary = Ink,
    primaryContainer = Color(0xFF5A2A12),
    onPrimaryContainer = Parchment,
    secondary = Kelp,
    onSecondary = Ink,
    tertiary = Coral,
    onTertiary = Ink,
    background = Ink,
    onBackground = Parchment,
    surface = Ink,
    onSurface = Parchment,
    surfaceVariant = Color(0xFF2A201B),
    onSurfaceVariant = Color(0xFFB9A994),
    surfaceContainerLowest = Color(0xFF0D0907),
    surfaceContainerLow = Color(0xFF181210),
    surfaceContainer = Color(0xFF1C1512),
    surfaceContainerHigh = Color(0xFF241B17),
    surfaceContainerHighest = Color(0xFF2E231D),
    secondaryContainer = Color(0xFF3A2A20),
    onSecondaryContainer = Parchment,
    outline = Color(0xFF4A3A30),
    outlineVariant = Color(0xFF33281F),
)

@Composable
fun StreamoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors) {
        // Surface gives all text the light "on background" color by default.
        Surface(color = colors.background, contentColor = colors.onBackground, content = content)
    }
}

/** Logo + "Streamo" in the logo's lettering, with the tagline underneath. */
@Composable
fun BrandTitle(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(
            painterResource(Res.drawable.logo), contentDescription = null,
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)),
        )
        Column {
            Text(
                "Streamo",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, lineHeight = 24.sp),
                color = BrandText,
            )
            Text(
                "YouTube premium for pirates",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
                color = BrandText,
            )
        }
    }
}
