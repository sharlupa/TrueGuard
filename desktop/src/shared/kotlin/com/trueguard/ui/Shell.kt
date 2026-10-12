package com.trueguard.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trueguard.core.LogStore
import com.trueguard.core.Phase
import com.trueguard.core.TunnelController
import com.trueguard.ui.dashboard.Dashboard
import com.trueguard.ui.deploy.DeployScreen
import com.trueguard.ui.exceptions.ExceptionsScreen
import com.trueguard.ui.logs.LogsScreen
import com.trueguard.ui.settings.SettingsHost
import kotlinx.coroutines.launch
import kotlin.math.abs

data class NavItem(val label: String, val selected: ImageVector, val unselected: ImageVector)

/** Вкладки нижней панели — как в APK (a5/c1). */
val NavItems = listOf(
    NavItem("Главная", Icons.Filled.Home, Icons.Outlined.Home),
    NavItem("Деплой", Icons.Filled.Cloud, Icons.Outlined.Cloud),
    NavItem("Исключения", Icons.Filled.FilterList, Icons.Outlined.FilterList),
    NavItem("Логи", Icons.Filled.Terminal, Icons.Outlined.Terminal),
    NavItem("Настройки", Icons.Filled.Settings, Icons.Outlined.Settings),
)

/** Снэкбар доступен всем экранам. */
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/** Переход на вкладку из любого экрана (например, «Открыть логи» на дашборде). */
val LocalOpenTab = staticCompositionLocalOf<(Int) -> Unit> { {} }

/** Ширина одноколоночных экранов на широком окне: макет телефона не растягивается на весь монитор. */
val ContentMaxWidth = 720.dp

/** Окно уже телефона — нижняя панель как в APK; шире — боковая панель MD3 Expressive. */
val RailBreakpoint = 600.dp

