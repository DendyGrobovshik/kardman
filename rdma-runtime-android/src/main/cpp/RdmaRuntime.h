#pragma once
#include <jsi/jsi.h>
#include <jni.h>
#include <string>

// Creates the Hermes runtime and installs the compose bridge (JSI only). Must be
// called on the Hermes thread; JNI caches are initialized separately on the UI
// thread via initRdmaComposeJniCache() before rdmaStart().
void initRdmaRuntime(JavaVM* jvm);

// Evaluates a JS asset/script on the Hermes thread. Fire-and-forget: the result
// is logged; errors are caught and logged. Runs only on the Hermes thread.
void evalJavaScript(const std::string& code);

// Returns the Hermes runtime. Hermes-thread access only.
facebook::jsi::Runtime* getRdmaRuntime();
