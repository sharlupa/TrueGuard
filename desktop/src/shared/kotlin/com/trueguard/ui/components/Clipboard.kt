package com.trueguard.ui.components

import com.trueguard.core.Platform

/** Буфер обмена платформы: AWT на компьютере, ClipboardManager на Android. */
object Clipboard {
    fun copy(text: String) = Platform.hooks.copyText(text)
    fun paste(): String? = Platform.hooks.pasteText()
}
