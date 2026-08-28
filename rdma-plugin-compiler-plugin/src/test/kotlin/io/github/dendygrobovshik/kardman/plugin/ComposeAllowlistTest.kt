package io.github.dendygrobovshik.kardman.plugin

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposeAllowlistTest {

    @Test
    fun `allows base protocol symbols`() {
        val allowed = listOf(
            "androidx.compose.runtime.Composable",
            "androidx.compose.runtime.remember",
            "androidx.compose.runtime.mutableStateOf",
            "androidx.compose.runtime.getValue",
            "androidx.compose.runtime.setValue",
            "androidx.compose.runtime.Composer",
            "androidx.compose.runtime.State",
            "androidx.compose.runtime.MutableState",
        )
        for (fqn in allowed) {
            assertTrue(ComposeAllowlist.isAllowed(fqn), "$fqn should be allowed")
        }
    }

    @Test
    fun `allows non-compose symbols`() {
        val allowed = listOf(
            "com.example.kernel.Text",
            "com.example.kernel.runRdmaApp",
            "kotlin.collections.List",
        )
        for (fqn in allowed) {
            assertTrue(ComposeAllowlist.isAllowed(fqn), "$fqn should be allowed")
        }
    }

    @Test
    fun `rejects unsupported compose symbols`() {
        val rejected = listOf(
            "androidx.compose.runtime.LaunchedEffect",
            "androidx.compose.runtime.DisposableEffect",
            "androidx.compose.runtime.SideEffect",
            "androidx.compose.runtime.derivedStateOf",
            "androidx.compose.runtime.rememberCoroutineScope",
            "androidx.compose.runtime.movableContentOf",
            "androidx.compose.runtime.produceState",
            "androidx.compose.runtime.snapshots.snapshotFlow",
            "androidx.compose.foundation.layout.Column",
            "androidx.compose.material3.Text",
            "androidx.compose.ui.Modifier",
        )
        for (fqn in rejected) {
            assertFalse(ComposeAllowlist.isAllowed(fqn), "$fqn should be rejected")
        }
    }

    @Test
    fun `reason names the offending symbol`() {
        val reason = ComposeAllowlist.reason("androidx.compose.runtime.LaunchedEffect")
        assertTrue(reason.contains("LaunchedEffect"), reason)
    }
}
