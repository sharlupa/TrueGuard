package com.trueguard.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.trueguard.core.Platform
import com.trueguard.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Палитры приложения — значения из TrueGuard 1.2.4 (a5/s3). */
data class Palette(val id: String, val name: String, val seed: Color)

val Palettes = listOf(
    Palette("tyrn", "Tyrn", Color(0xFF176B74)),
    Palette("forest", "Лес", Color(0xFF386A20)),
    Palette("violet", "Фиолет", Color(0xFF7650A6)),
    Palette("sunset", "Закат", Color(0xFF9C4100)),
    Palette("ocean", "Океан", Color(0xFF00629E)),
    Palette("rose", "Роза", Color(0xFFAF3F5F)),
)


/** Типографика из APK: заголовки жирнее стандартных M3. Шрифт — Roboto (на компьютере встроенный). */
fun appTypography(family: FontFamily): Typography {
    fun style(size: Int, line: Int, weight: FontWeight, spacing: Double = 0.0) =
        TextStyle(fontFamily = family, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = spacing.sp)
    return Typography(
    displayLarge = style(57, 64, FontWeight.Bold),
    displayMedium = style(45, 52, FontWeight.Bold),
    displaySmall = style(36, 44, FontWeight.Bold),
    headlineLarge = style(32, 40, FontWeight.SemiBold),
    headlineMedium = style(28, 36, FontWeight.SemiBold),
    headlineSmall = style(24, 32, FontWeight.SemiBold),
    titleLarge = style(22, 28, FontWeight.SemiBold),
    titleMedium = style(16, 24, FontWeight.SemiBold, 0.15),
    titleSmall = style(14, 20, FontWeight.Medium, 0.1),
    bodyLarge = style(16, 24, FontWeight.Normal, 0.5),
    bodyMedium = style(14, 20, FontWeight.Normal, 0.25),
    bodySmall = style(14, 20, FontWeight.Normal, 0.4),
    labelLarge = style(14, 20, FontWeight.Medium, 0.1),
    labelMedium = style(12, 16, FontWeight.Medium, 0.5),
    labelSmall = style(11, 16, FontWeight.Medium, 0.5),
    )
}

private val TyrnLight = lightColorScheme(
    primary = Color(0xff176b74), onPrimary = Color(0xffffffff), primaryContainer = Color(0xffbeebf0), onPrimaryContainer = Color(0xff002023),
    inversePrimary = Color(0xff82d5dc), secondary = Color(0xff586249), onSecondary = Color(0xffffffff), secondaryContainer = Color(0xffdce8c7),
    onSecondaryContainer = Color(0xff161f0b), tertiary = Color(0xff75546f), onTertiary = Color(0xffffffff), tertiaryContainer = Color(0xffffd7f3),
    onTertiaryContainer = Color(0xff2c122a), background = Color(0xfff6fbf8), onBackground = Color(0xff171d1a), surface = Color(0xfff6fbf8),
    onSurface = Color(0xff171d1a), surfaceVariant = Color(0xffdce5df), onSurfaceVariant = Color(0xff404943), inverseSurface = Color(0xff2c322f),
    inverseOnSurface = Color(0xffedf3ee), error = Color(0xffba1a1a), onError = Color(0xffffffff), errorContainer = Color(0xffffdad6),
    onErrorContainer = Color(0xff410002), outline = Color(0xff707973), outlineVariant = Color(0xffc0cac3), surfaceContainer = Color(0xffeaf2ec),
    surfaceContainerHigh = Color(0xffe4ece6), surfaceContainerHighest = Color(0xffdde7e1), surfaceContainerLow = Color(0xfff0f7f2),
    surfaceContainerLowest = Color(0xffffffff),
)

