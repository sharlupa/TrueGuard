package com.trueguard

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.trueguard.ui.components.mouseHorizontalScroll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ряд палитр: обычное колесо и перетаскивание мышью листают его без Shift. */
@OptIn(ExperimentalTestApi::class)
class MouseScrollTest {
    private fun ComposeUiTest.row(): ScrollState {
        lateinit var state: ScrollState
        setContent {
            state = rememberScrollState()
            Row(Modifier.width(300.dp).testTag("row").mouseHorizontalScroll(state)) {
                repeat(10) { Box(Modifier.size(100.dp).background(if (it % 2 == 0) Color.Red else Color.Blue)) }
            }
        }
        return state
    }

    @Test
    fun wheelScrollsWithoutShift() = runComposeUiTest {
        val state = row()
        onNodeWithTag("row").performMouseInput { scroll(3f) }
        waitForIdle()
        assertTrue(state.value > 0, "колесо вниз должно листать ряд вправо")
        val after = state.value
        onNodeWithTag("row").performMouseInput { scroll(-1f) }
        waitForIdle()
        assertTrue(state.value < after, "колесо вверх — обратно")
    }

    @Test
    fun mouseDragScrolls() = runComposeUiTest {
        val state = row()
        onNodeWithTag("row").performMouseInput {
            press()
            moveBy(Offset(-150f, 0f))
            moveBy(Offset(-150f, 0f))
            release()
        }
        waitForIdle()
        assertTrue(state.value >= 250, "перетаскивание влево листает вправо: ${state.value}")
    }

    @Test
    fun wheelAtEdgeIsLeftForPage() = runComposeUiTest {
        val state = row()
        // в начале ряда колесо вверх ничего не делает с рядом (прокрутка уходит странице)
        onNodeWithTag("row").performMouseInput { scroll(-2f) }
        waitForIdle()
        assertEquals(0, state.value)
    }
}
