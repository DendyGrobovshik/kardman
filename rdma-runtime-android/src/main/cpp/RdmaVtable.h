#pragma once
#include <jsi/jsi.h>
#include <vector>
#include <memory>

struct RdmaVtable {
    std::vector<std::shared_ptr<facebook::jsi::Function>> entries;
    facebook::jsi::Runtime* rt;

    RdmaVtable(facebook::jsi::Runtime* r, size_t methodCount)
        : entries(methodCount), rt(r) {}
};
