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
#include "RdmaSignature.h"

#include "../third_party/monocypher/monocypher-ed25519.h"

namespace facebook {
namespace rdma {

bool rdmaVerifyEd25519(const uint8_t* message, size_t length, const uint8_t* signature, const uint8_t* publicKey) {
    if (!message || !signature || !publicKey) return false;
    return crypto_ed25519_check(signature, publicKey, message, length) == 0;
}

} // namespace rdma
} // namespace facebook
