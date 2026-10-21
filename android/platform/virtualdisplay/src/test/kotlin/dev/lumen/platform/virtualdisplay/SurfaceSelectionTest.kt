package dev.lumen.platform.virtualdisplay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SurfaceSelectionTest {

    private val allowlist = setOf("com.example.browser", "com.example.travel")

    private fun request(
        packageName: String = "com.example.browser",
        allowlist: Set<String> = this.allowlist,
        deviceIsIdle: Boolean = true,
        idleWaitMs: Long = 30_000L,
        virtualDisplayAvailable: Boolean = false,
        targetSupportsSecondaryDisplay: Boolean = false,
        foregroundPackage: String? = "com.example.browser",
    ) = SurfaceRequest(
        packageName = packageName,
        allowlist = allowlist,
        deviceIsIdle = deviceIsIdle,
        idleWaitMs = idleWaitMs,
        virtualDisplayAvailable = virtualDisplayAvailable,
        targetSupportsSecondaryDisplay = targetSupportsSecondaryDisplay,
        foregroundPackage = foregroundPackage,
    )

    @Test
    fun `a package that is not allowed is refused`() {
        val decision = SurfaceSelection.choose(request(packageName = "com.example.other"))

        assertThat(decision).isInstanceOf(SurfaceDecision.Deny::class.java)
    }

    @Test
    fun `a foreground package that is not allowed is refused`() {
        val decision = SurfaceSelection.choose(request(foregroundPackage = "com.other.app"))

        assertThat(decision).isInstanceOf(SurfaceDecision.Deny::class.java)
    }

    @Test
    fun `a private display is preferred when the app supports one`() {
        val decision = SurfaceSelection.choose(
            request(virtualDisplayAvailable = true, targetSupportsSecondaryDisplay = true),
            virtualDisplayId = 7,
        )

        assertThat(decision).isInstanceOf(SurfaceDecision.UseVirtualDisplay::class.java)
        assertThat((decision as SurfaceDecision.UseVirtualDisplay).displayId).isEqualTo(7)
    }

    @Test
    fun `a private display is not used when the app cannot live on one`() {
        val decision = SurfaceSelection.choose(
            request(virtualDisplayAvailable = true, targetSupportsSecondaryDisplay = false),
            virtualDisplayId = 7,
        )

        assertThat(decision).isInstanceOf(SurfaceDecision.UseLiveSurface::class.java)
    }

    @Test
    fun `the live surface is held while the device is in use`() {
        val decision = SurfaceSelection.choose(request(deviceIsIdle = false, idleWaitMs = 12_000L))

        assertThat(decision).isInstanceOf(SurfaceDecision.Hold::class.java)
        assertThat((decision as SurfaceDecision.Hold).waitMs).isEqualTo(12_000L)
    }

    @Test
    fun `the live surface is used when the device is idle`() {
        val decision = SurfaceSelection.choose(request(deviceIsIdle = true))

        assertThat(decision).isInstanceOf(SurfaceDecision.UseLiveSurface::class.java)
    }

    @Test
    fun `the allowlist is checked before the idle gate`() {
        val decision = SurfaceSelection.choose(
            request(packageName = "com.example.other", deviceIsIdle = false),
        )

        // The app is not allowed, so the step is refused rather than waited on.
        assertThat(decision).isInstanceOf(SurfaceDecision.Deny::class.java)
    }
}
