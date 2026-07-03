//
// Created by Andrew Druk on 24.01.2024.
//

#include <jni.h>
#include <string>

#include "LlamaCpp.h"
#include "common.h"
#include "chat.h"

#include "console.h"
#include "ggml-backend.h"
#include "log.h"

#include <cassert>
#include <cinttypes>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>
#include <utility>
#include <vector>
#include <mutex>

#include <csignal>
#include <unistd.h>
#include <android/log.h>
#include <fcntl.h>

#define TAG "llama-android.cpp"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

void LlamaModel::loadModel(const std::string &modelPath,
                           int32_t n_gpu_layers,
                           llama_progress_callback progress_callback,
                           void * progress_callback_user_data,
                           bool disableRepack) {

    // initialize the model
    llama_model_params model_params = llama_model_default_params();
    // Keep the LLM entirely on CPU; Vulkan is reserved exclusively for the
    // mtmd/CLIP vision encoder (much faster there). n_gpu_layers=0 alone is
    // NOT enough: with a Vulkan backend loaded (GGML_BACKEND_DL), the
    // scheduler still reserves a Vulkan compute buffer and routes part of the
    // decode graph to Vulkan0 — and the image-conditioned decode for M-RoPE
    // vision models (Qwen3VL) then fails on the Mali Vulkan backend
    // (ggml graph compute status 1 -> garbage output). Pinning the model to a
    // CPU-only device list removes Vulkan from the text graph entirely. The
    // mtmd CLIP context selects Vulkan0 independently (MTMD_BACKEND_DEVICE),
    // so vision encoding still runs on the GPU.
    model_params.n_gpu_layers = 0;
    static ggml_backend_dev_t cpu_only_devices[2] = { nullptr, nullptr };
    cpu_only_devices[0] = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    if (cpu_only_devices[0] != nullptr) {
        model_params.devices = cpu_only_devices;
    }
    model_params.progress_callback = progress_callback;
    model_params.progress_callback_user_data = progress_callback_user_data;
    // Weight repacking (the CPU "extra" buffer types) copies quantized
    // weights into a freshly-allocated RAM buffer, which defeats mmap and
    // forces the whole model resident. For models that don't fit in RAM the
    // caller disables it so weights stay memory-mapped and load successfully
    // (slower matmuls, but they fit).
    model_params.use_extra_bufts = !disableRepack;
    model = llama_model_load_from_file(modelPath.c_str(), model_params);
    if (model == nullptr) {
        LOG_ERR("%s: failed to load model '%s'\n", __func__, modelPath.c_str());
        return;
    }
    chat_tmpls = common_chat_templates_init(model, "");
}

