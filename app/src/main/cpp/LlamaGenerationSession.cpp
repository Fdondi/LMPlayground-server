//
// Created by Andrew Druk on 22.01.2024.
//

#include <jni.h>
#include <string>

#include "LlamaCpp.h"

#include "common.h"
#include "chat.h"
#include "console.h"
#include "llama.h"
#include "log.h"

#include <cassert>
#include <cinttypes>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <utility>
#include <vector>
#include <mutex>

#include <unistd.h>
#include <android/log.h>
#include <asm-generic/fcntl.h>
#include <fcntl.h>

#define TAG "llama-android.cpp"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

bool is_valid_utf8(const char * string) {
    if (!string) {
        return true;
    }

    const unsigned char *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            // U+0000 to U+007F
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            // U+0080 to U+07FF
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            // U+0800 to U+FFFF
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            // U+10000 to U+10FFFF
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }

    return true;
}

LlamaGenerationSession::LlamaGenerationSession() = default;

LlamaGenerationSession::~LlamaGenerationSession() {
    if (ctx != nullptr) {
        llama_free(ctx);
    }
    if (smpl != nullptr) {
        llama_sampler_free(smpl);
    }
}

void LlamaGenerationSession::init(llama_model *model, const struct common_chat_templates *tmpls) {

    vocab = llama_model_get_vocab(model);
    chat_tmpls = tmpls;

    int n_threads = std::max(1, std::min(8, (int) sysconf(_SC_NPROCESSORS_ONLN) - 2));
    LOGi("Using %d threads", n_threads);

    static constexpr int MAX_CTX_MOBILE = 4096;
    int n_ctx_train = llama_model_n_ctx_train(model);
    int n_ctx = std::min(n_ctx_train, MAX_CTX_MOBILE);
    LOGi("Model training context: %d, using: %d", n_ctx_train, n_ctx);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = n_ctx;
    ctx_params.n_threads       = n_threads;
    ctx_params.n_threads_batch = n_threads;

    ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGe("%s: error: failed to create the llama_context\n" , __func__);
        return;
    }

    auto smplParams = llama_sampler_chain_default_params();
    smplParams.no_perf = true;

    smpl = llama_sampler_chain_init(smplParams);
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.8f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    prev_len = 0;
}

static std::string strip_think_tags(const std::string &text) {
    std::string result;
    size_t pos = 0;
    while (pos < text.size()) {
        size_t open = text.find("<think>", pos);
        if (open == std::string::npos) {
            result.append(text, pos, std::string::npos);
            break;
        }
        result.append(text, pos, open - pos);
        size_t close = text.find("</think>", open);
        if (close == std::string::npos) {
            break;
        }
        pos = close + 8; // strlen("</think>")
        // skip trailing newlines after the close tag
        while (pos < text.size() && text[pos] == '\n') pos++;
    }
    return result;
}

int LlamaGenerationSession::addMessage(const char *string, bool enableThinking) {
    if (chat_tmpls == nullptr || ctx == nullptr) {
        LOGe("addMessage called on uninitialized session");
        return 1;
    }

    common_chat_msg user_msg;
    user_msg.role = "user";
    user_msg.content = string;
    messages.push_back(user_msg);

    auto renderPrompt = [&](bool enableThinking) -> common_chat_params {
        common_chat_templates_inputs inputs;
        inputs.messages = messages;
        inputs.add_generation_prompt = true;
        inputs.use_jinja = true;
        inputs.enable_thinking = enableThinking;
        return common_chat_templates_apply(chat_tmpls, inputs);
    };

    // The Jinja template drops thinking content from non-last assistant messages,
    // so the re-rendered prompt is shorter than what's in the KV cache. We must
    // clear the cache and re-process from scratch when this happens.
    if (prev_had_thinking && prev_len > 0) {
        LOGi("Previous turn had thinking - clearing KV cache for re-render");
        llama_memory_clear(llama_get_memory(ctx), true);
        prev_len = 0;
    }

    auto result = renderPrompt(enableThinking);
    std::string full_prompt = result.prompt;
    additional_stops = result.additional_stops;

    if ((int)full_prompt.size() < prev_len) {
        LOGe("failed to apply the chat template");
        return 1;
    }

    std::string prompt = full_prompt.substr(prev_len);
    response.clear();

    bool is_first = (prev_len == 0);
    int n_ctx = llama_n_ctx(ctx);
    int n_ctx_used = is_first ? 0 : (int)llama_memory_seq_pos_max(llama_get_memory(ctx), 0);

    int n_prompt_tokens = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), NULL, 0, is_first, true);

    bool compacted = false;

    // Stage 1: strip thinking content from older assistant messages
    if (n_ctx_used + n_prompt_tokens > n_ctx) {
        LOGi("Context would overflow (%d + %d > %d), stripping thinking from older turns",
             n_ctx_used, n_prompt_tokens, n_ctx);

        bool stripped_any = false;
        for (size_t i = 0; i + 1 < messages.size(); i++) {
            if (messages[i].role == "assistant" && messages[i].content.find("<think>") != std::string::npos) {
                messages[i].content = strip_think_tags(messages[i].content);
                stripped_any = true;
            }
        }

        if (stripped_any) {
            result = renderPrompt(enableThinking);
            full_prompt = result.prompt;
            additional_stops = result.additional_stops;
            prompt = full_prompt;
            is_first = true;
            n_ctx_used = 0;
            n_prompt_tokens = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), NULL, 0, true, true);
            compacted = true;
        }
    }

    // Stage 2: drop oldest user+assistant pairs
    while (n_ctx_used + n_prompt_tokens > n_ctx && messages.size() > 1) {
        LOGi("Still overflowing (%d + %d > %d), dropping oldest turn (%zu messages remain)",
             n_ctx_used, n_prompt_tokens, n_ctx, messages.size());

        auto it = messages.begin();
        if (it->role == "system") ++it;
        if (it == messages.end()) break;
        messages.erase(it);

        it = messages.begin();
        if (it->role == "system") ++it;
        if (it != messages.end() && it->role == "assistant") {
            messages.erase(it);
        }

        result = renderPrompt(enableThinking);
        full_prompt = result.prompt;
        additional_stops = result.additional_stops;
        prompt = full_prompt;
        is_first = true;
        n_ctx_used = 0;
        n_prompt_tokens = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), NULL, 0, true, true);
        compacted = true;
    }

    if (compacted) {
        LOGi("Context compacted, clearing KV cache and reprocessing (%d tokens)", n_prompt_tokens);
        llama_memory_clear(llama_get_memory(ctx), true);
        prev_len = 0;
        is_first = true;
    }

    prompt_tokens.resize(n_prompt_tokens);
    if (llama_tokenize(vocab, prompt.c_str(), prompt.size(), prompt_tokens.data(), prompt_tokens.size(), is_first, true) < 0) {
        LOGe("failed to tokenize the prompt");
        return 1;
    }

    batch = llama_batch_get_one(prompt_tokens.data(), prompt_tokens.size());

    return 0;
}

