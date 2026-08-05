#pragma once
#include <jsi/jsi.h>
#include <unordered_map>
#include <string>
#include <memory>
#include <jni.h>
#include <android/log.h>

struct RdmaVtable {
    std::unordered_map<std::string, std::shared_ptr<facebook::jsi::Function>> entries;
    facebook::jsi::Runtime* rt;
    JavaVM* jvm;

    RdmaVtable(facebook::jsi::Runtime* r, JavaVM* j) : rt(r), jvm(j) {}
};
