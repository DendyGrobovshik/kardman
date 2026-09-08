<!--
Copyright 2026 DendyGrobovshik

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->
# Idea
Serialization/deserialization is not perfect especially in case of sharing objects between runtimes.
For simplicity, let's consider a scenario with `kernel`(core static) and `plugin`(dynamically loaded) living in a different runtimes. When `shared` object is created from plugin code it is immediately created in kernel runtime. When plugin call function for shared object it `proxy` the real action in kernel runtime.

# Motivation
This outperform serialization in some scenarios. Of course primitive values must be copied, but copying compound value types can be expensive, e.g. creating compound value in plugin memory and then copying to kernel memory can be n-times slower than direct creation in kernel memory not to mention memory consumption. It's also quite hard to efficiently serialize cross-referenced objects like graph, and we don't have such problem here.

# Example
Let's consider possible appearance in kotlin. Type that must to be exposed to plugin need to be annotated.

```kotlin
package com.example.kernel

@RDMA
class Person(val name: String, val age: Int) {
    override fun toString(): String {
        return "Person(name='$name', age=$age)"
    }
}
```

Plugin simply import and use the type.

```kotlin
package com.example.plugin

import com.example.kernel.Person

fun main() {
    val person = Person("Эдвард Каллен", 104)
    println(person.toString())
}
```

# How is works
Note that runtimes might be be complitely different but they need glue code and in our reality this is most likely `C`/`C++`.
Shared object created in kernel runtime and exposed to plugin as `handle` - reference to object that 1) prevent GC if only plugin references the object and 2) release reference when the object is being freed by plugin GC. Second part is not always possible, but sometimes pretty straightforward e.g. if plugin runs in javascript runtime like `hermes` then handle class inherits `jsi::HostObject` and implements all releasing logic in destructor.

### Kernel generation
Annotation processor detect marked types, collect their constuctors, functions and properties and generate for them proxies with `@JvmStatic` or `@CName`, e.g. kotlin JVM kernel and js hermes jsi runtime:

```kotlin
package com.example.kernel

class Person(val name: String, val age: Int) {
    override fun toString(): String {
        return "Person(name='$name', age=$age)"
    }
}
```

It also generation handle in glue code.

```c++
#include <jsi/jsi.h>
#include <jni.h>
#include <string>

using namespace facebook;

struct JniCache {
    jclass personClass = nullptr;
    jmethodID constructor = nullptr;
    jmethodID toStringMethod = nullptr;
};

// Cache instance
static JniCache gJniCache;

// State container (в стиле Nitro)
class PersonNativeState : public jsi::NativeState {
public:
    JavaVM* jvm_;
    jobject globalPersonRef_;

    PersonNativeState(JavaVM* jvm, jobject globalRef) 
        : jvm_(jvm), globalPersonRef_(globalRef) {}

    ~PersonNativeState() override {
        if (globalPersonRef_ != nullptr) {
            JNIEnv* env = nullptr;
            jint res = jvm_->GetEnv((void**)&env, JNI_VERSION_1_6);
            bool isAttached = false;
            if (res == JEDETACHED) {
                res = jvm_->AttachCurrentThread(&env, nullptr);
                if (res == JNI_OK) isAttached = true;
            }
            if (env != nullptr) {
                env->DeleteGlobalRef(globalPersonRef_);
            }
            if (isAttached) jvm_->DetachCurrentThread();
        }
    }
};

jsi::Value personToStringNative(jsi::Runtime& runtime, const jsi::Value& thisVal, const jsi::Value* args, size_t count, JavaVM* jvm) {
    jsi::Object thisObj = thisVal.asObject(runtime);
    auto state = std::static_pointer_cast<PersonNativeState>(thisObj.getNativeState(runtime));
    
    JNIEnv* env = nullptr;
    jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (!env) return jsi::Value::undefined();

    jstring jstr = (jstring)env->CallObjectMethod(state->globalPersonRef_, gJniCache.toStringMethod);

    const char* cstr = env->GetStringUTFChars(jstr, nullptr);
    std::string cppStr(cstr);
    env->ReleaseStringUTFChars(jstr, cstr);

    return jsi::Value(jsi::String::createFromUtf8(runtime, cppStr));
}

void installRdmaBridge(jsi::Runtime& runtime, JavaVM* jvm, JNIEnv* env) {
    
    if (gJniCache.personClass == nullptr) {
        jclass localClass = env->FindClass("com/example/rdma/Person");
        
        gJniCache.personClass = (jclass)env->NewGlobalRef(localClass);
        env->DeleteLocalRef(localClass);

        gJniCache.constructor = env->GetMethodID(gJniCache.personClass, "<init>", "(Ljava/lang/String;I)V");
        gJniCache.toStringMethod = env->GetMethodID(gJniCache.personClass, "toString", "()Ljava/lang/String;");
    }

    jsi::Object personPrototype(runtime);
    auto toStringFunc = jsi::Function::createFromHostFunction(
        runtime, jsi::PropNameID::forAscii(runtime, "toString"), 0,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) {
            return personToStringNative(r, thisVal, args, count, jvm);
        }
    );
    personPrototype.setProperty(runtime, "toString", std::move(toStringFunc));
    
    auto sharedPrototype = std::make_shared<jsi::Object>(std::move(personPrototype));
    jsi::Object rdmaNamespace(runtime);

    auto createPersonFunc = jsi::Function::createFromHostFunction(
        runtime, jsi::PropNameID::forAscii(runtime, "createPerson"), 2,
        [jvm, sharedPrototype](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            
            JNIEnv* env = nullptr;
            jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
            
            std::string name = args.asString(r).utf8(r);
            int age = (int)args.asNumber();

            jstring jName = env->NewStringUTF(name.c_str());
            
            jobject localPerson = env->NewObject(gJniCache.personClass, gJniCache.constructor, jName, age);
            env->DeleteLocalRef(jName);

            jobject globalPerson = env->NewGlobalRef(localPerson);
            env->DeleteLocalRef(localPerson);

            jsi::Object jsObject = jsi::Object::createWithPrototype(r, sharedPrototype.get());
            auto nativeState = std::make_shared<PersonNativeState>(jvm, globalPerson);
            jsObject.setNativeState(r, nativeState);

            return jsObject;
        }
    );

    rdmaNamespace.setProperty(runtime, "createPerson", std::move(createPersonFunc));
    runtime.global().setProperty(runtime, "RDMA", std::move(rdmaNamespace));
}
```

So it also exposes functionality to js.

### Plugin generation
Usages from import detected by KSP and replaced with proxy calls. So it actually generate slightly modified file and this file will be used for compiling.

```kotlin
package com.example.plugin

fun main() {
    val person = js(global.RDMA.createPerson("Эдвард Каллен", 104);)
    println(js(person.toString()))
}
```


# Benefits
- plugin code might be easily transformed to kernel by just coping the code between packages


# Resources
- https://nitro.margelo.com/ nitro modules are used for native implementation(e.g. in c++, kotlin, swift) of object/functions that are available in javascript. Main difference in the number of wrappers/interfaces needed to be generated/writed manually due to different language interop.