void LlamaModel::loadMmprojModel(const std::string &mmprojPath) {
    if (model == nullptr) {
        LOG_ERR("%s: text model not loaded yet\n", __func__);
        return;
    }

    mtmd_context_params params = mtmd_context_params_default();
    params.n_threads = std::max(1, std::min(kMaxVisionThreads, (int) sysconf(_SC_NPROCESSORS_ONLN) - 2));
    params.warmup = false;
    params.print_timings = true;
    // Use model defaults for image tokens (-1). Some models like Gemma 4
    // have high minimum pixel requirements that reject low token caps.

    LOGi("loadMmprojModel: loading %s (use_gpu=%d, n_threads=%d, image_max_tokens=%d)",
         mmprojPath.c_str(), params.use_gpu, params.n_threads, params.image_max_tokens);

    // The Vulkan CLIP init can SIGSEGV inside the GPU driver on some devices —
    // an uncatchable crash, not a C++ exception. Bracket it with the sentinel so
    // a crash here is detected on the next launch and Vulkan vision is disabled.
    // CPU encodes never hit this, so only mark the Vulkan path.
    const char *clip_backend = std::getenv("MTMD_BACKEND_DEVICE");
    bool clip_on_vulkan = (clip_backend != nullptr && strcmp(clip_backend, "CPU") != 0);
    if (clip_on_vulkan) clipSentinelBeginVulkanAttempt();

    // mtmd_init_from_file catches exceptions internally and returns null,
    // but its LOG_ERR may not reach Android logcat reliably. Wrap again to be safe.
    try {
        mtmd_ctx = mtmd_init_from_file(mmprojPath.c_str(), model, params);
    } catch (const std::exception &e) {
        LOGe("loadMmprojModel EXCEPTION: %s", e.what());
        mtmd_ctx = nullptr;
    } catch (...) {
        LOGe("loadMmprojModel UNKNOWN EXCEPTION");
        mtmd_ctx = nullptr;
    }

    // Reached only if mtmd_init returned (success or caught failure) without
    // crashing — clear the marker so this load isn't counted as a crash.
    if (clip_on_vulkan) clipSentinelEndVulkanAttempt();

    // Also check n_embd match manually for better diagnostics
    if (mtmd_ctx == nullptr) {
        int n_embd_text = llama_model_n_embd(model);
        LOGe("loadMmprojModel: FAILED (text model n_embd=%d). "
             "The mmproj may be incompatible with this text model.", n_embd_text);
    }

    if (mtmd_ctx == nullptr) {
        LOGe("loadMmprojModel: FAILED to load mmproj model '%s'", mmprojPath.c_str());
    } else {
        LOGi("loadMmprojModel: OK, vision=%d", mtmd_support_vision(mtmd_ctx));
    }
}

bool LlamaModel::supportsToolCalling() {
    if (!chat_tmpls) {
        return false;
    }
    auto caps = common_chat_templates_get_caps(chat_tmpls.get());
    auto it = caps.find("supports_tools");
    return it != caps.end() && it->second;
}

bool LlamaModel::supportsVision() {
    return mtmd_ctx != nullptr && mtmd_support_vision(mtmd_ctx);
}

LlamaGenerationSession* LlamaModel::createGenerationSession(const SamplerParams &params) {
    if (model == nullptr) {
        return nullptr;
    }
    // unique_ptr guards the allocation through init(); released to the
    // caller (the JNI layer owns it via the Kotlin object's handle).
    auto session = std::make_unique<LlamaGenerationSession>();
    session->init(model, chat_tmpls.get(), mtmd_ctx, params);
    return session.release();
}

int LlamaModel::getContextTrainSize() {
    if (model == nullptr) {
        return 0;
    }
    return llama_model_n_ctx_train(model);
}

uint64_t LlamaModel::getModelSize() {
    if (this->model == nullptr) {
        return 0;
    }
    return llama_model_size(this->model);
}

bool LlamaModel::supportsThinking() {
    if (!chat_tmpls) {
        return false;
    }
    return common_chat_templates_support_enable_thinking(chat_tmpls.get());
}

std::string LlamaModel::getModelReport() {
    if (model == nullptr) {
        return "";
    }

    char desc[256];
    llama_model_desc(model, desc, sizeof(desc));

    uint64_t n_params = llama_model_n_params(model);
    int n_ctx_train = llama_model_n_ctx_train(model);

    std::ostringstream report;
    report << "Model\n";
    report << "  Architecture: " << desc << "\n";

    if (n_params >= 1000000000ULL) {
        report << "  Parameters: " << std::fixed << std::setprecision(2)
               << (n_params / 1e9) << "B\n";
    } else {
        report << "  Parameters: " << std::fixed << std::setprecision(0)
               << (n_params / 1e6) << "M\n";
    }

    report << "  Training context: " << n_ctx_train << "\n";

    return report.str();
}

void LlamaModel::unloadModel() {
    if (mtmd_ctx != nullptr) {
        mtmd_free(mtmd_ctx);
        mtmd_ctx = nullptr;
    }
    chat_tmpls.reset();
    if (model != nullptr) {
        llama_model_free(model);
        model = nullptr;
    }
}
