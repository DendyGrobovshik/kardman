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
package io.github.dendygrobovshik.kardman.plugin

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RdmaComposeRewriteTest {

    @Test
    fun `maps mutableIntStateOf to rdmaMutableIntStateOf`() {
        assertEquals(
            "rdmaMutableIntStateOf",
            RdmaPluginTransformState.bridgeNameFor("androidx.compose.runtime.mutableIntStateOf"),
        )
        assertTrue(RdmaPluginTransformState.isFunctionQualifiedName("androidx.compose.runtime.mutableIntStateOf"))
    }

    @Test
    fun `maps SideEffect to rdmaSideEffect`() {
        assertEquals(
            "rdmaSideEffect",
            RdmaPluginTransformState.bridgeNameFor("androidx.compose.runtime.SideEffect"),
        )
        assertTrue(RdmaPluginTransformState.isFunctionQualifiedName("androidx.compose.runtime.SideEffect"))
    }

    @Test
    fun `maps DisposableEffect to rdmaDisposableEffect`() {
        assertEquals(
            "rdmaDisposableEffect",
            RdmaPluginTransformState.bridgeNameFor("androidx.compose.runtime.DisposableEffect"),
        )
        assertTrue(RdmaPluginTransformState.isFunctionQualifiedName("androidx.compose.runtime.DisposableEffect"))
    }

    @Test
    fun `maps LaunchedEffect to rdmaLaunchedEffect`() {
        assertEquals(
            "rdmaLaunchedEffect",
            RdmaPluginTransformState.bridgeNameFor("androidx.compose.runtime.LaunchedEffect"),
        )
        assertTrue(RdmaPluginTransformState.isFunctionQualifiedName("androidx.compose.runtime.LaunchedEffect"))
    }

    @Test
    fun `maps rememberCoroutineScope to rdmaRememberCoroutineScope`() {
        assertEquals(
            "rdmaRememberCoroutineScope",
            RdmaPluginTransformState.bridgeNameFor("androidx.compose.runtime.rememberCoroutineScope"),
        )
        assertTrue(RdmaPluginTransformState.isFunctionQualifiedName("androidx.compose.runtime.rememberCoroutineScope"))
    }

    @Test
    fun `structural symbols are allowed but not bridged`() {
        assertTrue(ComposeAllowlist.isAllowed("androidx.compose.runtime.key"))
        assertTrue(ComposeAllowlist.isAllowed("androidx.compose.runtime.rememberUpdatedState"))
        assertTrue(ComposeAllowlist.isAllowed("androidx.compose.runtime.Stable"))
        assertTrue(ComposeAllowlist.isAllowed("androidx.compose.runtime.Immutable"))
    }
}