@Composable
fun AppShell(startPage: Int = 0) {
    val pager = rememberPagerState(initialPage = startPage) { NavItems.size }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val openTab: (Int) -> Unit = { i -> scope.launch { pager.animateScrollToPage(i, animationSpec = spring(0.8f, 380f)) } }
    val cs = MaterialTheme.colorScheme
    val t = TunnelController
    LogStore.version.intValue // перерисовка счётчика ошибок

    CompositionLocalProvider(LocalSnackbar provides snackbar, LocalOpenTab provides openTab) {
        BoxWithConstraints(Modifier.fillMaxSize().background(cs.background)) {
            if (maxWidth < RailBreakpoint) {
                Scaffold(
                    containerColor = cs.background,
                    // верх и бока отступает MainActivity, низ — сама панель (она заходит под жестовую полосу)
                    contentWindowInsets = WindowInsets(0),
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        BottomBar(
                            selected = pager.targetPage,
                            onSelect = openTab,
                            errors = LogStore.errorCount,
                            connected = t.phase == Phase.Connected,
                        )
                    },
                ) { pad -> Pages(pager, Modifier.padding(pad), swipe = true) }
            } else {
                val expanded = maxWidth >= 1200.dp
                Row(Modifier.fillMaxSize().navigationBarsPadding()) {
                    SideRail(
                        selected = pager.targetPage,
                        onSelect = openTab,
                        errors = LogStore.errorCount,
                        connected = t.phase == Phase.Connected,
                        startExpanded = expanded,
                    )
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Pages(pager, Modifier, swipe = false)
                        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Pages(pager: androidx.compose.foundation.pager.PagerState, modifier: Modifier, swipe: Boolean) {
    HorizontalPager(
        state = pager,
        modifier = modifier.fillMaxSize(),
        beyondViewportPageCount = 2,
        userScrollEnabled = swipe,
    ) { page ->
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                // соседняя вкладка слегка гаснет и уменьшается, как в APK
                val f = abs(pager.currentPageOffsetFraction + (pager.currentPage - page)).coerceIn(0f, 1f)
                alpha = 1f - f * 0.36f
                scaleX = 1f - f * 0.035f
                scaleY = 1f - f * 0.035f
            },
        ) {
            when (page) {
                0 -> Dashboard()
                1 -> DeployScreen()
                2 -> ExceptionsScreen()
                3 -> LogsScreen()
                else -> SettingsHost()
            }
        }
    }
}

/**
 * Боковая панель для широкого окна: официальный WideNavigationRail из MD3 Expressive.
 * Сворачивается в узкую полосу значков и разворачивается с подписями кнопкой-меню.
 */
@Composable
private fun SideRail(selected: Int, onSelect: (Int) -> Unit, errors: Int, connected: Boolean, startExpanded: Boolean) {
    val cs = MaterialTheme.colorScheme
    val state = rememberWideNavigationRailState(if (startExpanded) WideNavigationRailValue.Expanded else WideNavigationRailValue.Collapsed)
    LaunchedEffect(startExpanded) {
        state.snapTo(if (startExpanded) WideNavigationRailValue.Expanded else WideNavigationRailValue.Collapsed)
    }
    val scope = rememberCoroutineScope()
    val isExpanded = state.targetValue == WideNavigationRailValue.Expanded
    WideNavigationRail(
        state = state,
        shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
        colors = WideNavigationRailDefaults.colors(containerColor = cs.surfaceContainerHigh),
        header = {
            Column(Modifier.padding(start = 20.dp, top = 8.dp, bottom = 16.dp)) {
                IconButton(onClick = { scope.launch { state.toggle() } }, shapes = IconButtonDefaults.shapes()) {
                    Icon(if (isExpanded) Icons.AutoMirrored.Filled.MenuOpen else Icons.Filled.Menu, if (isExpanded) "Свернуть" else "Развернуть")
                }
                Spacer(Modifier.height(8.dp))
                // только название: мелкий логотип в панели не читается
                androidx.compose.animation.AnimatedVisibility(isExpanded) {
                    Text("TrueGuard", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                }
            }
        },
    ) {
        NavItems.forEachIndexed { i, nav ->
            val badge = when {
                i == 0 && connected -> -1
                i == 3 && errors > 0 -> errors
                else -> 0
            }
            WideNavigationRailItem(
                selected = selected == i,
                onClick = { onSelect(i) },
                icon = {
                    BadgedBox(badge = {
                        when {
                            badge < 0 -> Badge(containerColor = cs.primary)
                            badge > 0 -> Badge { Text(if (badge > 99) "99+" else "$badge") }
                        }
                    }) { Icon(if (selected == i) nav.selected else nav.unselected, nav.label) }
                },
                label = { Text(nav.label, fontWeight = if (selected == i) FontWeight.Bold else FontWeight.Medium) },
                railExpanded = isExpanded,
            )
        }
    }
}

/**
 * Нижняя панель из APK: плавающая подложка со скруглением 28dp сверху и «червяк»-индикатор,
 * края которого догоняют друг друга с разной жёсткостью пружины.
 */
@Composable
fun BottomBar(selected: Int, onSelect: (Int) -> Unit, errors: Int, connected: Boolean) {
    val cs = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = cs.surfaceContainerHigh.copy(alpha = 0.96f),
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
    ) {
        Box(Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
            var width by remember { mutableStateOf(0) }
            val density = LocalDensity.current
            Box(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().height(84.dp).onSizeChanged { width = it.width }) {
                val item = with(density) { width.toDp() } / NavItems.size
                val transition = updateTransition(selected, "tab_transition")
                val left by transition.animateDp(
                    transitionSpec = { if (targetState > initialState) spring(0.65f, 150f) else spring(0.65f, 400f) }, label = "left",
                ) { item * it + item / 2 - 32.dp }
                val right by transition.animateDp(
                    transitionSpec = { if (targetState > initialState) spring(0.65f, 400f) else spring(0.65f, 150f) }, label = "right",
                ) { item * it + item / 2 + 32.dp }
                Box(
                    Modifier.offset(x = left, y = 14.dp).width((right - left).coerceAtLeast(0.dp)).height(38.dp)
                        .background(cs.primaryContainer, CircleShape)
                )
                Row(Modifier.fillMaxSize()) {
                    NavItems.forEachIndexed { i, nav ->
                        NavButton(
                            nav, selected == i, Modifier.weight(1f),
                            badge = when {
                                i == 0 && connected -> -1
                                i == 3 && errors > 0 -> errors
                                else -> 0
                            },
                        ) { onSelect(i) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavButton(nav: NavItem, selected: Boolean, modifier: Modifier, badge: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val iconColor by animateColorAsState(if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant)
    val labelColor by animateColorAsState(if (selected) cs.onSurface else cs.onSurfaceVariant)
    val scale by animateFloatAsState(if (selected) 1.1f else 0.94f, spring(stiffness = 400f))
    Column(
        modifier.fillMaxHeight().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(32.dp), contentAlignment = Alignment.Center) {
            BadgedBox(badge = {
                when {
                    badge < 0 -> Badge(containerColor = cs.primary)
                    badge > 0 -> Badge { Text(if (badge > 99) "99+" else "$badge") }
                }
            }) {
                Icon(if (selected) nav.selected else nav.unselected, nav.label, Modifier.size(24.dp).scale(scale), tint = iconColor)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            nav.label, color = labelColor, maxLines = 1, style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}
