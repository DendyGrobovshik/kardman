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
#pragma once
#include <stdint.h>

// Function-pointer registry. Kotlin/Native registers its C-compatible shim
// functions here at startup (`@EagerInitialization` + `staticCFunction`); C++
// looks them up by name and calls them by address. This replaces the previous
// `@CName` symbol linkage, which does not export C symbols from a Kotlin/Native
// framework (only ObjC methods).
extern "C" {
void rdma_registerFunction(const char* name, void* fn);
void* rdma_lookupFunction(const char* name);
}
