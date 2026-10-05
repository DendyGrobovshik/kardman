<!--
Copyright 2026 DendyGrobovshik

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->
# User Guide

This folder is the user-facing documentation: how to run the framework, what you
can build with it, and how to wire it into your own project. The root
[README.md](../../README.md) is the one-paragraph overview with code snippets;
the pages below are the step-by-step material.

## Where to go next

| You want to… | Read |
|---|---|
| Install prerequisites, run the demo, use the framework in your own project | [setup.md](setup.md) |
| Learn what you can express with `@RDMA` (data, UI, effects, coroutines) | [features.md](features.md) |
| Run releases and operate the store | [releases.md](releases.md) |
| Contribute to the framework itself (build, test, debug) | [contribution.md](contribution.md) |

## How the docs are organized

- `docs/` — architecture and reference: the bridge ([bridges.md](../bridges.md)),
  the runtime data flow ([architecture.md](../architecture.md)), code generation
  ([codegen.md](../codegen.md)), modules
  ([modules_architecture.md](../modules_architecture.md)), and the boundary type
  spec ([types.md](../types.md)).
- `docs/design/` — the original intent: the concept
  ([ORIGINAL_DESIGN.md](../design/ORIGINAL_DESIGN.md)), versioning/polyfills
  ([compatibility.md](../design/compatibility.md)), and the iOS design
  ([ios_design.md](../design/ios_design.md)).
