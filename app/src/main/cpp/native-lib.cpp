#include <jni.h>
#include <string>

#include "LlamaCpp.h"
#include "common.h"

#include "console.h"
#include "ggml.h"
#include "ggml-backend.h"
#include "gguf.h"
#include "llama.h"
#include "log.h"

#include <cassert>
#include <cinttypes>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <sys/system_properties.h>
#include <ctime>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>
#include <iostream>
#include <csignal>
#include <unistd.h>
#include <android/log.h>

class AndroidLogBuf : public std::streambuf {
protected:
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        __android_log_print(ANDROID_LOG_INFO, "Llama", "%.*s", (int)n, s);
        return n;
    }

    int overflow(int c) override {
        if (c != EOF) {
            char c_as_char = static_cast<char>(c);
            __android_log_write(ANDROID_LOG_INFO, "Llama", &c_as_char);
        }
        return c;
    }
};

#define TAG "llama-android.cpp"
static void log_callback(ggml_log_level level, const char * fmt, void * data) {
    // fmt is already formatted by llama.cpp — use %s to avoid interpreting % in the message
    if (level == GGML_LOG_LEVEL_ERROR)     __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", fmt);
    else if (level == GGML_LOG_LEVEL_INFO) __android_log_print(ANDROID_LOG_INFO, TAG, "%s", fmt);
    else if (level == GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", fmt);
    else __android_log_print(ANDROID_LOG_DEFAULT, TAG, "%s", fmt);
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaCpp_probeModelMetadata(JNIEnv *env, jobject thiz, jstring modelPath) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);

    struct gguf_init_params params = { /*.no_alloc =*/ true, /*.ctx =*/ nullptr };
    struct gguf_context * gguf_ctx = gguf_init_from_file(path, params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (gguf_ctx == nullptr) {
        return nullptr;
    }

    // Read general.name
    std::string name;
    int64_t name_key = gguf_find_key(gguf_ctx, "general.name");
    if (name_key >= 0) {
        name = gguf_get_val_str(gguf_ctx, name_key);
    }

    // Check tokenizer.chat_template existence
    int64_t template_key = gguf_find_key(gguf_ctx, "tokenizer.chat_template");
    bool has_chat_template = (template_key >= 0);

    gguf_free(gguf_ctx);

    // Return String[] { name, hasChatTemplate }
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(2, stringClass, nullptr);
    env->SetObjectArrayElement(result, 0, env->NewStringUTF(name.c_str()));
    env->SetObjectArrayElement(result, 1, env->NewStringUTF(has_chat_template ? "true" : "false"));

    return result;
}

// GPUs whose Vulkan driver mishandles the CLIP vision graph. The encoder runs
// on Vulkan by default (much faster than CPU); these specific parts fall back
// to CPU. Matched as a substring of the ggml device description (the Vulkan
// deviceName). Denylist, not allowlist: an untested bad GPU still crashes, but
// only into the survivable :llama-restart recovery path. Extend from Vitals.
static bool clipVulkanIsKnownBad(const char *gpuDescription) {
    if (gpuDescription == nullptr) return false;
    static const char *kDeny[] = {
        "PowerVR",  // Tensor G5 (Pixel 10): CLIP encode slow AND numerically wrong
        "Mali-G52", // Galaxy A32 (Helio G80) etc.: SIGSEGV in ggml_vk_create_buffer
    };
    for (const char *needle : kDeny) {
        if (strstr(gpuDescription, needle) != nullptr) return true;
    }
    return false;
}

// --- Vulkan CLIP crash sentinel ---------------------------------------------
// Catches GPUs not on the static denylist after a single crash. The inflight
// marker brackets the Vulkan vision-encoder init (the SIGSEGV happens inside
// the GPU driver and can't be caught); if it's still present at the next
// process start, that attempt crashed -> promote to a permanent block so CLIP
// runs on CPU from then on. An empty stateDir disables the sentinel (tests).
static std::string g_clipStateDir;
static std::string clipInflightPath() { return g_clipStateDir + "/vulkan_clip.inflight"; }
static std::string clipBlockedPath()  { return g_clipStateDir + "/vulkan_clip.blocked"; }

static bool fileExists(const std::string &path) {
    FILE *f = fopen(path.c_str(), "r");
    if (f != nullptr) { fclose(f); return true; }
    return false;
}
static void touchFile(const std::string &path) {
    FILE *f = fopen(path.c_str(), "w");
    if (f != nullptr) fclose(f);
}

void clipSentinelInit(const std::string &stateDir) {
    g_clipStateDir = stateDir;
    if (g_clipStateDir.empty()) return;
    // A marker that survived a process restart means the last Vulkan vision
    // attempt took the process down. Make the block permanent.
    if (fileExists(clipInflightPath())) {
        touchFile(clipBlockedPath());
        remove(clipInflightPath().c_str());
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "previous Vulkan CLIP attempt crashed; disabling Vulkan vision");
    }
}
bool clipSentinelVulkanBlocked() {
    return !g_clipStateDir.empty() && fileExists(clipBlockedPath());
}
void clipSentinelBeginVulkanAttempt() {
    if (!g_clipStateDir.empty()) touchFile(clipInflightPath());
}
void clipSentinelEndVulkanAttempt() {
    if (!g_clipStateDir.empty()) remove(clipInflightPath().c_str());
}

