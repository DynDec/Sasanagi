package com.sasayaki.player.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.sasayaki.player.ui.theme.withSecondary

private val PRESETS = listOf(
    "Red" to 0xFFE53935, "Pink" to 0xFFD81B60, "Rose" to 0xFFE57399, "Purple" to 0xFF8E24AA,
    "Lavender" to 0xFF9C89C9, "Indigo" to 0xFF3949AB, "Blue" to 0xFF1E88E5, "Cyan" to 0xFF00ACC1,
    "Teal" to 0xFF00897B, "Green" to 0xFF43A047, "Lime" to 0xFFC0CA33, "Amber" to 0xFFFFB300,
    "Orange" to 0xFFFB8C00, "Brown" to 0xFF795548, "Slate" to 0xFF607D8B,
).map { (name, c) -> name to c.toInt() }

private val HUES = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)

private enum class PickerTab(val label: String) { PRESETS("Presets"), SPECTRUM("Spectrum"), HEX("Hex") }

fun hexOf(argb: Int): String = "%06X".format(argb and 0xFFFFFF)

private fun hsvOf(argb: Int) = FloatArray(3).also { AndroidColor.colorToHSV(argb, it) }

/**
 * Picks the secondary color. The dialog is themed with the color being picked, so its own
 * controls preview it. [onApply] gets null for the default palette.
 */
@Composable
fun SecondaryColorDialog(current: Int?, dark: Boolean, onApply: (Int?) -> Unit, onDismiss: () -> Unit) {
    val start = current ?: MaterialTheme.colorScheme.secondary.toArgb()
    var tab by remember { mutableStateOf(PickerTab.PRESETS) }
    // HSV drives the spectrum; presets and hex input also set an exact color, which HSV can't round-trip.
    val hsv = remember { hsvOf(start) }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    var exact by remember { mutableStateOf<Int?>(start) }
    val color = exact ?: AndroidColor.HSVToColor(floatArrayOf(hue, sat, value))

    fun setExact(argb: Int) {
        val h = hsvOf(argb)
        if (h[1] > 0f && h[2] > 0f) hue = h[0] // keep the hue for grays and black
        sat = h[1]
        value = h[2]
        exact = argb
    }

    MaterialTheme(colorScheme = MaterialTheme.colorScheme.withSecondary(color, dark)) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Secondary color") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        PickerTab.entries.forEachIndexed { i, t ->
                            SegmentedButton(
                                selected = tab == t, onClick = { tab = t },
                                shape = SegmentedButtonDefaults.itemShape(i, PickerTab.entries.size),
                                icon = {},
                            ) { Text(t.label, maxLines = 1) }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(208.dp)) {
                        when (tab) {
                            PickerTab.PRESETS -> Presets(color, ::setExact)
                            PickerTab.SPECTRUM -> Spectrum(
                                hue, sat, value, color,
                                onSv = { s, v -> sat = s; value = v; exact = null },
                                onHue = { h -> hue = h; exact = null },
                            )
                            PickerTab.HEX -> HexInput(color, ::setExact)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onApply(color) }) { Text("Apply") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { onApply(null) }) { Text("Reset") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Presets(color: Int, onColor: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PRESETS.forEach { (name, c) ->
            val selected = c == color
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Color(c))
                    .selectable(selected = selected, role = Role.RadioButton) { onColor(c) }
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Rounded.Check, null, tint = if (Color(c).luminance() > 0.5f) Color.Black else Color.White)
            }
        }
    }
}

@Composable
private fun Spectrum(
    hue: Float, sat: Float, value: Float, color: Int,
    onSv: (Float, Float) -> Unit, onHue: (Float) -> Unit,
) {
    val sv by rememberUpdatedState(onSv)
    val h by rememberUpdatedState(onHue)
    val hueColor = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp))
                .dragInput { p, size -> sv(fraction(p.x, size.width), 1f - fraction(p.y, size.height)) },
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            thumb(Offset(sat * size.width, (1 - value) * size.height), Color(color))
        }
        Canvas(
            Modifier.fillMaxWidth().height(28.dp)
                .dragInput { p, size -> h(fraction(p.x, size.width) * 359.9f) },
        ) {
            drawRoundRect(Brush.horizontalGradient(HUES), cornerRadius = CornerRadius(size.height / 2))
            thumb(Offset(hue / 360f * size.width, center.y), hueColor)
        }
    }
}

@Composable
private fun HexInput(color: Int, onColor: (Int) -> Unit) {
    var text by remember { mutableStateOf(hexOf(color)) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { v ->
                text = v.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(6)
                if (text.length == 6) onColor(0xFF000000.toInt() or text.toInt(16))
            },
            modifier = Modifier.fillMaxWidth(),
            prefix = { Text("#") },
            singleLine = true,
            isError = text.length != 6,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
        )
        Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)).background(Color(color)))
    }
}

private fun fraction(pos: Float, length: Int) = (pos / length).coerceIn(0f, 1f)

private fun Modifier.dragInput(onPoint: (Offset, IntSize) -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        onPoint(down.position, size)
        down.consume()
        drag(down.id) { change ->
            onPoint(change.position, size)
            change.consume()
        }
    }
}

private fun DrawScope.thumb(at: Offset, fill: Color) {
    drawCircle(Color.Black.copy(alpha = 0.3f), radius = 12.dp.toPx(), center = at)
    drawCircle(Color.White, radius = 11.dp.toPx(), center = at)
    drawCircle(fill, radius = 8.dp.toPx(), center = at)
}