private val TyrnDark = darkColorScheme(
    primary = Color(0xff82d5dc), onPrimary = Color(0xff00363b), primaryContainer = Color(0xff0b4f55), onPrimaryContainer = Color(0xffbeebf0),
    inversePrimary = Color(0xff176b74), secondary = Color(0xffc0ccad), onSecondary = Color(0xff2b341e), secondaryContainer = Color(0xff414a33),
    onSecondaryContainer = Color(0xffdce8c7), tertiary = Color(0xffe4bbd8), onTertiary = Color(0xff43263f), tertiaryContainer = Color(0xff5b3c56),
    onTertiaryContainer = Color(0xffffd7f3), background = Color(0xff0e1512), onBackground = Color(0xffdde4df), surface = Color(0xff0e1512),
    onSurface = Color(0xffdde4df), surfaceVariant = Color(0xff404943), onSurfaceVariant = Color(0xffc0cac3), inverseSurface = Color(0xffdde4df),
    inverseOnSurface = Color(0xff2c322f), error = Color(0xffffb4ab), onError = Color(0xff690005), errorContainer = Color(0xff93000a),
    onErrorContainer = Color(0xffffdad6), outline = Color(0xff8a938d), outlineVariant = Color(0xff404943), surfaceContainer = Color(0xff18201c),
    surfaceContainerHigh = Color(0xff222a26), surfaceContainerHighest = Color(0xff2d3531), surfaceContainerLow = Color(0xff141b18),
    surfaceContainerLowest = Color(0xff090f0d),
)

/** Набор переопределений палитры поверх базовой схемы Tyrn (как ColorScheme.copy в APK). */
private class Over(
    val primary: Long, val onPrimary: Long?, val pc: Long, val opc: Long, val secondary: Long, val sc: Long,
    val tertiary: Long, val tc: Long, val bg: Long, val cont: Long, val high: Long, val highest: Long, val low: Long, val lowest: Long?,
)

private fun ColorScheme.with(o: Over) = copy(
    primary = Color(o.primary), onPrimary = o.onPrimary?.let(::Color) ?: onPrimary, primaryContainer = Color(o.pc),
    onPrimaryContainer = Color(o.opc), secondary = Color(o.secondary), secondaryContainer = Color(o.sc), tertiary = Color(o.tertiary),
    tertiaryContainer = Color(o.tc), background = Color(o.bg), surface = Color(o.bg), surfaceContainer = Color(o.cont),
    surfaceContainerHigh = Color(o.high), surfaceContainerHighest = Color(o.highest), surfaceContainerLow = Color(o.low),
    surfaceContainerLowest = o.lowest?.let(::Color) ?: surfaceContainerLowest,
)

private val paletteOverrides: Map<String, Pair<Over, Over>> = mapOf(
    "forest" to (Over(0xff386a20, 0xffffffff, 0xffb9f397, 0xff062100, 0xff52634b, 0xffd5e8c9, 0xff386663, 0xffbcece7, 0xfff8fbf1, 0xffecf0e4, 0xffe6ebdd, 0xffe0e5d7, 0xfff2f6ea, 0xffffffff) to
        Over(0xff9ed67d, 0xff0c3900, 0xff205107, 0xffb9f397, 0xffb9ccaf, 0xff3b4b34, 0xffa0d0cc, 0xff1f4e4b, 0xff11150f, 0xff1b2018, 0xff252a21, 0xff30352b, 0xff171b14, 0xff0b0f09)),
    "violet" to (Over(0xff7650a6, null, 0xffecdcff, 0xff290056, 0xff665a6f, 0xffeeddf7, 0xff805158, 0xffffd9de, 0xfffff7fd, 0xfff3eaf2, 0xffede4ec, 0xffe8dfe6, 0xfff9f0f8, null) to
        Over(0xffd7baff, 0xff461c74, 0xff5d378c, 0xffecdcff, 0xffd2c1da, 0xff4d4356, 0xfff3b7c0, 0xff663a41, 0xff171219, 0xff241e27, 0xff2e2831, 0xff39323c, 0xff1f1a22, 0xff110d13)),
    "sunset" to (Over(0xff9c4100, 0xffffffff, 0xffffdbc8, 0xff331100, 0xff77574c, 0xffffdbce, 0xff67613a, 0xfff1e6b0, 0xfffff8f5, 0xfffcebe1, 0xfff7e5db, 0xfff1dfd6, 0xfffff1ea, 0xffffffff) to
        Over(0xffffb68c, 0xff552000, 0xff793000, 0xffffdbc8, 0xffe7bdaf, 0xff5d3f35, 0xffd3c88f, 0xff4e4a24, 0xff1c130e, 0xff291f19, 0xff342923, 0xff3f342d, 0xff241b15, 0xff150e09)),
    "ocean" to (Over(0xff00629e, 0xffffffff, 0xffcee5ff, 0xff001d34, 0xff51606f, 0xffd5e4f6, 0xff00696e, 0xff9ff1f7, 0xfff7faff, 0xffebeff4, 0xffe5e9ef, 0xffdfe3e9, 0xfff1f4fa, 0xffffffff) to
        Over(0xff9bcbff, 0xff00344f, 0xff004a76, 0xffcee5ff, 0xffb9c8d8, 0xff3a4857, 0xff83d4da, 0xff004f53, 0xff0f1417, 0xff1b2023, 0xff262b2e, 0xff303539, 0xff171c1f, 0xff0a0e10)),
    "rose" to (Over(0xffaf3f5f, 0xffffffff, 0xffffd9e1, 0xff3e001c, 0xff74565a, 0xffffd9dc, 0xff7b572e, 0xffffddb8, 0xfffff8f7, 0xfffceae9, 0xfff7e4e3, 0xfff1dedd, 0xfffff1ef, 0xffffffff) to
        Over(0xffffb1c4, 0xff650828, 0xff8b2a45, 0xffffd9e1, 0xffe3bdc1, 0xff5b3f42, 0xffeebe8f, 0xff614017, 0xff1c1112, 0xff291d1e, 0xff342828, 0xff3f3233, 0xff25191a, 0xff160c0c)),
)

