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

#include <memory>
#include <string>

namespace facebook {
namespace rdma {

// Type descriptors: the compile-time target type of an *erased* channel, baked
// into the generated bridge and carried with the channel at runtime so values
// marshal by declared type instead of by JS value shape.
// See docs/design/type_descriptors.md for the contract.
//
// Grammar (one line, mirrored by the Kotlin emitters in the kernel/plugin
// compiler plugins):
//
//   descriptor := leaf | "list:" descriptor | "map:" descriptor ":" descriptor
//   leaf       := <type fqn> ["?"] ["!"]
//
// `?` marks a nullable type (null is a value on that channel), `!` marks an
// `@RDMA` enum (crosses as the entry name string; the kernel materializes it
// via `valueOf`). In `map:key:value` the `:` between key and value belongs to
// the map rule; a leaf stops at the next `:` without consuming it.
struct TypeDesc {
    enum Kind { LEAF, LIST, MAP };

    Kind kind = LEAF;
    std::string fqn;                        // LEAF: `kotlin.Int`, a class FQN, ...
    bool nullable = false;
    bool isEnum = false;
    std::shared_ptr<TypeDesc> elem;         // LIST
    std::shared_ptr<TypeDesc> key, value;   // MAP

    static TypeDesc leaf(std::string fqn, bool nullable = false, bool isEnum = false) {
        TypeDesc d;
        d.kind = LEAF;
        d.fqn = std::move(fqn);
        d.nullable = nullable;
        d.isEnum = isEnum;
        return d;
    }

    static TypeDesc list(TypeDesc elem) {
        TypeDesc d;
        d.kind = LIST;
        d.elem = std::make_shared<TypeDesc>(std::move(elem));
        return d;
    }

    static TypeDesc map(TypeDesc key, TypeDesc value) {
        TypeDesc d;
        d.kind = MAP;
        d.key = std::make_shared<TypeDesc>(std::move(key));
        d.value = std::make_shared<TypeDesc>(std::move(value));
        return d;
    }

    // Parses one descriptor starting at `pos` and returns the index just past it
    // (past the map separator it consumed). A malformed tail degrades to a
    // `kotlin.Any` leaf so marshalling falls back instead of crashing.
    static size_t parse(const std::string& d, size_t pos, TypeDesc& out) {
        if (d.compare(pos, 5, "list:") == 0) {
            TypeDesc elem;
            size_t next = parse(d, pos + 5, elem);
            out = list(std::move(elem));
            return next;
        }
        if (d.compare(pos, 4, "map:") == 0) {
            TypeDesc key, value;
            size_t keyEnd = parse(d, pos + 4, key);
            size_t valueStart = keyEnd;
            if (valueStart < d.size() && d[valueStart] == ':') valueStart++;
            size_t end = parse(d, valueStart, value);
            out = map(std::move(key), std::move(value));
            return end;
        }
        // Leaf: FQN + optional `?`/`!` flags, terminated by `:` or end of input.
        size_t i = pos;
        while (i < d.size() && d[i] != ':' && d[i] != '?' && d[i] != '!') i++;
        out = leaf(d.substr(pos, i - pos));
        while (i < d.size() && (d[i] == '?' || d[i] == '!')) {
            if (d[i] == '?') out.nullable = true;
            if (d[i] == '!') out.isEnum = true;
            i++;
        }
        return i;
    }

    // Whole-string parse with a tolerant fallback: empty/garbage input becomes a
    // `kotlin.Any` leaf (marshal treats it as "runtime-dispatch", the pre-descriptor
    // behavior).
    static TypeDesc parseOrAny(const std::string& d) {
        TypeDesc out;
        if (d.empty()) {
            return leaf("kotlin.Any");
        }
        size_t next = parse(d, 0, out);
        (void)next;
        return out;
    }

    // Inverse of [parse]; also accepted by the legacy `elementType` string parsers
    // (they only interpret the `list:`/`map:` structure and treat leaves as opaque).
    std::string serialize() const {
        switch (kind) {
            case LIST: return "list:" + elem->serialize();
            case MAP: return "map:" + key->serialize() + ":" + value->serialize();
            case LEAF:
            default: {
                std::string s = fqn;
                if (nullable) s += '?';
                if (isEnum) s += '!';
                return s;
            }
        }
    }
};

} // namespace rdma
} // namespace facebook
