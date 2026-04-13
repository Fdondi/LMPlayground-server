//
// Created by Andrew Druk on 22.01.2024.
//

#ifndef LMPLAYGROUND_LLAMACPP_H
#define LMPLAYGROUND_LLAMACPP_H

#include "common.h"
#include "chat.h"
#include "sampling.h"

struct SamplerParams {
    int n_ctx;
    float temperature;
    float top_p;
    float repetition_penalty;
    int top_k;
    float min_p;
    uint32_t seed;
    int32_t thinking_budget; // -1 = unlimited
};

class LlamaGenerationSession {
public:
    using ResponseCallback = std::function<void(const std::string&)>;

    LlamaGenerationSession();

    ~LlamaGenerationSession();

    void init(llama_model *model, const struct common_chat_templates *chat_tmpls, const SamplerParams &params);

    void printReport();

    // Returns: 0 = more tokens, 1 = done, 2 = tool calls detected
    int generate(const ResponseCallback& callback);

    int addMessage(const char *string, bool enableThinking);

    std::string getReport();

    void replayHistory(const std::vector<std::pair<std::string, std::string>>& history);

    // Tool calling
    void setTools(const char *toolsJson);
    std::string getToolCallsJson();
    int submitToolResults(const char *resultsJson, bool enableThinking);

private:
    void finalizeResponse();
    common_chat_params renderTemplate(bool enableThinking, bool addGenerationPrompt = true);

    const struct llama_vocab * vocab = nullptr;
    llama_context * ctx = nullptr;
    llama_sampler * smpl = nullptr;
    const struct common_chat_templates * chat_tmpls = nullptr;
    bool prev_had_thinking = false;
    bool prev_enable_thinking = false;
    std::string prev_rendered_prompt;
    std::vector<common_chat_msg> messages;
    std::vector<std::string> additional_stops;
    int prev_len = 0;
    std::vector<llama_token> prompt_tokens;
    llama_token last_token;
    llama_batch batch;
    std::string response;
    common_chat_parser_params parser_params;
    bool parser_initialized = false;
    SamplerParams sampler_params;
    bool budget_sampler_added = false;

    // Tool calling state
    std::vector<common_chat_tool> tools;
    bool tools_enabled = false;
    std::vector<common_chat_tool_call> pending_tool_calls;
    int tool_call_counter = 0;
};

class LlamaModel {
public:
    LlamaModel() = default;
    ~LlamaModel() = default;

    LlamaGenerationSession* createGenerationSession(const SamplerParams &params);
    int getContextTrainSize();
    void loadModel(const std::string &modelPath,
                   int32_t n_gpu_layers,
                   llama_progress_callback progress_callback,
                   void* progress_callback_user_data);

    uint64_t getModelSize();

    std::string getModelReport();

    bool supportsThinking();

    bool supportsToolCalling();

    void unloadModel();

private:
    llama_model *model = nullptr;
    common_chat_templates_ptr chat_tmpls;
};

#endif //LMPLAYGROUND_LLAMACPP_H
