package io.openflux.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.openflux.app.theme.AppTheme
import io.openflux.app.theme.accentBrush
import io.openflux.app.theme.glow
import io.openflux.desktop.resources.Res
import io.openflux.desktop.resources.ic_openflux_foreground
import org.jetbrains.compose.resources.painterResource

/** The logo on a tile of the accent's gradient, glowing a little. */
@Composable
fun BrandTile(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val c = AppTheme.colors
    val shape = RoundedCornerShape(size * 0.3f)
    Box(
        modifier.size(size).shadow(size / 4, shape, ambientColor = c.glow, spotColor = c.glow).clip(shape).background(c.accentBrush),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(Res.drawable.ic_openflux_foreground), "OpenFlux", tint = Color.White, modifier = Modifier.size(size * 0.9f))
    }
}

/**
 * The program's name with its logo: the side column's, or Home's slightly [large] one.
 */
@Composable
fun Brand(modifier: Modifier = Modifier, large: Boolean = false, subtitle: String = "Зашифрованный туннель") {
    val c = AppTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
        BrandTile(size = if (large) 44.dp else 40.dp)
        Column {
            Text(
                "OpenFlux",
                style = if (large) {
                    TextStyle(fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp)
                } else {
                    AppTheme.type.title.copy(fontWeight = FontWeight.Bold)
                },
                color = c.text,
                maxLines = 1,
            )
            Text(subtitle, style = AppTheme.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