// ───────── HSL, как в APK (s3.b / s3.c) ─────────

fun Color.toHsl(): FloatArray {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    if (max == min) return floatArrayOf(0f, 0f, l)
    val d = max - min
    val s = d / (if (l > 0.5f) 2f - max - min else max + min)
    val h = when (max) {
        r -> (g - b) / d + (if (g < b) 6f else 0f)
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    }
    return floatArrayOf(h * 60f, s, l)
}

fun hsl(hue: Float, sat: Float, light: Float): Color {
    val h = ((hue % 360f) + 360f) % 360f
    if (sat <= 0f) return Color(light, light, light)
    val q = if (light < 0.5f) light * (1 + sat) else light + sat - light * sat
    val p = 2 * light - q
    fun ch(t0: Float): Float {
        var t = t0
        if (t < 0) t += 1f
        if (t > 1) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 0.5f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }
    val hn = h / 360f
    return Color(ch(hn + 1f / 3f), ch(hn), ch(hn - 1f / 3f))
}

/** Нейтральные тона текста и контуров с оттенком палитры (s3.e). */
private fun ColorScheme.tinted(seed: Color, dark: Boolean): ColorScheme {
    val h = seed.toHsl()[0]
    return if (dark) copy(onSurface = hsl(h, .06f, .9f), onSurfaceVariant = hsl(h, .05f, .78f), outline = hsl(h, .04f, .58f), outlineVariant = hsl(h, .04f, .28f))
    else copy(onSurface = hsl(h, .08f, .13f), onSurfaceVariant = hsl(h, .06f, .28f), outline = hsl(h, .04f, .45f), outlineVariant = hsl(h, .04f, .8f))
}

