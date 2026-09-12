#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <mutex>
#include "llama.h"

#define TAG "KayanLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static const llama_vocab* g_vocab = nullptr;
static std::mutex g_mutex;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_kayan_x_llm_LlamaEngine_nativeInit(JNIEnv* env, jobject, jstring modelPath) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model: %s", path);

    llama_backend_init();
    llama_model_params mparams = llama_model_default_params();
    g_model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!g_model) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    g_vocab = llama_model_get_vocab(g_model);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 4096;
    cparams.n_batch = 512;
    cparams.n_threads = 4;
    cparams.n_threads_batch = 4;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        // fallback for b4600 older function name
        g_ctx = llama_new_context_with_model(g_model, cparams);
    }

    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Model loaded OK");
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_kayan_x_llm_LlamaEngine_nativeGenerate(JNIEnv* env, jobject, jstring prompt, jint maxTokens, jfloat temp, jfloat topP) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_ctx || !g_vocab) {
        return env->NewStringUTF("Error: Model not loaded");
    }

    const char* p = env->GetStringUTFChars(prompt, nullptr);
    std::string promptStr(p);
    env->ReleaseStringUTFChars(prompt, p);

    // Tokenize with vocab* - b4600 API
    std::vector<llama_token> tokens = llama_tokenize(g_vocab, promptStr, true, true);
    if (tokens.empty()) {
        return env->NewStringUTF("");
    }

    // Manual batch - llama_batch_add removed in b4600
    llama_batch batch = llama_batch_init(512, 0, 1);
    for (int i = 0; i < (int)tokens.size(); ++i) {
        batch.token[i] = tokens[i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = false;
    }
    batch.logits[tokens.size() - 1] = true;
    batch.n_tokens = (int)tokens.size();

    if (llama_decode(g_ctx, batch) != 0) {
        llama_batch_free(batch);
        return env->NewStringUTF("Error: decode failed");
    }

    // New sampler API - b4600
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string out;
    int n_cur = batch.n_tokens;

    for (int i = 0; i < maxTokens; ++i) {
        llama_token tok = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(g_vocab, tok)) break;

        char buf[256];
        int n = llama_token_to_piece(g_vocab, tok, buf, sizeof(buf), 0, false);
        if (n > 0) out.append(buf, n);

        llama_batch_clear(batch);
        batch.token[0] = tok;
        batch.pos[0] = n_cur;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = true;
        batch.n_tokens = 1;
        n_cur++;

        if (llama_decode(g_ctx, batch) != 0) break;
    }

    llama_sampler_free(smpl);
    llama_batch_free(batch);
    llama_kv_cache_clear(g_ctx);

    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_kayan_x_llm_LlamaEngine_nativeFree(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr;
    llama_backend_free();
    LOGI("Freed");
}

JNIEXPORT jstring JNICALL
Java_com_kayan_x_llm_LlamaEngine_nativeGetLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF("OK");
}

JNIEXPORT jboolean JNICALL
Java_com_kayan_x_llm_LlamaEngine_nativeIsLoaded(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return (g_model && g_ctx) ? JNI_TRUE : JNI_FALSE;
}

}
