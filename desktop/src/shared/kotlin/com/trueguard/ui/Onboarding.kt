package com.trueguard.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trueguard.ui.components.PrimaryButton
import com.trueguard.ui.components.animatedPolygonShape
import kotlinx.coroutines.launch

private data class Intro(val icon: ImageVector, val title: String, val text: String)

private val Intros = listOf(
    Intro(Icons.Filled.CloudUpload, "Установка сервера", "Перейдите во вкладку «Деплой» и установите VPN на ваш VPS сервер в 1 клик."),
    Intro(Icons.Filled.VpnKey, "VK Хэши", "Создайте звонок ВКонтакте, скопируйте код из ссылки и вставьте в настройки Производительности."),
    Intro(Icons.Filled.Shield, "Готово!", "Добавьте сервер на Главной и нажмите большую кнопку для подключения!"),
)

/** Первый запуск (c1.c): три страницы, точки-индикаторы, «Далее» / «Начать». Значок сидит в морфящейся форме. */
@Composable
fun Onboarding(onFinish: () -> Unit) {
    val pager = rememberPagerState { Intros.size }
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(cs.background).safeDrawingPadding()) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val intro = Intros[page]
            Column(
                Modifier.fillMaxSize().padding(horizontal = 32.dp).padding(bottom = 140.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val shape = animatedPolygonShape(
                    when (pager.currentPage) {
                        0 -> MaterialShapes.Cookie9Sided
                        1 -> MaterialShapes.Clover4Leaf
                        else -> MaterialShapes.Sunny
                    }
                )
                Surface(Modifier.size(168.dp), shape = shape, color = cs.primaryContainer) {
                    Box(contentAlignment = Alignment.Center) { Icon(intro.icon, null, Modifier.size(72.dp), tint = cs.onPrimaryContainer) }
                }
                Spacer(Modifier.height(40.dp))
                Text(intro.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Text(intro.text, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(32.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(Intros.size) { i ->
                    val w by animateDpAsState(if (pager.currentPage == i) 24.dp else 8.dp)
                    val c by animateColorAsState(if (pager.currentPage == i) cs.primary else cs.outlineVariant)
                    Box(Modifier.width(w).height(8.dp).background(c, CircleShape))
                }
            }
            PrimaryButton(
                onClick = {
                    if (pager.currentPage < Intros.lastIndex) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } else onFinish()
                },
                modifier = Modifier.height(56.dp),
            ) {
                Text(if (pager.currentPage < Intros.lastIndex) "Далее" else "Начать", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
