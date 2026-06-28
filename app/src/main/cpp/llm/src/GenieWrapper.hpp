// Ported from chatapp_android (Qualcomm Innovation Center, BSD-3-Clause)
// Adapted for local-dream: removed JNI dependency, uses std::function callback
#pragma once

#include <functional>
#include <string>

#include "GenieCommon.h"
#include "GenieDialog.h"
#include "PromptHandler.hpp"

namespace llm {

using TokenCallback = std::function<void(const std::string&)>;

class GenieWrapper {
public:
    GenieWrapper(const std::string& model_config_path,
                 const std::string& models_path,
                 const std::string& htp_config_path,
                 const std::string& tokenizer_path);
    GenieWrapper() = delete;
    GenieWrapper(const GenieWrapper&) = delete;
    GenieWrapper(GenieWrapper&&) = delete;
    GenieWrapper& operator=(const GenieWrapper&) = delete;
    GenieWrapper& operator=(GenieWrapper&&) = delete;
    ~GenieWrapper();

    // Query the model with a user prompt. Streams tokens via callback.
    // Returns the full accumulated response.
    std::string GetResponseForPrompt(const std::string& user_prompt,
                                     const TokenCallback& on_token);

    // Reset conversation state (clears history)
    void ResetDialog();

private:
    GenieDialogConfig_Handle_t m_config_handle = nullptr;
    GenieDialog_Handle_t m_dialog_handle = nullptr;
    GenieProfile_Handle_t m_profile_handle = nullptr;
    std::string m_models_path;
    AppUtils::PromptHandler prompt_handler;
};

} // namespace llm
