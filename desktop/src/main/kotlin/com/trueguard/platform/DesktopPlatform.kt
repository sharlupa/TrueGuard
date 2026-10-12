package com.trueguard.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import com.trueguard.core.AppScanner
import com.trueguard.core.DesktopApp
import com.trueguard.core.Os
import com.trueguard.core.PlatformHooks
import com.trueguard.ui.theme.SystemAppearance
import com.trueguard.ui.theme.customScheme
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File

/** Linux и Windows: буфер обмена AWT, встроенный Roboto, программы из .desktop, акцент рабочего стола. */
object DesktopPlatform : PlatformHooks {
    override fun copyText(text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    override fun pasteText(): String? = runCatching {
        val cb = Toolkit.getDefaultToolkit().systemClipboard
        if (cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) cb.getData(DataFlavor.stringFlavor) as String else null
    }.getOrNull()

    override val fontFamily: FontFamily = FontFamily(
        Font("fonts/Roboto-Regular.ttf", FontWeight.Normal),
        Font("fonts/Roboto-Medium.ttf", FontWeight.Medium),
        Font("fonts/Roboto-SemiBold.ttf", FontWeight.SemiBold),
        Font("fonts/Roboto-Bold.ttf", FontWeight.Bold),
        Font("fonts/Roboto-ExtraBold.ttf", FontWeight.ExtraBold),
        Font("fonts/Roboto-Black.ttf", FontWeight.Black),
    )

    @Composable
    override fun appIcon(): Painter = painterResource("icons/app256.png")

    @Composable
    override fun dynamicColorScheme(dark: Boolean): ColorScheme? = SystemAppearance.accent?.let { customScheme(it, dark) }

    override suspend fun installedApps(refresh: Boolean): List<DesktopApp> = if (Os.isWindows) emptyList() else AppScanner.scan(refresh)

    override suspend fun loadAppIcon(app: DesktopApp): ImageBitmap? {
        val p = app.iconPath ?: return null
        return runCatching { org.jetbrains.skia.Image.makeFromEncoded(File(p).readBytes()).toComposeImageBitmap() }.getOrNull()
    }
}
