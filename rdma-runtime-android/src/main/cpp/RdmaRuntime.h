#pragma once
#include <jsi/jsi.h>
#include <jni.h>
#include <string>

void initRdmaRuntime(JavaVM* jvm);
void evalJavaScript(const std::string& code, std::string& result);
facebook::jsi::Runtime* getRdmaRuntime();
