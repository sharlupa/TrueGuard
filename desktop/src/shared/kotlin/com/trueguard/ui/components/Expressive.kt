package com.trueguard.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon

/**
 * Форма из морфа двух многоугольников MaterialShapes: progress 0 — первая форма, 1 — вторая.
 * Многоугольники MaterialShapes нормализованы в квадрат 0..1, поэтому путь просто растягивается под размер.
 */
class MorphPolygonShape(private val morph: Morph, private val progress: Float, private val rotation: Float = 0f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path: Path = morph.toPath(progress.coerceIn(-0.1f, 1.1f))
        val m = Matrix()
        if (rotation != 0f) {
            m.translate(size.width / 2f, size.height / 2f)
            m.rotateZ(rotation)
            m.translate(-size.width / 2f, -size.height / 2f)
        }
        m.scale(size.width, size.height)
        path.transform(m)
        return Outline.Generic(path)
    }
}

/**
 * Форма, которая перетекает в [target] при каждой его смене (пружина из MotionScheme.expressive).
 * Если смена пришла посреди анимации, новая морфа стартует от ближайшей формы — без рывков.
 */
@Composable
fun animatedPolygonShape(
    target: RoundedPolygon,
    rotation: Float = 0f,
    spec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultSpatialSpec(),
): Shape {
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target === to) return@LaunchedEffect
        from = if (progress.value < 0.5f) from else to
        to = target
        progress.snapTo(0f)
        progress.animateTo(1f, spec)
    }
    val morph = remember(from, to) { Morph(from, to) }
    return MorphPolygonShape(morph, progress.value, rotation)
}

/** Круглая кнопка-значок с морфом при нажатии (IconButtonDefaults.shapes()). */
@Composable
fun MorphIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = contentColorFor(container).takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        modifier = modifier,
        enabled = enabled,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = content),
        content = icon,
    )
}

/** Кнопки MD3 Expressive: форма сжимается при нажатии (ButtonDefaults.shapes()). */
@Composable
fun PrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, colors: ButtonColors = ButtonDefaults.buttonColors(), content: @Composable RowScope.() -> Unit) =
    Button(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = modifier, enabled = enabled, colors = colors, content = content)

@Composable
fun TonalButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(), content: @Composable RowScope.() -> Unit) =
    FilledTonalButton(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = modifier, enabled = enabled, colors = colors, content = content)

@Composable
fun OutlineButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, colors: ButtonColors = ButtonDefaults.outlinedButtonColors(), content: @Composable RowScope.() -> Unit) =
    OutlinedButton(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = modifier, enabled = enabled, colors = colors, content = content)

@Composable
fun PlainButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, colors: ButtonColors = ButtonDefaults.textButtonColors(), content: @Composable RowScope.() -> Unit) =
    TextButton(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = modifier, enabled = enabled, colors = colors, content = content)

/**
 * Связанная группа переключателей MD3 Expressive (замена SegmentedButton):
 * выбранная кнопка становится «таблеткой», соседние морфят углы при нажатии.
 */
@Composable
fun <T> ConnectedChoice(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, (value, label) ->
            ToggleButton(
                checked = value == selected,
                onCheckedChange = { onSelect(value) },
                enabled = enabled,
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(label, maxLines = 1, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
