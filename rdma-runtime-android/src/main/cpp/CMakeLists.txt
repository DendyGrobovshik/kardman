# Copyright 2026 DendyGrobovshik
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
cmake_minimum_required(VERSION 3.22.1)
project(RdmaRuntime)
set(CMAKE_CXX_STANDARD 17)

find_package(hermes-engine REQUIRED CONFIG)

add_library(rdma_runtime SHARED
        RdmaJni.cpp
        RdmaRuntime.cpp
        RdmaCompose.cpp
        RdmaRendezvous.cpp
        ListHandle.cpp
        generated/RdmaComposerProxy.cpp
)

target_include_directories(rdma_runtime PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/generated
)

target_link_libraries(rdma_runtime
        hermes-engine::hermesvm
        android
        log
)
