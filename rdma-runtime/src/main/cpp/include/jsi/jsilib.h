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
/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

#pragma once

#include <jsi/jsi.h>

namespace facebook {
namespace jsi {

class FileBuffer : public Buffer {
 public:
  FileBuffer(const std::string& path);
  ~FileBuffer() override;

  size_t size() const override {
    return size_;
  }

  const uint8_t* data() const override {
    return data_;
  }

 private:
  size_t size_;
  uint8_t* data_;
};

// A trivial implementation of PreparedJavaScript that simply stores the source
// buffer and URL.
class SourceJavaScriptPreparation final : public jsi::PreparedJavaScript,
                                          public jsi::Buffer {
  std::shared_ptr<const jsi::Buffer> buf_;
  std::string sourceURL_;

 public:
  SourceJavaScriptPreparation(
      std::shared_ptr<const jsi::Buffer> buf,
      std::string sourceURL)
      : buf_(std::move(buf)), sourceURL_(std::move(sourceURL)) {}

  const std::string& sourceURL() const {
    return sourceURL_;
  }

  size_t size() const override {
    return buf_->size();
  }
  const uint8_t* data() const override {
    return buf_->data();
  }
};

} // namespace jsi
} // namespace facebook
