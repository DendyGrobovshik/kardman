cmake_minimum_required(VERSION 3.22.1)
project(RdmaRuntime)
set(CMAKE_CXX_STANDARD 17)

find_package(hermes-engine REQUIRED CONFIG)

file(GLOB GENERATED_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/generated/*.cpp")

add_library(rdma_runtime SHARED
        RdmaJni.cpp
        RdmaRuntime.cpp
        RdmaCompose.cpp
        ListHandle.cpp
        ${GENERATED_SOURCES}
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
