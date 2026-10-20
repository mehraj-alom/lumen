package dev.lumen.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class ActionSerializationTest {

    private val json = Json { encodeDefaults = true }

    private val actions: List<Action> = listOf(
        Action.Tap(RelativePoint(0.5), RelativePoint(0.25)),
        Action.DoubleTap(RelativePoint(0.1), RelativePoint(0.2)),
        Action.LongPress(RelativePoint(0.3), RelativePoint(0.4), 800L),
        Action.Swipe(RelativePoint(0.0), RelativePoint(0.5), RelativePoint(1.0), RelativePoint(0.5), 250L),
        Action.Scroll(ScrollDirection.DOWN, 0.75, RelativePoint(0.5)),
        Action.Drag(listOf(RelativePoint(0.1), RelativePoint(0.5), RelativePoint(0.9)), 400L, Easing.EASE_IN_OUT),
        Action.InputText("hello"),
        Action.PasteText("world"),
        Action.PressEnter,
        Action.ClickById("com.example:id/button"),
        Action.ClickText("Pay", 2),
        Action.OpenApp("com.example.app"),
        Action.OpenUrl("https://example.com"),
        Action.ListApps,
        Action.ObserveScreen(includeScreenshot = true, includeOverlays = false, maxNodes = 120),
        Action.Screenshot(RelativeRect(0.1, 0.1, 0.9, 0.9)),
        Action.ReadFile("workspace/notes.md"),
    )

    @Test
    fun `every action survives a serialisation round trip`() {
        actions.forEach { action ->
            val encoded = json.encodeToString(action)
            val decoded = json.decodeFromString<Action>(encoded)

            assertThat(decoded).isEqualTo(action)
        }
    }

    @Test
    fun `the wire form of an action is the declared discriminator`() {
        assertThat(json.encodeToString<Action>(Action.PressEnter))
            .contains("\"press_enter\"")
        assertThat(json.encodeToString<Action>(Action.ListApps))
            .contains("\"list_apps\"")
    }

    @Test
    fun `a mutating action is distinguished from a read`() {
        val mutating = actions.filter { it.mutating }
        val reading = actions.filterNot { it.mutating }

        assertThat(mutating.map { it::class.simpleName })
            .containsNoneOf("ListApps", "ObserveScreen", "Screenshot", "ReadFile")
        assertThat(reading.map { it::class.simpleName })
            .containsExactly("ListApps", "ObserveScreen", "Screenshot", "ReadFile")
    }

    @Test
    fun `a relative point outside the unit range is rejected`() {
        assertThat(runCatching { RelativePoint(1.2) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { RelativePoint(-0.1) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a relative rect with a negative extent is rejected`() {
        assertThat(runCatching { RelativeRect(0.8, 0.0, 0.2, 1.0) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { RelativeRect(0.0, 0.8, 1.0, 0.2) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the full rect covers the root window`() {
        assertThat(RelativeRect.FULL.width).isEqualTo(1.0)
        assertThat(RelativeRect.FULL.height).isEqualTo(1.0)
        assertThat(RelativeRect.FULL.center).isEqualTo(RelativePoint(0.5))
    }

    @Test
    fun `a drag needs at least two points`() {
        assertThat(runCatching { Action.Drag(listOf(RelativePoint(0.1))) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `an empty text entry is rejected`() {
        assertThat(runCatching { Action.InputText("") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