extern "C" JNIEXPORT int
JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaCpp_init(JNIEnv *env, jobject object, jstring nativeLibDir, jstring stateDir) {

    if (stateDir != nullptr) {
        const char *sd = env->GetStringUTFChars(stateDir, nullptr);
        clipSentinelInit(sd);
        env->ReleaseStringUTFChars(stateDir, sd);
    }

    // Redirect std::cerr to logcat
    AndroidLogBuf androidLogBuf;
    std::cerr.rdbuf(&androidLogBuf);

    llama_log_set(log_callback, NULL);
    ggml_log_set(log_callback, NULL);
    // mtmd/clip use their own logger (default writes to C stderr -> /dev/null
    // on Android, so vision-encoder diagnostics like "CLIP using <backend>"
    // are otherwise lost). Route them to logcat too.
    mtmd_log_set(log_callback, NULL);

    // With GGML_BACKEND_DL=ON the CPU and Vulkan backends live in separate
    // libggml-*.so files alongside libllamacpp.so. dlopen them so
    // llama_model_load_from_file (and the mtmd vision encoder) have backends
    // to bind tensors to.
    if (nativeLibDir != nullptr) {
        const char *path = env->GetStringUTFChars(nativeLibDir, nullptr);
        ggml_backend_load_all_from_path(path);
        env->ReleaseStringUTFChars(nativeLibDir, path);
    } else {
        ggml_backend_load_all();
    }

    // Pick the CLIP vision-encoder backend now that the Vulkan backend (if any)
    // is loaded and its devices are enumerable. clip.cpp selects by device name
    // (MTMD_BACKEND_DEVICE, e.g. "Vulkan0"). Default to the GPU (much faster
    // than CPU vision) and fall back to "CPU" only for denylisted parts whose
    // Vulkan driver mishandles the CLIP graph. Mobile GPUs register as IGPU (not
    // GPU), so dev_by_type(GPU) misses them; enumerate instead. Reading the
    // device name/description is safe (no buffer allocation, which is where the
    // crash happens). Override with `setprop debug.lmp.mtmd_backend <name>`.
    const char *mtmd_backend = "CPU";
    const char *gpu_name = nullptr;
    const char *gpu_desc = nullptr;
    size_t dev_count = ggml_backend_dev_count();
    for (size_t i = 0; i < dev_count; i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (dev == nullptr) continue;
        enum ggml_backend_dev_type type = ggml_backend_dev_type(dev);
        const char *name = ggml_backend_dev_name(dev);
        const char *desc = ggml_backend_dev_description(dev);
        __android_log_print(ANDROID_LOG_INFO, TAG, "ggml device %zu: name=%s type=%d desc=%s",
                            i, name ? name : "?", (int) type, desc ? desc : "?");
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) {
            gpu_name = name;
            gpu_desc = desc;
        }
    }
    if (gpu_name != nullptr && !clipVulkanIsKnownBad(gpu_desc)
            && !clipSentinelVulkanBlocked()) {
        mtmd_backend = gpu_name;
    }
    char backend_override[PROP_VALUE_MAX] = {0};
    if (__system_property_get("debug.lmp.mtmd_backend", backend_override) > 0) {
        mtmd_backend = backend_override;
    }
    setenv("MTMD_BACKEND_DEVICE", mtmd_backend, 1);
    __android_log_print(ANDROID_LOG_INFO, TAG, "vision backend: %s (gpu=%s)",
                        mtmd_backend, gpu_desc ? gpu_desc : "none");

    llama_backend_init();
    return 0;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaCpp_systemInfo(JNIEnv *env, jobject object) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C" JNIEXPORT jobject
JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaCpp_loadModel(JNIEnv *env,
                   jobject activity,
                   jstring modelPath,
                   jobject progressCallback,
                   jboolean disableRepack) {

    struct CallbackContext {
        JNIEnv *env;
        jobject progressCallback;
    };

    auto* model = new LlamaModel();
    CallbackContext ctx = {env, progressCallback};
    const char* utfModelPath = env->GetStringUTFChars(modelPath, nullptr);
    model->loadModel(utfModelPath,
                     -1,
                     [](float progress, void *ctx) -> bool {
                            auto* context = static_cast<CallbackContext*>(ctx);
                            jclass clazz = context->env->GetObjectClass(context->progressCallback);
                            jmethodID methodId = context->env->GetMethodID(clazz, "onProgress", "(F)V");
                            context->env->CallVoidMethod(context->progressCallback, methodId, progress);
                            return true;
                     },
                     &ctx,
                     disableRepack == JNI_TRUE
                     );
    env->ReleaseStringUTFChars(modelPath, utfModelPath);

    if (!model->isLoaded()) {
        delete model;
        return nullptr;
    }

    jclass clazz = env->FindClass("com/druk/llamacpp/jni/NativeLlamaModel");
    jmethodID constructor = env->GetMethodID(clazz, "<init>", "()V");
    jobject obj = env->NewObject(clazz, constructor);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    env->SetLongField(obj, fid, (long) model);
    return obj;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_getModelSize(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) {
        return 0;
    }
    return model->getModelSize();
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_getModelReport(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) {
        return env->NewStringUTF("");
    }
    auto report = model->getModelReport();
    return env->NewStringUTF(report.c_str());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_supportsThinking(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) {
        return JNI_FALSE;
    }
    return model->supportsThinking() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_loadMmprojModel(JNIEnv *env, jobject thiz, jstring mmprojPath) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) {
        return;
    }
    const char* utfPath = env->GetStringUTFChars(mmprojPath, nullptr);
    model->loadMmprojModel(utfPath);
    env->ReleaseStringUTFChars(mmprojPath, utfPath);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_supportsVision(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) {
        return JNI_FALSE;
    }
    return model->supportsVision() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_unloadModel(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) {
        return;
    }
    env->SetLongField(thiz, fid, 0);
    model->unloadModel();
    delete model;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_getContextTrainSize(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) {
        return 0;
    }
    return model->getContextTrainSize();
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_createSession(JNIEnv *env, jobject thiz,
                                                  jint contextSize,
                                                  jfloat temperature,
                                                  jfloat topP,
                                                  jfloat repetitionPenalty,
                                                  jint topK,
                                                  jfloat minP,
                                                  jint seed,
                                                  jint thinkingBudget,
                                                  jstring systemPrompt) {

    jclass clazz1 = env->GetObjectClass(thiz);
    jfieldID fid1 = env->GetFieldID(clazz1, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid1);
    if (model == nullptr) {
        return nullptr;
    }

    SamplerParams params;
    params.n_ctx = contextSize;
    params.temperature = temperature;
    params.top_p = topP;
    params.repetition_penalty = repetitionPenalty;
    params.top_k = topK;
    params.min_p = minP;
    params.seed = (seed < 0) ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed);
    params.thinking_budget = thinkingBudget;
    if (systemPrompt != nullptr) {
        const char* utfSystemPrompt = env->GetStringUTFChars(systemPrompt, nullptr);
        if (utfSystemPrompt != nullptr) {
            params.system_prompt = utfSystemPrompt;
            env->ReleaseStringUTFChars(systemPrompt, utfSystemPrompt);
        }
    }

    jclass clazz2 = env->FindClass("com/druk/llamacpp/jni/NativeLlamaSession");
    jmethodID constructor = env->GetMethodID(clazz2, "<init>", "()V");
    jobject obj = env->NewObject(clazz2, constructor);

    LlamaGenerationSession* session = model->createGenerationSession(params);
    if (session == nullptr) {
        return nullptr;
    }
    jclass clazz3 = env->GetObjectClass(obj);
    jfieldID fid3 = env->GetFieldID(clazz3, "nativeHandle", "J");
    env->SetLongField(obj, fid3, (long)session);

    return obj;
}

