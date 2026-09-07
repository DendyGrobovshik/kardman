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
project(RdmaUserBridge)
set(CMAKE_CXX_STANDARD 17)

find_package(rdma-runtime-android REQUIRED CONFIG)
find_package(hermes-engine REQUIRED CONFIG)

file(GLOB GENERATED_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/generated/*.cpp")

add_library(rdma_user SHARED
        UserBridgeJni.cpp
        ${GENERATED_SOURCES}
)

target_include_directories(rdma_user PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/generated
)

target_link_libraries(rdma_user
        rdma-runtime-android::rdma_runtime
        hermes-engine::hermesvm
        android
        log
)