/** Своя палитра из одного цвета — тот же алгоритм, что в APK. */
fun customScheme(seed: Color, dark: Boolean): ColorScheme {
    val hs = seed.toHsl()
    val h = hs[0]
    val s = hs[1].coerceIn(0.35f, 0.85f)
    return if (dark) TyrnDark.copy(
        primary = hsl(h, s, .8f), onPrimary = hsl(h, s, .16f), primaryContainer = hsl(h, s, .3f), onPrimaryContainer = hsl(h, s, .9f),
        secondary = hsl(h + 30, s * .5f, .8f), secondaryContainer = hsl(h + 30, s * .4f, .28f),
        tertiary = hsl(h - 60, s * .6f, .78f), tertiaryContainer = hsl(h - 60, s * .4f, .28f),
        background = hsl(h, .12f, .08f), surface = hsl(h, .12f, .08f), onSurface = hsl(h, .06f, .9f), onSurfaceVariant = hsl(h, .05f, .78f),
        outline = hsl(h, .04f, .58f), outlineVariant = hsl(h, .04f, .28f),
        surfaceContainer = hsl(h, .12f, .13f), surfaceContainerHigh = hsl(h, .12f, .18f), surfaceContainerHighest = hsl(h, .12f, .22f),
        surfaceContainerLow = hsl(h, .12f, .11f), surfaceContainerLowest = hsl(h, .12f, .05f),
    ) else TyrnLight.copy(
        primary = hsl(h, s, .38f), onPrimary = Color.White, primaryContainer = hsl(h, s, .88f), onPrimaryContainer = hsl(h, s, .14f),
        secondary = hsl(h + 30, s * .4f, .38f), secondaryContainer = hsl(h + 30, s * .3f, .9f),
        tertiary = hsl(h - 60, s * .5f, .36f), tertiaryContainer = hsl(h - 60, s * .35f, .88f),
        background = hsl(h, .15f, .98f), surface = hsl(h, .15f, .98f), onSurface = hsl(h, .08f, .13f), onSurfaceVariant = hsl(h, .06f, .28f),
        outline = hsl(h, .04f, .45f), outlineVariant = hsl(h, .04f, .8f),
        surfaceContainer = hsl(h, .15f, .93f), surfaceContainerHigh = hsl(h, .15f, .91f), surfaceContainerHighest = hsl(h, .15f, .89f),
        surfaceContainerLow = hsl(h, .15f, .95f), surfaceContainerLowest = Color.White,
    )
}

internal fun ColorScheme.amoled() = copy(
    background = Color.Black, onBackground = Color(0xFFE7E7E7), surface = Color.Black, onSurface = Color(0xFFE7E7E7),
    surfaceVariant = Color(0xFF121212), onSurfaceVariant = Color(0xFFC9C9C9), surfaceContainer = Color(0xFF090909),
    surfaceContainerHigh = Color(0xFF101010), surfaceContainerHighest = Color(0xFF171717), surfaceContainerLow = Color(0xFF050505),
    surfaceContainerLowest = Color.Black,
)

fun colorSchemeFor(palette: String, seed: Color, dark: Boolean, amoled: Boolean, accent: Color?): ColorScheme {
    val base = when {
        accent != null -> customScheme(accent, dark)
        palette == "custom" -> customScheme(seed, dark)
        else -> paletteOverrides[palette]?.let { (l, d) ->
            val p = Palettes.first { it.id == palette }.seed
            if (dark) TyrnDark.with(d).tinted(p, true) else TyrnLight.with(l).tinted(p, false)
        } ?: if (dark) TyrnDark else TyrnLight
    }
    return if (dark && amoled) base.amoled() else base
}

// ───────── системная тема и акцент через xdg-desktop-portal ─────────

/** Светлая/тёмная тема и акцентный цвет рабочего стола — замена «цветов из обоев» Android. */
object SystemAppearance {
    var dark by mutableStateOf(false); private set
    var accent by mutableStateOf<Color?>(null); private set

    private suspend fun portal(key: String): String? = withContext(Dispatchers.IO) {
        val r = com.trueguard.core.run(
            "gdbus", "call", "--session", "--dest", "org.freedesktop.portal.Desktop",
            "--object-path", "/org/freedesktop/portal/desktop",
            "--method", "org.freedesktop.portal.Settings.ReadOne", "org.freedesktop.appearance", key, timeoutSec = 3,
        )
        r.out.takeIf { r.code == 0 }
    }

    suspend fun refresh() {
        if (com.trueguard.core.Os.isWindows) return refreshWindows()
        // color-scheme: 0 — без предпочтения, 1 — тёмная, 2 — светлая
        val scheme = portal("color-scheme")?.let { Regex("uint32 (\\d)").find(it)?.groupValues?.get(1)?.toInt() }
        dark = when (scheme) {
            1 -> true
            2 -> false
            else -> gnomeDark() ?: false
        }
        accent = portal("accent-color")?.let { out ->
            val v = Regex("[-\\d.]+").findAll(out.substringAfter("<(")).map { it.value.toFloatOrNull() }.toList()
            if (v.size >= 3 && v.take(3).all { it != null && it in 0f..1f }) Color(v[0]!!, v[1]!!, v[2]!!) else null
        } ?: kdeAccent()
    }

