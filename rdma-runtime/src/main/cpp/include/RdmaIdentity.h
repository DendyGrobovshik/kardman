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

#include <jsi/jsi.h>

#include <cstdint>

namespace facebook {
namespace rdma {

// Identity of plugin JS objects for the Compose slot table (docs/internals/compose_protocol.md
// #object-keys). A key passed to `Composer.changed` or an effect is stored natively; a JS
// object has no native counterpart, so it crosses as its identity id: the same object
// always gets the same id, ids are never reused. The table is a JS `WeakMap`, so an entry
// lives exactly as long as its object. Hermes thread only.

// Creates the table for `rt`; called when the RDMA namespace is installed.
void rdmaIdentityInstall(jsi::Runtime& rt);

// The identity id of `obj` (assigned on first use).
int64_t rdmaJsIdentity(jsi::Runtime& rt, const jsi::Object& obj);

} // namespace rdma
} // namespace facebook
