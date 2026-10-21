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
package org.example.ui

import androidx.compose.runtime.Composable

/** True once the Hermes runtime has bootstrapped. */
expect fun runtimeReady(): Boolean

/** Monotonically increasing content-registration version (drives preview re-composition). */
expect fun contentVersion(): Int

/** Renders the currently registered plugin content (`RdmaComposeHost.Content()`). */
@Composable
expect fun PreviewContent()
