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
#include <string>

namespace facebook {
namespace rdma {

// Recomposition tracer (docs/guide/threads_and_performance.md#recomposition-tracer): a
// debugging aid that tells how often each plugin composable runs and why. Off by default;
// while off nothing on the composition paths does any work for it.
//
// Two sources meet here, in one order (the rendezvous is synchronous):
//  - the plugin side: Compose's own trace hooks (`traceEventStart`/`traceEventEnd`, emitted
//    by the Compose compiler into every plugin composable), installed by the plugin bridge
//    through `RDMA.installTracer` and forwarded to `RDMA.traceStart`/`RDMA.traceEnd`;
//  - the kernel side: a Compose `CompositionObserver` on every composition, which reports
//    the scopes entered, invalidated (and by what), read and disposed.

// Scope events from the kernel-side observer.
enum RdmaTraceScopeEvent : int32_t {
    kRdmaTraceEnter = 0,
    kRdmaTraceExit = 1,
    kRdmaTraceInvalidated = 2, // `value` invalidated `scope`; `desc` describes it
    kRdmaTraceRead = 3,        // `scope` read `value`
    kRdmaTraceDisposed = 4,
};

// Turns tracing on (clearing the collected data) or off, and installs or removes the
// plugin-side tracer on the Hermes thread. Any thread.
void rdmaTraceSetEnabled(bool enabled);
bool rdmaTraceEnabled();

// A kernel-side scope event. `scope` and `value` are identity ids (0 = none). Any thread.
void rdmaTraceScopeEvent(int32_t kind, int64_t scope, int64_t value, const char* desc);

// A human-readable report of what was collected since tracing was turned on.
std::string rdmaTraceReport();

// Adds `traceStart`/`traceEnd` to the RDMA namespace (Hermes thread, at install).
void rdmaTraceInstallJs(jsi::Runtime& rt, jsi::Object& rdma);

} // namespace rdma
} // namespace facebook
