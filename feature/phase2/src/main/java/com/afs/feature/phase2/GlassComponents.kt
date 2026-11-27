package com.afs.feature.phase2

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.afs.style.api.LocalVisualEnvironment

/**
 * A layered glass surface. The visual mass comes from a contact shadow, an inner edge, a
 * top-left specular line and a bottom-edge refraction line, not merely a translucent fill.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 26.dp,
    padding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val tokens = LocalVisualEnvironment.current.tokens
    val dark = tokens.bg
    val surface = tokens.surface
    val line = tokens.line
    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            val radius = cornerRadius.toPx()
            drawRoundRect(
                color = Color.Black.copy(alpha = .34f),
                topLeft = Offset(0f, 7.dp.toPx()),
                size = Size(size.width, size.height),
                cornerRadius = CornerRadius(radius)
            )
            drawRoundRect(
                color = Color.Black.copy(alpha = .18f),
                topLeft = Offset(4.dp.toPx(), 12.dp.toPx()),
                size = Size((size.width - 8.dp.toPx()).coerceAtLeast(1f), size.height),
                cornerRadius = CornerRadius(radius)
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(cornerRadius))
                .then(Modifier)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = cornerRadius.toPx()
                drawRoundRect(
                    brush = Brush.linearGradient(
                        listOf(
                            Color(surface).copy(alpha = .96f),
                            Color(surface).copy(alpha = .76f),
                            Color(dark).copy(alpha = .90f)
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height)
                    ),
                    cornerRadius = CornerRadius(radius)
                )
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = .13f), Color.Transparent, Color.Black.copy(alpha = .18f))
                    ),
                    cornerRadius = CornerRadius(radius)
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = .20f),
                    cornerRadius = CornerRadius(radius),
                    style = Stroke(1.dp.toPx())
                )
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = .55f), Color.Transparent)
                    ),
                    start = Offset(size.width * .14f, 1.dp.toPx()),
                    end = Offset(size.width * .80f, 1.dp.toPx()),
                    strokeWidth = 1.2.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, Color(line).copy(alpha = .85f), Color.Transparent)
                    ),
                    start = Offset(size.width * .20f, size.height - 1.dp.toPx()),
                    end = Offset(size.width * .86f, size.height - 1.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
        Box(Modifier.padding(padding), content = content)
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    GlassPanel(modifier, cornerRadius, padding) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String? = null,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val moving = LocalVisualEnvironment.current.active &&
        LocalVisualEnvironment.current.quality.allowMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed && moving) .955f else 1f,
        animationSpec = spring(.66f, 520f),
        label = "glass-button-scale"
    )
    val glow by animateFloatAsState(
        targetValue = if (pressed && moving) 1f else .28f,
        animationSpec = spring(.82f, 360f),
        label = "glass-button-glow"
    )
    val tokens = LocalVisualEnvironment.current.tokens
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .then(if (tag == null) Modifier else Modifier.testTag(tag))
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRoundRect(
                color = Color.Black.copy(alpha = .30f),
                topLeft = Offset(0f, 4.dp.toPx()),
                size = Size(size.width, size.height),
                cornerRadius = CornerRadius(16.dp.toPx())
            )
            drawRoundRect(
                brush = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = .16f + glow * .12f),
                        Color(tokens.surface).copy(alpha = if (enabled) .92f else .48f),
                        Color(tokens.bg).copy(alpha = .92f)
                    )
                ),
                cornerRadius = CornerRadius(16.dp.toPx())
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = .16f * glow), Color.Transparent),
                    center = Offset(size.width / 2f, 0f),
                    radius = size.width * .62f
                ),
                radius = size.width * .62f,
                center = Offset(size.width / 2f, 0f)
            )
            drawRoundRect(
                color = Color.White.copy(alpha = if (enabled) .22f else .07f),
                cornerRadius = CornerRadius(16.dp.toPx()),
                style = Stroke(1.dp.toPx())
            )
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

data class GlassNavigationItem(
    val id: String,
    val label: String,
    val description: String
)

@Composable
fun GlassNavigationBar(
    items: List<GlassNavigationItem>,
    selectedId: String,
    onSelect: (GlassNavigationItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val environment = LocalVisualEnvironment.current
    val moving = environment.active && environment.quality.allowMotion()
    val tokens = LocalVisualEnvironment.current.tokens
    GlassPanel(modifier.fillMaxWidth(), cornerRadius = 28.dp, padding = PaddingValues(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items.forEach { item ->
                val selected = item.id == selectedId
                val fill by animateColorAsState(
                    targetValue = if (selected) Color(tokens.accent).copy(alpha = .17f) else Color.Transparent,
                    label = "glass-nav-fill"
                )
                val interaction = remember(item.id) { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val lift by animateFloatAsState(
                    targetValue = if (pressed && moving) .94f else 1f,
                    animationSpec = spring(.62f, 520f),
                    label = "glass-nav-press"
                )
                Box(
                    Modifier
                        .weight(1f)
                        .graphicsLayer { scaleX = lift; scaleY = lift }
                        .clip(RoundedCornerShape(22.dp))
                        .clickable(
                            interactionSource = interaction,
                            indication = LocalIndication.current
                        ) { onSelect(item) }
                        .semantics {
                            this.selected = selected
                            contentDescription = item.description
                            role = Role.Tab
                        }
                        .testTag("glass-nav-${item.id}"),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.matchParentSize()) {
                        if (fill.alpha > 0f) {
                            drawRoundRect(
                                color = fill,
                                cornerRadius = CornerRadius(22.dp.toPx())
                            )
                            drawRoundRect(
                                color = Color.White.copy(alpha = .16f),
                                cornerRadius = CornerRadius(22.dp.toPx()),
                                style = Stroke(1.dp.toPx())
                            )
                        }
                    }
                    Row(
                        Modifier.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Box(
                            Modifier
                                .size(if (selected) 7.dp else 5.dp)
                                .clip(CircleShape)
                                .then(
                                    Modifier.graphicsLayer {
                                        alpha = if (selected) 1f else .45f
                                    }
                                )
                        ) {
                            Canvas(Modifier.fillMaxSize()) {
                                drawCircle(
                                    if (selected) Color(tokens.accent)
                                    else Color(tokens.text2).copy(alpha = .7f)
                                )
                            }
                        }
                        Text(
                            item.label,
                            color = if (selected) Color(tokens.text) else Color(tokens.text2),
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GlassDialog(
    onDismiss: () -> Unit,
    title: String,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    dismissLabel: String = "取消",
    content: @Composable ColumnScope.() -> Unit
) {
    val status = phase2StatusPalette()
    Dialog(onDismissRequest = onDismiss) {
        GlassPanel(Modifier.fillMaxWidth(), cornerRadius = 28.dp, padding = PaddingValues(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title, color = status.text, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                content()
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                ) {
                    GlassButton(onClick = onDismiss) {
                        Text(dismissLabel, color = status.textMuted, fontSize = 12.sp)
                    }
                    if (confirmLabel != null && onConfirm != null) {
                        GlassButton(onClick = onConfirm) {
                            Text(confirmLabel, color = status.info, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
