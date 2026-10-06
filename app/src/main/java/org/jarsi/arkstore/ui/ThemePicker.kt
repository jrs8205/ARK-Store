package org.jarsi.arkstore.ui

import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jarsi.arkstore.R

/**
 * The row of palettes to choose from under the [heading], each shown as a disc of its primary
 * and tertiary colour in the light or dark version in use, the chosen one marked with a tick.
 * The wallpaper's palette is offered where Android provides it.
 */
@Composable
internal fun ThemePicker(heading: String, palette: Palette, onPaletteChange: (Palette) -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val haptics = LocalHapticFeedback.current
    val choices = remember {
        Palette.entries.filter { it != Palette.WALLPAPER || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
    }
    Text(
        text = heading,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(top = 16.dp)
            .semantics { heading() }
    )
    Text(
        text = stringResource(R.string.theme_title),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 8.dp)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (choice in choices) {
            val scheme = remember(choice, dark) { colorSchemeOf(context, choice, dark, black = false) }
            val selected = choice == palette
            // The whole column is the choice, so the name is part of what gets announced and tapped.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .selectable(selected = selected, role = Role.RadioButton) {
                        if (!selected) {
                            haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                            onPaletteChange(choice)
                        }
                    }
                    .widthIn(min = 52.dp)
                    .padding(horizontal = 2.dp, vertical = 8.dp)
            ) {
                Swatch(
                    left = scheme.primary,
                    right = scheme.tertiary,
                    tick = if (selected) scheme.onPrimary else null,
                    ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    ringWidth = if (selected) 3.dp else 1.dp
                )
                Text(
                    text = stringResource(choice.label),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

private val Palette.label: Int
    get() = when (this) {
        Palette.ARK -> R.string.palette_ark
        Palette.FOREST -> R.string.palette_forest
        Palette.GRAPHITE -> R.string.palette_graphite
        Palette.WINE -> R.string.palette_wine
        Palette.MIDNIGHT -> R.string.palette_midnight
        Palette.WALLPAPER -> R.string.palette_wallpaper
    }

/**
 * A disc whose [left] half and [right] half show two colours of a palette, inside a [ring];
 * with a [tick] colour, a smaller disc of the left colour carries a tick in it.
 */
@Composable
private fun Swatch(left: Color, right: Color, tick: Color?, ring: Color, ringWidth: Dp) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .drawBehind {
                val radius = size.minDimension / 2
                drawArc(left, startAngle = 90f, sweepAngle = 180f, useCenter = true)
                drawArc(right, startAngle = 270f, sweepAngle = 180f, useCenter = true)
                val stroke = ringWidth.toPx()
                drawCircle(ring, radius = radius - stroke / 2, style = Stroke(stroke))
                if (tick != null) {
                    drawCircle(left, radius = radius * 0.45f)
                    val w = size.width
                    val h = size.height
                    drawPath(
                        path = Path().apply {
                            moveTo(w * 0.35f, h * 0.51f)
                            lineTo(w * 0.45f, h * 0.61f)
                            lineTo(w * 0.65f, h * 0.40f)
                        },
                        color = tick,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }
    )
}
