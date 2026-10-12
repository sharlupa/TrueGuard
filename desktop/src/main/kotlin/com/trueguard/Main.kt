package com.trueguard

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.trueguard.core.*
import com.trueguard.ui.AppShell
import com.trueguard.ui.Onboarding
import com.trueguard.ui.components.AppTextField
import com.trueguard.ui.components.PlainButton
import com.trueguard.ui.components.PrimaryButton
import com.trueguard.ui.theme.SystemAppearance
import com.trueguard.ui.theme.TrueGuardTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.awt.Dimension
import kotlin.system.exitProcess

/** Состояние окна, которое меняют трей и второй запуск приложения. */
object AppWindow {
    var visible by mutableStateOf(true)
    var focusRequests by mutableIntStateOf(0)

    fun show() {
        visible = true
        focusRequests++
    }
}

/**
 * Тайлинговые композиторы (niri, sway, Hyprland, i3…) не оборачивают окна X11 в рамку,
 * а Java по умолчанию этого ждёт — окно получает неверный размер, по краям видны белые полосы.
 */
fun nonReparentingSession(): Boolean {
    if (System.getenv("DISPLAY").isNullOrEmpty()) return false
    val desk = listOfNotNull(System.getenv("XDG_CURRENT_DESKTOP"), System.getenv("XDG_SESSION_DESKTOP"), System.getenv("DESKTOP_SESSION"))
        .joinToString(":").lowercase()
    val tiling = listOf(
        "niri", "sway", "hyprland", "river", "wayfire", "labwc", "dwl", "i3", "bspwm", "dwm", "qtile", "xmonad",
        "awesome", "herbstluftwm", "leftwm", "spectrwm", "cosmic",
    )
    if (tiling.any { it in desk }) return true
    val reparenting = listOf("gnome", "kde", "plasma", "xfce", "mate", "cinnamon", "lxde", "lxqt", "unity", "budgie", "pantheon", "deepin")
    return System.getenv("XDG_SESSION_TYPE") == "wayland" && reparenting.none { it in desk }
}

/** Перезапуск с _JAVA_AWT_WM_NONREPARENTING=1: переменную AWT читает только из окружения процесса. */
private fun relaunchNonReparenting(args: Array<String>): Boolean {
    if (System.getenv("_JAVA_AWT_WM_NONREPARENTING") != null || System.getenv("TRUEGUARD_RELAUNCHED") != null) return false
    if (!nonReparentingSession()) return false
    // путь к лаунчеру jpackage; при запуске из исходников (gradle run) перезапуск не нужен
    val launcher = System.getProperty("jpackage.app-path")?.takeIf { java.io.File(it).canExecute() } ?: return false
    return runCatching {
        val pb = ProcessBuilder(listOf(launcher) + args).inheritIO()
        // метку «я дочерний процесс» лаунчер jpackage ставит себе сам; с ней новый лаунчер
        // решит, что ему передали аргументы java, и не запустит приложение
        pb.environment().keys.removeIf { it.startsWith("_JPACKAGE") || it.startsWith("JPACKAGE") }
        pb.environment()["_JAVA_AWT_WM_NONREPARENTING"] = "1"
        pb.environment()["TRUEGUARD_RELAUNCHED"] = "1"
        pb.start()
        true
    }.getOrDefault(false)
}

fun main(args: Array<String>) {
    if (relaunchNonReparenting(args)) exitProcess(0)
    com.trueguard.core.Platform.hooks = com.trueguard.platform.DesktopPlatform
    val minimized = "--minimized" in args
    val connect = "--connect" in args

    // второй запуск передаёт команду уже открытому окну и выходит
    if (SingleInstance.forward(if (connect) "connect" else "show")) exitProcess(0)

    Settings.instance.load()
    runBlocking { runCatching { SystemAppearance.refresh() } }
    if (Settings.instance.autostart) Desktop.setAutostart(true) // путь к бинарнику мог смениться
    LogStore.event("TrueGuard $APP_VERSION запущен", key = "app_start")

    AppWindow.visible = !minimized
    application(exitProcessOnExit = true) {
        val scope = rememberCoroutineScope()
        val quit: () -> Unit = {
            scope.launch {
                TunnelController.shutdown()
                Tray.stop()
                SingleInstance.close()
                exitApplication()
            }
        }
        LaunchedEffect(Unit) {
            launch { SystemAppearance.watch() }
            SingleInstance.listen { cmd ->
                if (cmd == "connect" && !TunnelController.phase.active) TunnelController.connect()
                AppWindow.show()
            }
            Tray.start(onShow = AppWindow::show, onQuit = quit)
            if (connect || Settings.instance.connectOnLaunch) {
                kotlinx.coroutines.delay(600)
                TunnelController.connect()
            }
        }

        val state = rememberWindowState(size = DpSize(460.dp, 900.dp), position = WindowPosition.PlatformDefault)
        Window(
            onCloseRequest = {
                if (Settings.instance.closeToTray && Tray.available) AppWindow.visible = false else quit()
            },
            state = state,
            visible = AppWindow.visible,
            title = "TrueGuard",
            icon = painterResource("icons/app256.png"),
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(360, 480) }
            LaunchedEffect(AppWindow.focusRequests) {
                if (AppWindow.focusRequests > 0) {
                    window.isMinimized = false
                    window.toFront()
                    window.requestFocus()
                }
            }
            val s = Settings.instance
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density * s.uiScale / 100f, d.fontScale)) {
                TrueGuardTheme {
                    // фон самого окна AWT — под цвет темы, чтобы при изменении размера не мелькал белый
                    val bg = MaterialTheme.colorScheme.background
                    LaunchedEffect(bg) {
                        val c = java.awt.Color(bg.toArgb())
                        window.background = c
                        window.contentPane.background = c
                    }
                    Surface(Modifier.fillMaxSize(), color = bg) {
                        if (!s.onboardingDone) Onboarding(onFinish = { s.onboardingDone = true }) else AppShell()
                        RootPasswordHost()
                    }
                }
            }
        }
    }
}

/**
 * Пароль для sudo, когда в сессии нет графического агента polkit:
 * TunnelController ждёт ответа, окно показывается поверх приложения.
 */
@Composable
private fun RootPasswordHost() {
    var request by remember { mutableStateOf<CompletableDeferred<String?>?>(null) }
    DisposableEffect(Unit) {
        TunnelController.askRootPassword = {
            val d = CompletableDeferred<String?>()
            AppWindow.show()
            request = d
            d.await()
        }
        onDispose { TunnelController.askRootPassword = null }
    }
    val req = request ?: return
    var password by remember(req) { mutableStateOf("") }
    val user = System.getenv("USER").orEmpty()
    fun finish(v: String?) {
        req.complete(v?.takeIf { it.isNotEmpty() })
        request = null
    }
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Filled.AdminPanelSettings, null) },
        title = { Text("Права администратора") },
        text = {
            Column(Modifier.widthIn(max = 420.dp)) {
                Text("Режим TUN меняет маршруты системы, поэтому ядро запускается через sudo.")
                Spacer(Modifier.height(16.dp))
                AppTextField(password, { password = it }, if (user.isEmpty()) "Пароль" else "Пароль пользователя $user", password = true)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Пароль не сохраняется на диск: он хранится в памяти до выхода из TrueGuard, чтобы автопереподключение не спрашивало его снова.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { PrimaryButton(onClick = { finish(password) }, enabled = password.isNotEmpty()) { Text("Подключить") } },
        dismissButton = { PlainButton(onClick = { finish(null) }) { Text("Отмена") } },
    )
}
