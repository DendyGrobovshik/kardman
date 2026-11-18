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
package com.example.kernel.internal.services

import io.github.dendygrobovshik.kardman.RDMA

// A kernel class hierarchy (docs/design/runtime_rework.md §11, "supertypes in the stubs"):
// the plugin passes a `Square` where a `Shape` is expected and uses inherited members.

@RDMA
open class Shape(val name: String) {
    open fun area(): Double = 0.0
}

@RDMA
class Square(val side: Double) : Shape("square") {
    override fun area(): Double = side * side
}

@RDMA
fun describeShape(shape: Shape): String = "${shape.name}:${shape.area()}"
