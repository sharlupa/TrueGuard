package com.trueguard.ui.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private enum class BtnState { Idle, Connecting, Connected }

/**
 * Главная кнопка, как в APK: круг 150dp без ряби, при нажатии сжимается до 0.88.
 * Подключение — LoadingIndicator, подключено — цвет палитры, свечение и «живая капля».
 */
@Composable
fun ConnectButton(connected: Boolean, connecting: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val state = when {
        connected -> BtnState.Connected
        connecting -> BtnState.Connecting
        else -> BtnState.Idle
    }
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()

    Box(Modifier.size(232.dp), contentAlignment = Alignment.Center) {
        if (state != BtnState.Idle) Pulses(connected = state == BtnState.Connected)

        val glow by animateFloatAsState(if (state == BtnState.Connected) 0.35f else 0f, tween(700, easing = LinearOutSlowInEasing), label = "glowAlpha")
        if (glow > 0f) {
            Box(
                Modifier.size(220.dp).background(
                    Brush.radialGradient(listOf(cs.primary.copy(alpha = glow), cs.primary.copy(alpha = 0f))), CircleShape,
                )
            )
        }

        val scale by animateFloatAsState(
            when {
                pressed -> 0.88f
                state == BtnState.Connected -> 1.05f
                else -> 1f
            },
            spring(0.5f, 400f), label = "mainBtnScale",
        )
        // при подключении кнопка остаётся серой, как на телефоне; цвет палитры — только когда подключено
        // как на телефоне: цвет палитры сразу после нажатия, на всё время подключения
        val active = state != BtnState.Idle
        val container by animateColorAsState(if (active) cs.primary else cs.surfaceVariant, tween(500, easing = LinearOutSlowInEasing))
        val content by animateColorAsState(if (active) cs.onPrimary else cs.onSurfaceVariant)

        Surface(
            modifier = Modifier.size(150.dp).scale(scale)
                .clickable(interactionSource = src, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
            shape = CircleShape,
            color = container,
            contentColor = content,
            tonalElevation = if (state == BtnState.Connected) 8.dp else 2.dp,
            shadowElevation = if (state == BtnState.Connected) 22.dp else 7.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                AnimatedContent(
                    state,
                    transitionSpec = { (scaleIn(spring(0.6f, 300f)) + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
                    label = "btnContent",
                ) { st ->
                    when (st) {
                        BtnState.Connecting -> LoadingIndicator(Modifier.size(56.dp), color = content)
                        BtnState.Connected -> BlobLoader(content, Modifier.size(70.dp))
                        BtnState.Idle -> Icon(Icons.Filled.PowerSettingsNew, "Подключить", Modifier.size(56.dp), tint = content)
                    }
                }
            }
        }
    }
}

/** Расходящиеся волны вокруг кнопки (c.A). */
@Composable
private fun Pulses(connected: Boolean) {
    val cs = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "pulse")
    val dur = if (connected) 2000 else 1500
    // как в APK: волна расходится и стягивается обратно к кнопке (RepeatMode.Reverse)
    val wave = infiniteRepeatable<Float>(tween(dur, easing = FastOutSlowInEasing), RepeatMode.Reverse)
    val s1 by t.animateFloat(1f, if (connected) 1.35f else 1.8f, wave, label = "s1")
    val a1 by t.animateFloat(if (connected) 0.6f else 0.4f, 0f, wave, label = "a1")
    Box(Modifier.size(150.dp).scale(s1).alpha(a1).background(cs.primary, CircleShape))
    if (!connected) {
        val s2 by t.animateFloat(0.8f, 2.2f, infiniteRepeatable(tween(1500, 500, FastOutSlowInEasing)), label = "s2")
        val a2 by t.animateFloat(0.3f, 0f, infiniteRepeatable(tween(1500, 500, FastOutSlowInEasing)), label = "a2")
        Box(Modifier.size(150.dp).scale(s2).alpha(a2).background(cs.primary, CircleShape))
    }
}

/** «Живая капля» из APK (a.a.d): 10 точек на окружности с двумя синусоидами, сглаженные квадратичными кривыми. */
@Composable
fun BlobLoader(color: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "blobLoader")
    val rotation by t.animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "rotation")
    val phase1 by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "phase1")
    val phase2 by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "phase2")
    Canvas(modifier) {
        val cx = size.width / 2
        val cy = size.height / 2
        val r = min(size.width, size.height) / 2 * 0.72f
        val a1 = 0.14f * r
        val a2 = 0.08f * r
        val rot = Math.toRadians(rotation.toDouble())
        val pts = List(10) { i ->
            val th = i * 2 * PI / 10 + rot
            val d = sin(5 * th - phase2) * a2 + sin(3 * th + phase1) * a1 + r
            Offset((cos(th) * d).toFloat() + cx, cy + (sin(th) * d).toFloat())
        }
        val mids = List(10) { i -> (pts[i] + pts[(i + 1) % 10]) / 2f }
        val path = Path().apply {
            moveTo(mids[0].x, mids[0].y)
            for (i in 1..10) {
                val p = pts[i % 10]
                val m = mids[i % 10]
                quadraticTo(p.x, p.y, m.x, m.y)
            }
            close()
        }
        drawPath(path, color)
    }
}
