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
#include "reasoning-budget.h"

#include <cassert>
#include <cinttypes>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <fstream>
#include <iostream>
#include <iomanip>
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

void LlamaGenerationSession::init(llama_model *model, const struct common_chat_templates *tmpls, const SamplerParams &params) {

    vocab = llama_model_get_vocab(model);
    chat_tmpls = tmpls;

    int n_threads = std::max(1, std::min(4, (int) sysconf(_SC_NPROCESSORS_ONLN) - 2));
    LOGi("Using %d threads", n_threads);

    int n_ctx_train = llama_model_n_ctx_train(model);
    int n_ctx = std::min(params.n_ctx, n_ctx_train);
    LOGi("Model training context: %d, using: %d", n_ctx_train, n_ctx);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = std::min(n_ctx, 512);
    ctx_params.n_threads       = n_threads;
    ctx_params.n_threads_batch = n_threads;

    ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGe("%s: error: failed to create the llama_context\n" , __func__);
        return;
    }

    auto smplParams = llama_sampler_chain_default_params();
    smplParams.no_perf = false;

    smpl = llama_sampler_chain_init(smplParams);

    sampler_params = params;

    // Repetition penalty (only if > 1.0)
    if (params.repetition_penalty > 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(256, params.repetition_penalty, 0.0f, 0.0f));
    }

    // Top-K (only if > 0)
    if (params.top_k > 0) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(params.top_k));
    }

    // Top-P (only if < 1.0)
    if (params.top_p < 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(params.top_p, 1));
    }

    // Min-P (only if > 0.0)
    if (params.min_p > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(params.min_p, 1));
    }

    // Temperature: greedy if 0, otherwise temp + dist
    if (params.temperature == 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(params.temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(params.seed));
    }

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
        if (enableThinking) {
            inputs.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;
        }
        return common_chat_templates_apply(chat_tmpls, inputs);
    };

    prev_enable_thinking = enableThinking;

    common_chat_params result;
    try {
        result = renderPrompt(enableThinking);
    } catch (const std::exception &e) {
        LOGe("Failed to render chat template: %s", e.what());
        messages.pop_back();
        return 1;
    } catch (...) {
        LOGe("Failed to render chat template: unknown error");
        messages.pop_back();
        return 1;
    }
    std::string full_prompt = result.prompt;
    additional_stops = result.additional_stops;

    // Check if the rendered prompt prefix matches what finalizeResponse computed.
    // The Jinja template may render assistant content differently depending on
    // position (e.g. Qwen3 adds <think></think> prefill to the last assistant
    // message but strips it from earlier ones). A mismatch means the KV cache
    // doesn't correspond to the current render, so we must clear and reprocess.
    if (prev_len > 0) {
        bool prefix_match = (int)full_prompt.size() >= prev_len &&
                            full_prompt.compare(0, prev_len, prev_rendered_prompt) == 0;
        if (!prefix_match) {
            LOGi("Prompt prefix mismatch, clearing KV cache");
            llama_memory_clear(llama_get_memory(ctx), true);
            prev_len = 0;
        }
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
            if (messages[i].role == "assistant") {
                // Use PEG parser to strip thinking from any format
                if (parser_initialized) {
                    auto parsed = common_chat_parse(messages[i].content, false, parser_params);
                    if (!parsed.reasoning_content.empty()) {
                        messages[i].content = parsed.content;
                        messages[i].reasoning_content.clear();
                        stripped_any = true;
                    }
                } else if (messages[i].content.find("<think>") != std::string::npos) {
                    messages[i].content = strip_think_tags(messages[i].content);
                    stripped_any = true;
                }
            }
        }

        if (stripped_any) {
            try {
                result = renderPrompt(enableThinking);
            } catch (const std::exception &e) {
                LOGe("Failed to render chat template after stripping: %s", e.what());
                messages.pop_back();
                return 1;
            } catch (...) {
                LOGe("Failed to render chat template after stripping: unknown error");
                messages.pop_back();
                return 1;
            }
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

        try {
            result = renderPrompt(enableThinking);
        } catch (const std::exception &e) {
            LOGe("Failed to render chat template after dropping turns: %s", e.what());
            messages.pop_back();
            return 1;
        } catch (...) {
            LOGe("Failed to render chat template after dropping turns: unknown error");
            messages.pop_back();
            return 1;
        }
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

    // Build PEG parser params for response parsing
    if (!result.parser.empty()) {
        parser_params = common_chat_parser_params(result);
        parser_params.reasoning_format = enableThinking
            ? COMMON_REASONING_FORMAT_DEEPSEEK : COMMON_REASONING_FORMAT_NONE;
        parser_params.parse_tool_calls = false;
        parser_params.parser.load(result.parser);
        parser_initialized = true;
    } else {
        parser_initialized = false;
    }

    // Add reasoning budget sampler on first thinking-enabled turn, using
    // the model's actual thinking tags from the template (not hardcoded).
    // Must be first in chain (before top-k/top-p/temp) so it can override logits,
    // so we rebuild the entire sampler chain.
    if (sampler_params.thinking_budget >= 0 && enableThinking && !budget_sampler_added && result.supports_thinking) {
        auto tokenize_str = [&](const std::string &text) -> std::vector<llama_token> {
            int n = -llama_tokenize(vocab, text.c_str(), text.size(), nullptr, 0, false, true);
            std::vector<llama_token> tokens(n);
            llama_tokenize(vocab, text.c_str(), text.size(), tokens.data(), tokens.size(), false, true);
            return tokens;
        };

        std::string start_tag = result.thinking_start_tag;
        std::string end_tag = result.thinking_end_tag;

        // For gpt-oss (Gemma 4) and similar models that use channel-based thinking,
        // thinking_start_tag/end_tag may be empty — detect from preserved tokens
        if (start_tag.empty() && !result.preserved_tokens.empty()) {
            for (const auto &tok : result.preserved_tokens) {
                if (tok.find("channel") != std::string::npos) {
                    start_tag = "<|channel|>analysis<|message|>";
                    end_tag = "<|end|>";
                    break;
                }
            }
        }

        if (!start_tag.empty() && !end_tag.empty()) {
            // Rebuild sampler chain with budget sampler first
            llama_sampler_free(smpl);
            auto smplParams = llama_sampler_chain_default_params();
            smplParams.no_perf = false;
            smpl = llama_sampler_chain_init(smplParams);

            // Budget sampler first (must override logits before other samplers filter)
            auto start_tokens  = tokenize_str(start_tag);
            auto end_tokens    = tokenize_str(end_tag);
            auto forced_tokens = end_tokens;
            llama_sampler_chain_add(smpl, common_reasoning_budget_init(
                    vocab, start_tokens, end_tokens, forced_tokens, sampler_params.thinking_budget));

            // Re-add other samplers in original order
            if (sampler_params.repetition_penalty > 1.0f)
                llama_sampler_chain_add(smpl, llama_sampler_init_penalties(256, sampler_params.repetition_penalty, 0.0f, 0.0f));
            if (sampler_params.top_k > 0)
                llama_sampler_chain_add(smpl, llama_sampler_init_top_k(sampler_params.top_k));
            if (sampler_params.top_p < 1.0f)
                llama_sampler_chain_add(smpl, llama_sampler_init_top_p(sampler_params.top_p, 1));
            if (sampler_params.min_p > 0.0f)
                llama_sampler_chain_add(smpl, llama_sampler_init_min_p(sampler_params.min_p, 1));
            if (sampler_params.temperature == 0.0f) {
                llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
            } else {
                llama_sampler_chain_add(smpl, llama_sampler_init_temp(sampler_params.temperature));
                llama_sampler_chain_add(smpl, llama_sampler_init_dist(sampler_params.seed));
            }

            budget_sampler_added = true;
            LOGi("Reasoning budget sampler added: budget=%d, start='%s', end='%s'",
                 sampler_params.thinking_budget, start_tag.c_str(), end_tag.c_str());
        }
    }

    return 0;
}

