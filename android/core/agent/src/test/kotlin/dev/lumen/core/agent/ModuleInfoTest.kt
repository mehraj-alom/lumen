package dev.lumen.core.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModuleInfoTest {
    @Test
    fun `module path is namespaced`() {
        assertThat(ModuleInfo.PATH).startsWith(":core:")
    }
}
