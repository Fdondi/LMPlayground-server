//
// Created by Andrew Druk on 22.01.2024.
//

#ifndef LMPLAYGROUND_LLAMACPP_H
#define LMPLAYGROUND_LLAMACPP_H

#include "common.h"
#include "chat.h"
#include "sampling.h"

class LlamaGenerationSession {
public:
    using ResponseCallback = std::function<void(const std::string&)>;

    LlamaGenerationSession();

    ~LlamaGenerationSession();

    void init(llama_model *model, const struct common_chat_templates *chat_tmpls);

    void printReport();

    int generate(const ResponseCallback& callback);

    int addMessage(const char *string, bool enableThinking);

    std::string getReport();

private:
    void finalizeResponse();
    const struct llama_vocab * vocab = nullptr;
    llama_context * ctx = nullptr;
    llama_sampler * smpl = nullptr;
    const struct common_chat_templates * chat_tmpls = nullptr;
    bool prev_had_thinking = false;
    std::vector<common_chat_msg> messages;
    std::vector<std::string> additional_stops;
    int prev_len = 0;
    std::vector<llama_token> prompt_tokens;
    llama_token last_token;
    llama_batch batch;
    std::string response;
};

class LlamaModel {
public:
    LlamaModel() = default;
    ~LlamaModel() = default;

    LlamaGenerationSession* createGenerationSession();
    void loadModel(const std::string &modelPath,
                   int32_t n_gpu_layers,
                   llama_progress_callback progress_callback,
                   void* progress_callback_user_data);

    uint64_t getModelSize();

    bool supportsThinking();

    void unloadModel();

private:
    llama_model *model = nullptr;
    common_chat_templates_ptr chat_tmpls;
};

#endif //LMPLAYGROUND_LLAMACPP_H