void LlamaGenerationSession::finalizeResponse() {
    common_chat_msg assistant_msg;
    assistant_msg.role = "assistant";
    assistant_msg.content = response;
    messages.push_back(assistant_msg);

    if (parser_initialized) {
        auto parsed = common_chat_parse(response, /*is_partial=*/false, parser_params);
        prev_had_thinking = !parsed.reasoning_content.empty();
    } else {
        prev_had_thinking = response.find("</think>") != std::string::npos;
    }

    try {
        common_chat_templates_inputs inputs;
        inputs.messages = messages;
        inputs.add_generation_prompt = false;
        inputs.use_jinja = true;
        inputs.enable_thinking = prev_enable_thinking;

        auto result = common_chat_templates_apply(chat_tmpls, inputs);
        prev_rendered_prompt = result.prompt;
        prev_len = (int)prev_rendered_prompt.size();
    } catch (const std::exception &e) {
        LOGe("Failed to render chat template in finalizeResponse: %s", e.what());
        prev_rendered_prompt.clear();
        prev_len = 0;
    } catch (...) {
        LOGe("Failed to render chat template in finalizeResponse: unknown error");
        prev_rendered_prompt.clear();
        prev_len = 0;
    }
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

    // Process prompt in chunks of n_batch to avoid exceeding the batch limit.
    // After replayHistory or context compaction the prompt can be much larger
    // than n_batch since the entire conversation is re-tokenized.
    int n_batch_limit = llama_n_batch(ctx);
    while (batch.n_tokens > n_batch_limit) {
        llama_batch chunk = llama_batch_get_one(batch.token, n_batch_limit);
        if (llama_decode(ctx, chunk)) {
            LOGe("failed to decode prompt chunk");
            finalizeResponse();
            return 1;
        }
        batch = llama_batch_get_one(batch.token + n_batch_limit, batch.n_tokens - n_batch_limit);
    }

    if (llama_decode(ctx, batch)) {
        LOGe("failed to decode the batch");
        finalizeResponse();
        return 1;
    }

    // Reset sampler and feed prompt tokens so the reasoning budget sampler
    // can detect <think> prefill from chat templates (e.g. Qwen3).
    // Only on the first call per turn (prompt_tokens is non-empty).
    if (!prompt_tokens.empty()) {
        llama_sampler_reset(smpl);
        for (const auto &token : prompt_tokens) {
            llama_sampler_accept(smpl, token);
        }
        prompt_tokens.clear();
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
            // Use PEG parser to normalize thinking format for the UI
            if (parser_initialized) {
                auto parsed = common_chat_parse(response, /*is_partial=*/true, parser_params);
                std::string normalized;
                if (!parsed.reasoning_content.empty()) {
                    normalized = "<think>" + parsed.reasoning_content;
                    if (!parsed.content.empty()) {
                        normalized += "</think>" + parsed.content;
                    }
                } else {
                    normalized = parsed.content.empty() ? response : parsed.content;
                }
                callback(normalized);
            } else {
                callback(response);
            }
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

void LlamaGenerationSession::replayHistory(const std::vector<std::pair<std::string, std::string>>& history) {
    messages.clear();
    for (const auto& pair : history) {
        common_chat_msg user_msg;
        user_msg.role = "user";
        user_msg.content = pair.first;
        messages.push_back(user_msg);

        common_chat_msg assistant_msg;
        assistant_msg.role = "assistant";
        assistant_msg.content = pair.second;
        messages.push_back(assistant_msg);
    }
    prev_len = 0;
    prev_rendered_prompt.clear();
    prev_had_thinking = false;
    prev_enable_thinking = false;
    response.clear();
    if (ctx != nullptr) {
        llama_memory_clear(llama_get_memory(ctx), true);
    }
    LOGi("Replayed %zu turns of history", history.size());
}

std::string LlamaGenerationSession::getReport() {
    auto timings = llama_perf_context(ctx);
    auto sampler_timings = llama_perf_sampler(smpl);

    int n_ctx_total = llama_n_ctx(ctx);
    int n_ctx_used = (int)llama_memory_seq_pos_max(llama_get_memory(ctx), 0);

    std::ostringstream report;

    report << "Session\n";
    report << "  Context: " << n_ctx_used << " / " << n_ctx_total << " tokens\n";
    report << "  Prompt tokens: " << timings.n_p_eval << "\n";
    report << "  Generated tokens: " << timings.n_eval << "\n";
    report << "\n";

    report << "Performance\n";
    report << "  Load time: " << std::fixed << std::setprecision(0) << timings.t_load_ms << " ms\n";
    if (timings.n_p_eval > 0 && timings.t_p_eval_ms > 0) {
        report << "  Prompt eval: " << timings.n_p_eval << " tokens, "
               << std::setprecision(1) << (1e3 / timings.t_p_eval_ms * timings.n_p_eval) << " t/s\n";
    }
    if (timings.n_eval > 0 && timings.t_eval_ms > 0) {
        report << "  Generation: " << timings.n_eval << " tokens, "
               << std::setprecision(1) << (1e3 / timings.t_eval_ms * timings.n_eval) << " t/s\n";
    }
    if (sampler_timings.n_sample > 0 && sampler_timings.t_sample_ms > 0) {
        report << "  Sampling: " << sampler_timings.n_sample << " tokens, "
               << std::setprecision(1) << (1e3 / sampler_timings.t_sample_ms * sampler_timings.n_sample) << " t/s\n";
    }

    return report.str();
}
