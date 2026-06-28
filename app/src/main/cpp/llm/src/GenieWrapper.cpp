// Ported from chatapp_android (Qualcomm Innovation Center, BSD-3-Clause)
// Adapted for local-dream: removed JNI, uses std::function callback for streaming

#include "GenieWrapper.hpp"
#include "nlohmann/json.hpp"

#include <android/log.h>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>

namespace llm {

namespace {

struct CallbackData {
    TokenCallback on_token;
    std::string data;
};

void GenieCallBack(const char* response_back,
                   const GenieDialog_SentenceCode_t sentence_code,
                   const void* user_data) {
    auto* cb = static_cast<CallbackData*>(const_cast<void*>(user_data));
    cb->data.append(response_back);
    if (cb->on_token) {
        cb->on_token(response_back);
    }
}

std::string LoadModelConfig(const std::string& model_config_path,
                            const std::string& models_path,
                            const std::string& htp_model_config_path,
                            const std::string& tokenizer_path) {
    if (!std::filesystem::exists(model_config_path)) {
        throw std::runtime_error("Genie config file not found: " + model_config_path);
    }

    std::string content;
    std::getline(std::ifstream(model_config_path), content, '\0');

    using namespace nlohmann;
    json config = json::parse(content);

    config["dialog"]["tokenizer"]["path"] = tokenizer_path;
    config["dialog"]["engine"]["backend"]["extensions"] = htp_model_config_path;

    for (auto& bin : config["dialog"]["engine"]["model"]["binary"]["ctx-bins"])
        bin = models_path + "/" + bin.get<std::string>();

    return config.dump();
}

} // namespace

GenieWrapper::GenieWrapper(const std::string& model_config_path,
                           const std::string& models_path,
                           const std::string& htp_config_path,
                           const std::string& tokenizer_path)
    : m_models_path(models_path), prompt_handler(models_path) {
    std::string config = LoadModelConfig(model_config_path, models_path,
                                         htp_config_path, tokenizer_path);

    if (GENIE_STATUS_SUCCESS != GenieDialogConfig_createFromJson(config.c_str(), &m_config_handle)) {
        throw std::runtime_error("Failed to create Genie Dialog config.");
    }

    if (GENIE_STATUS_SUCCESS != GenieProfile_create(nullptr, &m_profile_handle)) {
        throw std::runtime_error("Failed to create Genie profile handler.");
    }

    if (GENIE_STATUS_SUCCESS != GenieDialogConfig_bindProfiler(m_config_handle, m_profile_handle)) {
        throw std::runtime_error("Failed to bind Genie profiler to dialog config.");
    }

    if (GENIE_STATUS_SUCCESS != GenieDialog_create(m_config_handle, &m_dialog_handle)) {
        throw std::runtime_error("Failed to create Genie Dialog.");
    }

    __android_log_print(ANDROID_LOG_INFO, "LLM", "GenieWrapper initialized successfully");
}

GenieWrapper::~GenieWrapper() {
    if (m_dialog_handle != nullptr) {
        GenieDialog_free(m_dialog_handle);
    }
    if (m_profile_handle != nullptr) {
        GenieProfile_free(m_profile_handle);
    }
    if (m_config_handle != nullptr) {
        GenieDialogConfig_free(m_config_handle);
    }
}

std::string GenieWrapper::GetResponseForPrompt(const std::string& user_prompt,
                                               const TokenCallback& on_token) {
    CallbackData cb_data{on_token, ""};

    std::string tagged_prompt = prompt_handler.GetPromptWithTag(user_prompt);

    if (GENIE_STATUS_SUCCESS != GenieDialog_query(
            m_dialog_handle, tagged_prompt.c_str(),
            GENIE_DIALOG_SENTENCE_COMPLETE,
            GenieCallBack, &cb_data)) {
        __android_log_print(ANDROID_LOG_ERROR, "LLM", "Failed to get response from Genie");
    }

    // If empty response, reset and retry once
    if (cb_data.data.empty()) {
        __android_log_print(ANDROID_LOG_WARN, "LLM", "Empty response, resetting dialog");
        if (GENIE_STATUS_SUCCESS != GenieDialog_reset(m_dialog_handle)) {
            throw std::runtime_error("Failed to reset Genie Dialog.");
        }
        // Re-init prompt handler for fresh start
        prompt_handler = AppUtils::PromptHandler(m_models_path);

        cb_data.data.clear();
        std::string retry_prompt = prompt_handler.GetPromptWithTag(user_prompt);
        if (GENIE_STATUS_SUCCESS != GenieDialog_query(
                m_dialog_handle, retry_prompt.c_str(),
                GENIE_DIALOG_SENTENCE_COMPLETE,
                GenieCallBack, &cb_data)) {
            throw std::runtime_error("Failed to get response after reset.");
        }
    }

    // Log profiling data
    char* rawJson = nullptr;
    const Genie_AllocCallback_t alloc_callback(
        [](size_t size, const char** out) { *out = static_cast<char*>(std::malloc(size)); });

    auto profileStatus = GenieProfile_getJsonData(m_profile_handle, alloc_callback,
                                                  (const char**)&rawJson);
    std::unique_ptr<char, decltype(&std::free)> jsonData(rawJson, &std::free);

    if (GENIE_STATUS_SUCCESS == profileStatus && jsonData) {
        using namespace nlohmann;
        json j = json::parse(jsonData.get());
        for (const auto& component : j["components"]) {
            for (const auto& event : component["events"]) {
                if (event.contains("time-to-first-token")) {
                    auto ttft = event["time-to-first-token"]["value"].get<int>();
                    __android_log_print(ANDROID_LOG_INFO, "LLM", "TTFT: %d ms", ttft);
                }
                if (event.contains("token-generation-rate")) {
                    auto tps = event["token-generation-rate"]["value"].get<int>();
                    __android_log_print(ANDROID_LOG_INFO, "LLM", "TPS: %d tok/s", tps);
                }
            }
        }
    }

    return cb_data.data;
}

void GenieWrapper::ResetDialog() {
    if (m_dialog_handle != nullptr) {
        if (GENIE_STATUS_SUCCESS != GenieDialog_reset(m_dialog_handle)) {
            __android_log_print(ANDROID_LOG_ERROR, "LLM", "Failed to reset Genie Dialog");
        }
    }
    prompt_handler = AppUtils::PromptHandler(m_models_path);
}

} // namespace llm
