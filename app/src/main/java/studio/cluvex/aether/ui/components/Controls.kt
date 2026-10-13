package studio.cluvex.aether.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.ui.theme.AetherColors
import studio.cluvex.aether.ui.theme.MonoStyle
import studio.cluvex.aether.ui.theme.NumericStyle
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun RadioDot(selected: Boolean, accent: Color, modifier: Modifier = Modifier) {
    val fill by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "radio")
    val edge by animateColorAsState(if (selected) accent else AetherColors.HairlineStrong, tween(200), label = "radioEdge")
    Box(
        modifier = modifier.size(20.dp).clip(CircleShape).border(1.5.dp, edge, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(10.dp).scale(fill).clip(CircleShape).background(accent))
    }
}

/** A pill row with a sliding highlight behind the chosen segment. */
@Composable
fun <T> SegmentedPicker(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = AetherColors.Control,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(14.dp)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(AetherColors.Ink.copy(alpha = 0.05f))
            .border(1.dp, AetherColors.Hairline, shape)
            .padding(4.dp),
    ) {
        val segment = maxWidth / options.size.coerceAtLeast(1)
        val index = options.indexOf(selected).coerceAtLeast(0)
        val offset by animateDpAsState(segment * index, spring(dampingRatio = 0.8f, stiffness = 520f), label = "segment")
        val inner = RoundedCornerShape(10.dp)

        Box(
            Modifier
                .offset(x = offset)
                .width(segment)
                .fillMaxHeight()
                .clip(inner)
                .background(accent)
        )
        Row(Modifier.fillMaxSize()) {
            options.forEach { option ->
                val active = option == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(inner)
                        .clickable(enabled = enabled, role = Role.RadioButton) { onSelect(option) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) AetherColors.Void else AetherColors.InkMuted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
fun OptionChip(
    glyph: GlyphKind,
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = AetherColors.Cyan,
) {
    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(glyph, tint, Modifier.size(18.dp))
            Spacer(Modifier.weight(1f))
            Glyph(GlyphKind.Chevron, AetherColors.InkFaint, Modifier.size(14.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = AetherColors.InkFaint, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = AetherColors.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    accent: Color = AetherColors.Control,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
            .padding(vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = AetherColors.Ink)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AetherColors.InkMuted)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AetherColors.Void,
                checkedTrackColor = accent,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = AetherColors.InkMuted,
                uncheckedTrackColor = AetherColors.Ink.copy(alpha = 0.06f),
                uncheckedBorderColor = AetherColors.HairlineStrong,
                disabledCheckedTrackColor = accent.copy(alpha = 0.35f),
                disabledCheckedThumbColor = AetherColors.Void,
                disabledUncheckedTrackColor = AetherColors.Ink.copy(alpha = 0.03f),
            ),
        )
    }
}

@Composable
fun SliderRow(
    title: String,
    value: Int,
    range: IntRange,
    unit: String,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 1,
    enabled: Boolean = true,
    accent: Color = AetherColors.Control,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp).alpha(if (enabled) 1f else 0.45f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = AetherColors.Ink, modifier = Modifier.weight(1f))
            Text(
                "$value $unit",
                style = MaterialTheme.typography.labelLarge.merge(NumericStyle),
                color = accent,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { raw -> onChange(((raw / step).roundToInt() * step).coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = AetherColors.Ink.copy(alpha = 0.08f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

@Composable
fun ChoiceRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = AetherColors.Control,
    enabled: Boolean = true,
) {
    val background by animateColorAsState(
        if (selected) accent.copy(alpha = 0.10f) else Color.Transparent, tween(220), label = "choice",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioDot(selected, accent)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = AetherColors.Ink)
            Spacer(Modifier.height(2.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = AetherColors.InkMuted)
        }
    }
}

/** A labelled single-line input in the glass style. */
@Composable
fun FieldRow(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    helper: String? = null,
    error: String? = null,
    numeric: Boolean = false,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val edge by animateColorAsState(
        when {
            error != null -> AetherColors.Rose.copy(alpha = 0.7f)
            focused -> AetherColors.Cyan.copy(alpha = 0.7f)
            else -> AetherColors.Hairline
        },
        tween(180),
        label = "fieldEdge",
    )
    val shape = RoundedCornerShape(14.dp)

    Column(modifier.fillMaxWidth().padding(vertical = 8.dp).alpha(if (enabled) 1f else 0.45f)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = AetherColors.Ink)
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = MonoStyle.copy(color = AetherColors.Ink, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
            cursorBrush = SolidColor(AetherColors.Cyan),
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Uri),
            interactionSource = interaction,
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(AetherColors.Ink.copy(alpha = 0.04f))
                        .border(1.dp, edge, shape)
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                ) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = MonoStyle.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize), color = AetherColors.InkFaint)
                    }
                    inner()
                }
            },
        )
        val note = error ?: helper
        if (note != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) AetherColors.Rose else AetherColors.InkFaint,
            )
        }
    }
}

