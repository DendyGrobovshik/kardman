/*
 * Copyright 2026 DendyGrobovshik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.dendygrobovshik.kardman.runtime

import androidx.compose.runtime.Composer
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.RecomposeScope
import java.util.Collections
import java.util.WeakHashMap

/**
 * Recompose scopes tracked by plugin composable lambdas (field report #50). A plugin
 * `ComposableLambdaImpl` reads `composer.recomposeScope` each time it runs and invalidates
 * the scopes it collected when the parent recomposes with new captures — that is how a
 * skipped widget call still re-runs its content with fresh values, as in plain Compose.
 *
 * The JS lambda cannot recognise a proxy as the same scope (it checks for its own
 * `RecomposeScopeImpl`), so it would collect one per run. Each scope is therefore handed out
 * once and again only after it was invalidated; the scope is recorded used on every read, as
 * `currentRecomposeScope` does, so it survives a run that reads no state.
 */
@OptIn(InternalComposeApi::class)
object RdmaLambdaScopes {
    private val handedOut: MutableSet<RecomposeScope> = Collections.newSetFromMap(WeakHashMap())

    @JvmStatic
    fun track(composer: Composer): RecomposeScope? {
        val scope = composer.recomposeScope ?: return null
        composer.recordUsed(scope)
        return synchronized(handedOut) { if (handedOut.add(scope)) scope else null }
    }

    @JvmStatic
    fun invalidate(scope: RecomposeScope) {
        synchronized(handedOut) { handedOut.remove(scope) }
        if (!RdmaDiagnostics.tracing) {
            scope.invalidate()
            return
        }
        RdmaDiagnostics.lambdaInvalidation = true
        try {
            scope.invalidate()
        } finally {
            RdmaDiagnostics.lambdaInvalidation = false
        }
    }
}
