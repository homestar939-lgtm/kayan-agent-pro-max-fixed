#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <thread>
#include <mutex>
#include "llama.h"
#include "ggml.h"

#define TAG "KayanLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static std::mutex g_mutex;
static std::string g_last_error;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_kayan_x_llm_LlamaEngine_nativeInit(JNIEnv* env, jobject thiz, jstring modelPath) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model: %s", path);

    llama_backend_init();
    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap = true;
    model_params.use_mlock = false;

    g_model = llama_model_load_from_file(path, model_params);
    if (!g_model) {
        g_last_error = "Failed to load model";
        LOGE("%s", g_last_error.c_str());
        env->ReleaseStringUTFChars(modelPath, path);
        return JNI_FALSE;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 4096;
    ctx_params.n_batch = 512;
    ctx_params.n_threads = 4;
    ctx_params.n_threads_batch = 4;

    g_ctx = llama_new_context_with_model(g_model, ctx_params);
    if (!g_ctx) {
        g_last_error = "Failed to create context";
        LOGE("%s", g_last_error.c_str());
        llama_model_free(g_model);
        g_model = nullptr;
        env->ReleaseStringUTFChars(modelPath, path);
        return JNI_FALSE;
    }

    env->ReleaseStringUTFChars(modelPath, path);
    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_kayan_x_llm_LlamaEngine_nativeGenerate(
        JNIEnv* env, jobject thiz,
        jstring prompt,
        jint maxTokens,
        jfloat temperature,
        jfloat topP) {

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_ctx) {
        return env->NewStringUTF("Error: Model not loaded");
    }

    const char* prompt_c = env->GetStringUTFChars(prompt, nullptr);
    std::string full_prompt(prompt_c);
    env->ReleaseStringUTFChars(prompt, prompt_c);

    auto tokens = llama_tokenize(g_ctx, full_prompt, true, true);
    if (tokens.empty()) {
        return env->NewStringUTF("");
    }

    llama_batch batch = llama_batch_init(512, 0, 1);
    for (size_t i = 0; i < tokens.size(); i++) {
        llama_batch_add(batch, tokens[i], i, {0}, false);
    }
    batch.logits[batch.n_tokens - 1] = true;

    if (llama_decode(g_ctx, batch) != 0) {
        llama_batch_free(batch);
        return env->NewStringUTF("Error: Decode failed");
    }

    std::string result;
    int n_cur = batch.n_tokens;
    int n_decode = 0;

    while (n_decode < maxTokens) {
        auto n_vocab = llama_n_vocab(g_model);
        auto* logits = llama_get_logits_ith(g_ctx, batch.n_tokens - 1);

        llama_token new_token_id = 0;
        {
            std::vector<llama_token_data> candidates;
            candidates.reserve(n_vocab);
            for (llama_token token_id = 0; token_id < n_vocab; token_id++) {
                candidates.emplace_back(llama_token_data{token_id, logits[token_id], 0.0f});
            }
            llama_token_data_array candidates_p = {candidates.data(), candidates.size(), false};
            
            // Apply sampling
            llama_sample_temp(nullptr, &candidates_p, temperature);
            llama_sample_top_p(nullptr, &candidates_p, topP, 1);
            new_token_id = llama_sample_token(nullptr, &candidates_p);
        }

        if (llama_token_is_eog(g_model, new_token_id)) break;

        result += llama_token_to_piece(g_ctx, new_token_id);
        n_decode++;

        llama_batch_clear(batch);
        llama_batch_add(batch, new_token_id, n_cur, {0}, true);
        n_cur++;

        if (llama_decode(g_ctx, batch) != 0) break;
    }

    llama_batch_free(batch);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_kayan_x_llm_LlamaEngine_nativeFree(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    llama_backend_free();
    LOGI("Model freed");
}

JNIEXPORT jstring JNICALL
Java_com_kayan_x_llm_LlamaEngine_nativeGetLastError(JNIEnv* env, jobject thiz) {
    return env->NewStringUTF(g_last_error.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_kayan_x_llm_LlamaEngine_nativeIsLoaded(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return (g_model != nullptr && g_ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}
}