void LlamaGenerationSession::finalizeResponse() {
    common_chat_msg assistant_msg;
    assistant_msg.role = "assistant";
    assistant_msg.content = response;
    messages.push_back(assistant_msg);

    prev_had_thinking = response.find("</think>") != std::string::npos;

    common_chat_templates_inputs inputs;
    inputs.messages = messages;
    inputs.add_generation_prompt = false;
    inputs.use_jinja = true;
    inputs.enable_thinking = true;

    auto result = common_chat_templates_apply(chat_tmpls, inputs);
    prev_len = (int)result.prompt.size();
}

int LlamaGenerationSession::generate(const ResponseCallback& callback) {
    if (ctx == nullptr || smpl == nullptr) {
        LOGe("generate called on uninitialized session");
        return 1;
    }

    int n_ctx = llama_n_ctx(ctx);
    int n_ctx_used = llama_memory_seq_pos_max(llama_get_memory(ctx), 0);
    if (n_ctx_used + batch.n_tokens > n_ctx) {
        LOGe("context size exceeded: n_ctx_used = %d, batch.n_tokens = %d, n_ctx = %d", n_ctx_used, batch.n_tokens, n_ctx);
        finalizeResponse();
        return 1;
    }

    if (llama_decode(ctx, batch)) {
        LOGe("failed to decode the batch");
        finalizeResponse();
        return 1;
    }

    last_token = llama_sampler_sample(smpl, ctx, -1);

    bool is_eog = llama_vocab_is_eog(vocab, last_token);

    if (!is_eog) {
        char buf[256];
        int n = llama_token_to_piece(vocab, last_token, buf, sizeof(buf), 0, true);
        if (n < 0) {
            LOGe("failed to convert token to piece");
            finalizeResponse();
            return 1;
        }
        std::string piece(buf, n);
        response += piece;

        for (const auto& stop : additional_stops) {
            if (response.size() >= stop.size() &&
                response.compare(response.size() - stop.size(), stop.size(), stop) == 0) {
                response.erase(response.size() - stop.size());
                is_eog = true;
                break;
            }
        }

        if (!is_eog) {
            callback(piece.c_str());
            batch = llama_batch_get_one(&last_token, 1);
            return 0;
        }
    }

    finalizeResponse();
    return 1;
}

void LlamaGenerationSession::printReport() {
    llama_perf_context_print(ctx);
}

std::string LlamaGenerationSession::getReport() {
    auto timings = llama_perf_context(ctx);
    std::ostringstream report;
    report << "load time = " << timings.t_load_ms << " ms\n\n";
    report << "prompt eval time = " << timings.t_p_eval_ms << " ms / " << timings.n_p_eval << " tokens\n";
    report << "(" << 1e3 / timings.t_p_eval_ms * timings.n_p_eval << " tokens per second)\n\n";
    report << "eval time = " << timings.t_eval_ms << " ms / " << timings.n_eval << " runs\n";
    report << "(" << 1e3 / timings.t_eval_ms * timings.n_eval << " tokens per second)\n\n";
    return report.str();
}
