package io.github.dendygrobovshik.kardman.plugin

/**
 * Whitelist of Compose symbols the plugin is allowed to use.
 *
 * The host kernel exposes only the base `Composer` protocol (see
 * `RdmaComposerProtocol` in `rdma-kernel-compiler-plugin`). Everything else from
 * `androidx.compose.*` must be rejected at compile time with a clear error, because
 * the kernel cannot faithfully execute it (effects, derived state, movable content,
 * snapshot flows, animations, coroutine scopes, ...).
 */
object ComposeAllowlist {

    private val allowedRuntime = setOf(
        "androidx.compose.runtime.Composable",
        "androidx.compose.runtime.remember",
        "androidx.compose.runtime.mutableStateOf",
        "androidx.compose.runtime.getValue",
        "androidx.compose.runtime.setValue",
        "androidx.compose.runtime.Composer",
        "androidx.compose.runtime.State",
        "androidx.compose.runtime.MutableState",
    )

    fun isAllowed(fqn: String): Boolean = when {
        fqn in allowedRuntime -> true
        !fqn.startsWith("androidx.compose") -> true
        else -> false
    }

    fun reason(fqn: String): String {
        val short = fqn.substringAfterLast('.')
        return "kernel doesn't support '$short' — the plugin is limited to the base Compose protocol " +
            "(remember/mutableStateOf/widgets)"
    }
}
