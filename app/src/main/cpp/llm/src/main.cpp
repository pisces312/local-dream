// LLM backend for local-dream
// Standalone HTTP server using Genie SDK for QNN/HTP LLM inference
// Port: 8082 (SD backend uses 8081)

#include <filesystem>
#include <iostream>
#include <memory>
#include <mutex>
#include <string>

#include "GenieWrapper.hpp"
#include "httplib.h"
#include "nlohmann/json.hpp"

using json = nlohmann::json;

struct LlmOptions {
    int port = 8082;
    std::string listen_address = "127.0.0.1";
    std::string model_dir;
    std::string lib_dir;
    std::string htp_config;
};

static void showHelp() {
    std::cout
        << "Usage:\n"
           "  llm_core --model_dir <dir> --lib_dir <dir> --htp_config <file> [options]\n"
           "\n"
           "Required:\n"
           "  --model_dir <dir>      Directory with genie_config.json, tokenizer.json, *.bin\n"
           "  --lib_dir <dir>        Directory with libQnnHtp.so / libQnnSystem.so\n"
           "  --htp_config <file>    HTP backend config JSON (e.g. qualcomm-snapdragon-8-elite.json)\n"
           "\n"
           "Options:\n"
           "  --port <n>             HTTP port (default 8082)\n"
           "  --listen_all           Listen on 0.0.0.0 instead of 127.0.0.1\n"
           "  --help                 Show this help\n";
}

static LlmOptions processCommandLine(int argc, char** argv) {
    LlmOptions opts;
    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg == "--help") {
            showHelp();
            std::exit(EXIT_SUCCESS);
        } else if (arg == "--model_dir" && i + 1 < argc) {
            opts.model_dir = argv[++i];
        } else if (arg == "--lib_dir" && i + 1 < argc) {
            opts.lib_dir = argv[++i];
        } else if (arg == "--htp_config" && i + 1 < argc) {
            opts.htp_config = argv[++i];
        } else if (arg == "--port" && i + 1 < argc) {
            opts.port = std::stoi(argv[++i]);
        } else if (arg == "--listen_all") {
            opts.listen_address = "0.0.0.0";
        } else {
            std::cerr << "Unknown argument: " << arg << "\n";
            showHelp();
            std::exit(EXIT_FAILURE);
        }
    }
    return opts;
}