extern "C" JNIEXPORT jint JNICALL Java_com_druk_llamacpp_jni_NativeLlamaSession_generate
        (JNIEnv *env, jobject obj, jobject callback) {
    jclass clazz = env->GetObjectClass(obj);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(obj, fid);
    if (session == nullptr) {
        return 1;
    }

    jclass javaClass = env->FindClass("com/druk/llamacpp/LlamaGenerationCallback");
    jmethodID onFullResponseId = env->GetMethodID(javaClass, "onFullResponse", "(Ljava/lang/String;)V");

    return session->generate(
            [env, onFullResponseId, callback](const std::string &fullResponse) {
                // Strip trailing incomplete UTF-8 sequence to avoid
                // NewStringUTF crash on invalid Modified UTF-8.
                std::string safe = fullResponse;
                while (!safe.empty()) {
                    unsigned char last = safe.back();
                    if ((last & 0x80) == 0) break;       // ASCII — complete
                    if ((last & 0xC0) == 0xC0) {          // Start byte without continuation
                        safe.pop_back();
                        break;
                    }
                    // Continuation byte — count how many
                    size_t pos = safe.size() - 1;
                    while (pos > 0 && (((unsigned char)safe[pos]) & 0xC0) == 0x80) pos--;
                    unsigned char start = safe[pos];
                    int expected;
                    if ((start & 0xE0) == 0xC0) expected = 2;
                    else if ((start & 0xF0) == 0xE0) expected = 3;
                    else if ((start & 0xF8) == 0xF0) expected = 4;
                    else { safe.resize(pos); break; }
                    int actual = (int)(safe.size() - pos);
                    if (actual < expected) safe.resize(pos);
                    break;
                }
                jstring jResponse = env->NewStringUTF(safe.c_str());
                if (jResponse != nullptr) {
                    env->CallVoidMethod(callback, onFullResponseId, jResponse);
                    env->DeleteLocalRef(jResponse);
                }
            }
    );
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_setImageData(JNIEnv *env,
                                                            jobject thiz,
                                                            jbyteArray data) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) {
        return;
    }

    jsize len = env->GetArrayLength(data);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    session->setImageData(reinterpret_cast<const unsigned char*>(bytes), len);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_addMessage(JNIEnv *env,
                                                         jobject thiz,
                                                         jstring message,
                                                         jboolean enableThinking) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) {
        return;
    }

    const char* utfMessage = env->GetStringUTFChars(message, nullptr);
    session->addMessage(utfMessage, enableThinking);
    env->ReleaseStringUTFChars(message, utfMessage);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_requestAbort(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) {
        return;
    }
    session->requestAbort();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_printReport(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) {
        return;
    }
    session->printReport();
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_getReport(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) {
        return env->NewStringUTF("");
    }
    auto report = session->getReport();
    auto string = env->NewStringUTF(report.c_str());
    return string;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_replayHistory(JNIEnv *env,
                                                             jobject thiz,
                                                             jobjectArray userMessages,
                                                             jobjectArray assistantMessages) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) {
        return;
    }

    int len = env->GetArrayLength(userMessages);
    std::vector<std::pair<std::string, std::string>> history;
    history.reserve(len);

    for (int i = 0; i < len; i++) {
        auto jUser = (jstring) env->GetObjectArrayElement(userMessages, i);
        auto jAssistant = (jstring) env->GetObjectArrayElement(assistantMessages, i);
        const char* user = env->GetStringUTFChars(jUser, nullptr);
        const char* assistant = env->GetStringUTFChars(jAssistant, nullptr);
        history.emplace_back(user, assistant);
        env->ReleaseStringUTFChars(jUser, user);
        env->ReleaseStringUTFChars(jAssistant, assistant);
        env->DeleteLocalRef(jUser);
        env->DeleteLocalRef(jAssistant);
    }

    session->replayHistory(history);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_supportsToolCalling(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) return JNI_FALSE;
    return model->supportsToolCalling() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_setTools(JNIEnv *env, jobject thiz, jstring toolsJson) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) return;
    const char* utfJson = env->GetStringUTFChars(toolsJson, nullptr);
    session->setTools(utfJson);
    env->ReleaseStringUTFChars(toolsJson, utfJson);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_getToolCallsJson(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) return env->NewStringUTF("[]");
    auto json = session->getToolCallsJson();
    return env->NewStringUTF(json.c_str());
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_submitToolResults(JNIEnv *env, jobject thiz,
                                                                 jstring resultsJson,
                                                                 jboolean enableThinking) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) return 1;
    const char* utfJson = env->GetStringUTFChars(resultsJson, nullptr);
    int result = session->submitToolResults(utfJson, enableThinking);
    env->ReleaseStringUTFChars(resultsJson, utfJson);
    return result;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_renderPreambleString(JNIEnv *env, jobject thiz,
                                                                    jboolean enableThinking) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) return env->NewStringUTF("");
    auto preamble = session->renderPreambleString(enableThinking);
    return env->NewStringUTF(preamble.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_setPreambleCachePath(JNIEnv *env, jobject thiz,
                                                                    jstring path,
                                                                    jstring fingerprint) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) return;
    const char* utfPath = path != nullptr ? env->GetStringUTFChars(path, nullptr) : nullptr;
    const char* utfFp   = fingerprint != nullptr ? env->GetStringUTFChars(fingerprint, nullptr) : nullptr;
    session->setPreambleCachePath(utfPath, utfFp);
    if (utfPath != nullptr) env->ReleaseStringUTFChars(path, utfPath);
    if (utfFp   != nullptr) env->ReleaseStringUTFChars(fingerprint, utfFp);
}

extern "C" JNIEXPORT void JNICALL Java_com_druk_llamacpp_jni_NativeLlamaSession_destroy
        (JNIEnv *env, jobject obj) {
    jclass clazz = env->GetObjectClass(obj);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(obj, fid);

    if (session != nullptr) {
        env->SetLongField(obj, fid, (long)nullptr);
        delete session;
        __android_log_print(ANDROID_LOG_DEBUG, "Llama", "Destroy");
    }
}
