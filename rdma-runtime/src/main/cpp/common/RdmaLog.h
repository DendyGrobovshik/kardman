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

// Platform-neutral logging. Each translation unit defines `LOG_TAG` before
// including this header; the LOGI/LOGW/LOGE macros print that tag.
#if defined(__ANDROID__)
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) do { std::printf("[INFO] %s: ", LOG_TAG); std::printf(__VA_ARGS__); std::printf("\n"); } while (0)
#define LOGW(...) do { std::printf("[WARN] %s: ", LOG_TAG); std::printf(__VA_ARGS__); std::printf("\n"); } while (0)
#define LOGE(...) do { std::fprintf(stderr, "[ERROR] %s: ", LOG_TAG); std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#endif