int main(int argc, char** argv) {
    LlmOptions opts = processCommandLine(argc, argv);

    // Model is loaded on demand via /load endpoint, not at startup.
    // This allows the BackendService to start the process first, then load.
    std::unique_ptr<llm::GenieWrapper> model;
    std::mutex model_mutex;
    std::string loaded_model_dir;

    httplib::Server svr;
    svr.set_default_headers({
        {"Access-Control-Allow-Origin", "*"},
        {"Access-Control-Allow-Methods", "GET, POST, OPTIONS"},
        {"Access-Control-Allow-Headers", "Content-Type"},
        {"Access-Control-Max-Age", "86400"},
    });
    svr.Options(R"(.*)", [](const httplib::Request&, httplib::Response& res) {
        res.status = 204;
    });

    // Health check
    svr.Get("/health", [&model](const httplib::Request&, httplib::Response& res) {
        json resp = {{"status", "ok"}, {"model_loaded", model != nullptr}};
        res.set_content(resp.dump(), "application/json");
    });

    // Load model
    svr.Post("/load", [&](const httplib::Request& req, httplib::Response& res) {
        try {
            auto body = json::parse(req.body);
            std::string model_dir = body.value("model_dir", std::string());
            std::string htp_config = body.value("htp_config", opts.htp_config);

            if (model_dir.empty()) {
                res.status = 400;
                res.set_content(json{{"error", "model_dir required"}}.dump(), "application/json");
                return;
            }

            std::lock_guard<std::mutex> lock(model_mutex);

            // Already loaded this model?
            if (model && loaded_model_dir == model_dir) {
                res.set_content(json{{"status", "already_loaded"}}.dump(), "application/json");
                return;
            }

            // Unload previous if different
            model.reset();

            std::filesystem::path dir(model_dir);
            auto config_path = (dir / "genie_config.json").string();
            auto tokenizer_path = (dir / "tokenizer.json").string();

            if (!std::filesystem::exists(config_path)) {
                res.status = 400;
                res.set_content(json{{"error", "genie_config.json not found in " + model_dir}}.dump(),
                               "application/json");
                return;
            }

            std::cout << "Loading LLM model from: " << model_dir << std::endl;
            model = std::make_unique<llm::GenieWrapper>(
                config_path, model_dir, htp_config, tokenizer_path);
            loaded_model_dir = model_dir;

            std::cout << "LLM model loaded successfully" << std::endl;
            res.set_content(json{{"status", "loaded"}}.dump(), "application/json");

        } catch (const std::exception& e) {
            std::cerr << "Load failed: " << e.what() << std::endl;
            res.status = 500;
            res.set_content(json{{"error", e.what()}}.dump(), "application/json");
        }
    });

    // Chat with SSE streaming
    svr.Post("/chat", [&](const httplib::Request& req, httplib::Response& res) {
        try {
            auto body = json::parse(req.body);
            std::string prompt = body.value("prompt", std::string());

            if (prompt.empty()) {
                res.status = 400;
                res.set_content(json{{"error", "prompt required"}}.dump(), "application/json");
                return;
            }

            std::lock_guard<std::mutex> lock(model_mutex);
            if (!model) {
                res.status = 400;
                res.set_content(json{{"error", "model not loaded"}}.dump(), "application/json");
                return;
            }

            res.set_header("Content-Type", "text/event-stream");
            res.set_header("Cache-Control", "no-cache");
            res.set_header("Connection", "keep-alive");

            res.set_chunked_content_provider(
                "text/event-stream",
                [&model, prompt](intptr_t, httplib::DataSink& sink) -> bool {
                    try {
                        std::string full_response = model->GetResponseForPrompt(
                            prompt,
                            [&sink](const std::string& token) {
                                json evt = {{"type", "token"}, {"text", token}};
                                std::string data = "event: token\ndata: " + evt.dump() + "\n\n";
                                if (!sink.write(data.c_str(), data.size())) {
                                    throw std::runtime_error("Client disconnected");
                                }
                            });

                        json complete = {{"type", "complete"}, {"text", full_response}};
                        std::string data = "event: complete\ndata: " + complete.dump() + "\n\n";
                        sink.write(data.c_str(), data.size());
                        sink.done();
                        return true;

                    } catch (const std::exception& e) {
                        json err = {{"type", "error"}, {"message", e.what()}};
                        std::string data = "event: error\ndata: " + err.dump() + "\n\n";
                        sink.write(data.c_str(), data.size());
                        sink.done();
                        return false;
                    }
                });

        } catch (const std::exception& e) {
            res.status = 500;
            res.set_content(json{{"error", e.what()}}.dump(), "application/json");
        }
    });

    // Reset conversation
    svr.Post("/reset", [&](const httplib::Request&, httplib::Response& res) {
        std::lock_guard<std::mutex> lock(model_mutex);
        if (!model) {
            res.status = 400;
            res.set_content(json{{"error", "model not loaded"}}.dump(), "application/json");
            return;
        }
        model->ResetDialog();
        res.set_content(json{{"status", "reset"}}.dump(), "application/json");
    });

    // Unload model
    svr.Post("/unload", [&](const httplib::Request&, httplib::Response& res) {
        std::lock_guard<std::mutex> lock(model_mutex);
        model.reset();
        loaded_model_dir.clear();
        std::cout << "LLM model unloaded" << std::endl;
        res.set_content(json{{"status", "unloaded"}}.dump(), "application/json");
    });

    std::cout << "LLM server listening on " << opts.listen_address << ":" << opts.port << std::endl;
    svr.listen(opts.listen_address.c_str(), opts.port);

    return EXIT_SUCCESS;
}