/** A port input that only reports values an unprivileged app may bind. */
@Composable
fun PortRow(
    title: String,
    port: Int,
    onPortChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    conflictsWith: Int? = null,
) {
    var text by remember(port) { mutableStateOf(port.toString()) }
    val parsed = text.toIntOrNull()
    val rangeError = stringResource(R.string.advanced_port_error)
    val conflictError = stringResource(R.string.advanced_port_conflict)
    val error = when {
        parsed == null || parsed !in 1024..65535 -> rangeError
        conflictsWith != null && parsed == conflictsWith -> conflictError
        else -> null
    }
    FieldRow(
        title = title,
        value = text,
        onValueChange = { raw ->
            text = raw.filter(Char::isDigit).take(5)
            val next = text.toIntOrNull()
            if (next != null && next in 1024..65535 && next != conflictsWith) onPortChange(next)
        },
        modifier = modifier,
        placeholder = "1819",
        helper = stringResource(R.string.advanced_port_helper),
        error = error,
        numeric = true,
        enabled = enabled,
    )
}

@Composable
fun InfoRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    glyph: GlyphKind? = null,
    mono: Boolean = false,
    valueColor: Color = AetherColors.InkMuted,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            Glyph(glyph, AetherColors.InkMuted, Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(title, style = MaterialTheme.typography.titleSmall, color = AetherColors.Ink, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = if (mono) MonoStyle else MaterialTheme.typography.bodyMedium,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 210.dp),
        )
        if (onClick != null) {
            Spacer(Modifier.width(6.dp))
            Glyph(GlyphKind.Chevron, AetherColors.InkFaint, Modifier.size(16.dp))
        }
    }
}

@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(AetherColors.Hairline))
}

@Composable
fun StatTile(
    label: String,
    value: String,
    unit: String,
    glyph: GlyphKind,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Glyph(glyph, tint, Modifier.size(13.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.width(8.dp))
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = AetherColors.InkFaint, maxLines = 1)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge.merge(NumericStyle),
                color = AetherColors.Ink,
                maxLines = 1,
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(
                    unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = AetherColors.InkMuted,
                    modifier = Modifier.padding(bottom = 3.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

object Formats {
    fun rate(bytesPerSecond: Long): Pair<String, String> = when {
        bytesPerSecond >= 1_000_000 -> String.format(Locale.US, "%.1f", bytesPerSecond / 1_000_000.0) to "MB/s"
        bytesPerSecond >= 1_000 -> String.format(Locale.US, "%.0f", bytesPerSecond / 1_000.0) to "KB/s"
        else -> bytesPerSecond.coerceAtLeast(0).toString() to "B/s"
    }

    fun bytes(total: Long): String = when {
        total >= 1_000_000_000 -> String.format(Locale.US, "%.2f GB", total / 1_000_000_000.0)
        total >= 1_000_000 -> String.format(Locale.US, "%.1f MB", total / 1_000_000.0)
        total >= 1_000 -> String.format(Locale.US, "%.0f KB", total / 1_000.0)
        else -> "${total.coerceAtLeast(0)} B"
    }

    fun duration(millis: Long): String {
        val seconds = (millis / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    }
}
