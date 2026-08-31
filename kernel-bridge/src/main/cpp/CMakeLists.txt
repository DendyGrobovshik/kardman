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