    /** Windows: светлая/тёмная тема приложений и акцентный цвет из реестра. */
    private suspend fun refreshWindows() {
        val wt = com.trueguard.core.WindowsTools
        val light = wt.regValue("HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "AppsUseLightTheme")
        dark = light?.let { it.removePrefix("0x").toLongOrNull(16) == 0L } ?: false
        // AccentColor хранится как 0xAABBGGRR
        accent = wt.regValue("HKCU\\Software\\Microsoft\\Windows\\DWM", "AccentColor")?.removePrefix("0x")?.toLongOrNull(16)?.let { v ->
            Color(red = (v and 0xFF).toInt(), green = ((v shr 8) and 0xFF).toInt(), blue = ((v shr 16) and 0xFF).toInt())
        }
    }

    private suspend fun gnomeDark(): Boolean? {
        val r = com.trueguard.core.run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme", timeoutSec = 3)
        if (r.code != 0) return null
        return r.out.contains("dark")
    }

    private fun kdeAccent(): Color? = runCatching {
        val f = java.io.File("${com.trueguard.core.Paths.home}/.config/kdeglobals")
        val m = Regex("^AccentColor=(\\d+),(\\d+),(\\d+)", RegexOption.MULTILINE).find(f.readText()) ?: return null
        Color(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }.getOrNull()

    /** Следим за сменой темы рабочего стола. */
    suspend fun watch() {
        while (true) {
            runCatching { refresh() }
            delay(15_000)
        }
    }
}

@Composable
fun TrueGuardTheme(content: @Composable () -> Unit) {
    val s = Settings.instance
    val dark = when (s.themeMode) {
        "light" -> false
        "dark", "amoled" -> true
        else -> if (Platform.isAndroid) androidx.compose.foundation.isSystemInDarkTheme() else SystemAppearance.dark
    }
    val amoled = s.themeMode == "amoled"
    // «Динамические цвета»: обои Android 12+ или акцентный цвет рабочего стола
    val dynamic = if (s.dynamicColor) Platform.hooks.dynamicColorScheme(dark) else null
    val scheme = dynamic?.let { if (dark && amoled) it.amoled() else it }
        ?: colorSchemeFor(s.themePalette, Color(s.themeSeedColor), dark, amoled, accent = null)
    val typography = remember { appTypography(Platform.hooks.fontFamily) }
    MaterialExpressiveTheme(
        colorScheme = scheme.animated(),
        motionScheme = MotionScheme.expressive(),
        typography = typography,
        content = content,
    )
}

/** Плавная смена палитры: цвета перетекают, а не переключаются скачком. */
@Composable
private fun ColorScheme.animated(): ColorScheme {
    @Composable
    fun Color.a(): Color = androidx.compose.animation.animateColorAsState(this, androidx.compose.animation.core.tween(450)).value
    return copy(
        primary = primary.a(), onPrimary = onPrimary.a(), primaryContainer = primaryContainer.a(), onPrimaryContainer = onPrimaryContainer.a(),
        secondary = secondary.a(), secondaryContainer = secondaryContainer.a(), onSecondaryContainer = onSecondaryContainer.a(),
        tertiary = tertiary.a(), tertiaryContainer = tertiaryContainer.a(), background = background.a(), surface = surface.a(),
        onSurface = onSurface.a(), onSurfaceVariant = onSurfaceVariant.a(), outline = outline.a(), outlineVariant = outlineVariant.a(),
        surfaceContainer = surfaceContainer.a(), surfaceContainerHigh = surfaceContainerHigh.a(),
        surfaceContainerHighest = surfaceContainerHighest.a(), surfaceContainerLow = surfaceContainerLow.a(),
        surfaceContainerLowest = surfaceContainerLowest.a(),
    )
}

/** Сравнение цветов без учёта погрешности float (для тестов палитр). */
fun Color.near(other: Color, eps: Float = 1.5f / 255f) =
    abs(red - other.red) < eps && abs(green - other.green) < eps && abs(blue - other.blue) < eps
