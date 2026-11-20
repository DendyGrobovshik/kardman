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
@file:OptIn(ExperimentalNativeApi::class, InternalComposeApi::class)

package io.github.dendygrobovshik.kardman.runtime

import androidx.compose.runtime.Composer
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.RecomposeScope
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference

/**
 * Recompose scopes tracked by plugin composable lambdas (field report #50); the Android
 * counterpart explains the protocol. Each scope is handed out once and again only after it
 * was invalidated; the set holds the scopes weakly (by identity), so a scope whose group
 * went away is dropped. Both calls run on the UI thread (`rdmaCallUi`).
 */
object RdmaLambdaScopes {
    private val handedOut = HashMap<Int, MutableList<WeakReference<RecomposeScope>>>()
    private var purgeAt = 64

    fun track(composer: Composer): RecomposeScope? {
        val scope = composer.recomposeScope ?: return null
        composer.recordUsed(scope)
        val bucket = handedOut.getOrPut(scope.hashCode()) { mutableListOf() }
        if (bucket.any { it.value === scope }) return null
        bucket += WeakReference(scope)
        if (handedOut.size >= purgeAt) purge()
        return scope
    }

    fun invalidate(scope: RecomposeScope) {
        val key = scope.hashCode()
        handedOut[key]?.let { bucket ->
            bucket.removeAll { it.value === scope || it.value == null }
            if (bucket.isEmpty()) handedOut.remove(key)
        }
        scope.invalidate()
    }

    private fun purge() {
        val it = handedOut.entries.iterator()
        while (it.hasNext()) {
            val bucket = it.next().value
            bucket.removeAll { ref -> ref.value == null }
            if (bucket.isEmpty()) it.remove()
        }
        purgeAt = maxOf(64, handedOut.size * 2)
    }
}
