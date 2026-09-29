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
package io.github.dendygrobovshik.kardman

/**
 * Marks a function as a **manual polyfill** for a kernel symbol that the auto-materializer cannot
 * compile to JS but a human can reimplement through `@RDMA` primitives (§6.3).
 *
 * Naming convention: the `_polyfill` suffix. The declaration is **not** part of the public
 * `@RDMA` surface — it is excluded from the manifest, so plugins never see it. The materializer
 * picks it up only when the bound symbol needs a JS version.
 *
 * ```kotlin
 * @Polyfill(for = "com.example.kernel.foo")
 * fun foo_polyfill(...): ... = { /* emulatable implementation via @RDMA primitives */ }
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Polyfill(val `for`: String)
