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

// Registry of plugin JS functions ("blocks") referenced from the kernel by id
// (docs/design/runtime_rework.md §4). Shared by both platform backends.
//
// Lifetime: a block registered by the plugin (`RDMA.registerBlock`) is owned by its
// kernel HOLDERS — the kernel objects wrapping its id (`RdmaBlockRef`: service
// lambdas, widget lambdas, scope blocks). A holder retains on construction and
// releases deterministically (Compose `RememberObserver`) or on finalization.
// A block no holder retained by the end of the JS task that registered it is freed
// (nobody can retain it later). Runtime-internal blocks (effect bodies) are
// "explicit": they ignore holders and are erased by their owner.
//
// Threading: retain/release may be called from any thread (holders live on the UI
// thread and finalizer threads); the `jsi::Function`s are created, looked up and
// destroyed ONLY on the Hermes thread — a release that drops the last holder posts
// the destruction to the low-priority JS queue, i.e. after every invocation of that
// block posted before it (FIFO).

#include <jsi/jsi.h>

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace facebook {
namespace rdma {

// Registers a plugin block (JS thread). The same function object registered again
// returns its existing id (so a Compose-memoized lambda keeps a stable id).
// The block starts without holders and is swept at the end of the current JS task
// unless a holder retains it.
int64_t rdmaBlockRegisterPlugin(jsi::Runtime& rt, const jsi::Function& fn, const std::string& pluginId);

// Registers a runtime-internal block (JS thread) that is erased explicitly by its owner.
int64_t rdmaBlockRegisterExplicit(std::shared_ptr<jsi::Function> fn, const std::string& pluginId = "");

// The block function (JS thread), or null when unknown/freed.
std::shared_ptr<jsi::Function> rdmaBlockGet(int64_t id);

// The plugin that registered the block ("" when unknown).
std::string rdmaBlockPluginId(int64_t id);

// Erases a block now (JS thread), regardless of holders.
void rdmaBlockErase(int64_t id);

// Holder bookkeeping (any thread).
void rdmaBlockRetain(int64_t id);
void rdmaBlockRelease(int64_t id);

// Parameter type descriptors of a callback block (docs/design/type_descriptors.md):
// invocations marshal their arguments by them instead of by runtime shape.
void rdmaBlockSetParamDescs(int64_t id, std::vector<std::string> descs);
// Comma-separated form (descriptors never contain commas), "" = untyped.
void rdmaBlockSetParamDescs(int64_t id, const std::string& joined);
std::vector<std::string> rdmaBlockParamDescs(int64_t id);

// Result type descriptor of a lambda block. A block with a result (anything but Unit) is
// invoked synchronously — the native caller waits for the value (rdmaCallJsBlocking) —
// while a block without one is fire-and-forget. "" or "kotlin.Unit" = no result.
void rdmaBlockSetResultDesc(int64_t id, const std::string& desc);
std::string rdmaBlockResultDesc(int64_t id);

// End of a top-level JS task (JS thread): frees the blocks registered during the task
// that no holder retained.
void rdmaBlockSweepUnretained();

// Diagnostics: number of live blocks.
size_t rdmaBlockCount();

} // namespace rdma
} // namespace facebook
