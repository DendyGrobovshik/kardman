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
#include <string>

#include "RdmaPlatform.h"

// Creates the Hermes runtime and installs the compose bridge (JSI only). Must be
// called on the Hermes thread; platform caches are initialized separately on the
// UI thread before rdmaStart().
void initRdmaRuntime(facebook::rdma::HostContext ctx);

// Evaluates a JS asset/script on the Hermes thread. Fire-and-forget: the result
// is logged; errors are caught and logged. Runs only on the Hermes thread.
void evalJavaScript(const std::string& code);

// Returns the Hermes runtime. Hermes-thread access only.
facebook::jsi::Runtime* getRdmaRuntime();
