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
 * Acknowledges that this kernel state is **runtime-local** and may split between runtimes
 * (compatibility.md §6.6).
 *
 * Every non-`@RDMA` variable, `object` and `lazy` value of a kernel is runtime-local: each runtime
 * that runs code touching it has its own copy, with no synchronization. When a dynamic release
 * moves some of that code into a polyfill while other code touching the same state stays native,
 * the two copies diverge, and the release fails until the state carries this annotation. It
 * changes no semantics; it records that the developer accepts the split (a cache, a counter for
 * logs). To share state between runtimes, keep it behind `@RDMA` functions (`getX`/`setX`)
 * instead.
 *
 * On an `object` it covers all its properties.
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class RuntimeLocal
