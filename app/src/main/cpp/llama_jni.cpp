/**
 * llama_jni.cpp  —  FINAL CONTRACT  (llama.cpp b4600, handle-based)
 *
 * ── 8 exported JNI functions ────────────────────────────────────────────────
 *
 *  initBackend          → void
 *  loadModel            → jlong  (KayanContext* opaque handle; 0 = failure)
 *  freeModel            → void
 *  cancelInference      → void   (thread-safe atomic flag)
 *  infer                → jstring (nullable; streams via TokenCallback if provided)
 *  getModelInfo         → jstring (JSON)
 *  benchmarkTokensPerSec→ jfloat
 *  detectGpuVendor      → jstring (STATIC — jclass, not jobject)
 *
 * ── b4600 API changes applied ───────────────────────────────────────────────
 *  llama_load_model_from_file   → llama_model_load_from_file
 *  llama_new_context_with_model → llama_init_from_model
 *  llama_free_model             → llama_model_free
 *  llama_tokenize(model,…)      → llama_tokenize(vocab,…)
 *  llama_token_to_piece(model,…)→ llama_token_to_piece(vocab,…)
 *  llama_token_is_eog(model,…)  → llama_vocab_is_eog(vocab,…)
 *
 * ── No EGL / No GLESv2 ──────────────────────────────────────────────────────
 *  GPU vendor detection uses Android system properties (__system_property_get)
 *  + /proc/cpuinfo fallback.  No EGL linkage required.
 *
 * ── No common/ ──────────────────────────────────────────────────────────────
 *  CMakeLists.txt sets LLAMA_BUILD_COMMON=OFF.
 *  We include only: llama, android, log.
 */

#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <ctime>
#include <cctype>
#include <cstdio>
#include <android/log.h>
#include <sys/system_properties.h>

#include "llama.h"

#define LOG_TAG "KayanLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// ─────────────────────────────────────────────────────────────────────────────
// KayanContext — one per loaded model, stored as opaque jlong handle
// ─────────────────────────────────────────────────────────────────────────────
struct KayanContext {
    llama_model*       model   = nullptr;
    llama_context*     ctx     = nullptr;
    const llama_vocab* vocab   = nullptr;   // owned by model; do NOT free separately
    std::atomic<bool>  cancel  {false};
};

static void kayan_ctx_free(KayanContext* kctx) {
    if (!kctx) return;
    if (kctx->ctx)   { llama_free(kctx->ctx);          kctx->ctx   = nullptr; }
    if (kctx->model) { llama_model_free(kctx->model);  kctx->model = nullptr; }
    kctx->vocab = nullptr;
    delete kctx;
}

// ─────────────────────────────────────────────────────────────────────────────
// GPU vendor detection — NO EGL
// ─────────────────────────────────────────────────────────────────────────────
static std::string detect_gpu_vendor_impl() {
    // Read Android system properties
    char hardware[PROP_VALUE_MAX] = {};
    char board[PROP_VALUE_MAX]    = {};
    char chipname[PROP_VALUE_MAX] = {};
    __system_property_get("ro.hardware",          hardware);
    __system_property_get("ro.board.platform",    board);
    __system_property_get("ro.hardware.chipname", chipname);

    std::string s = std::string(hardware) + " " + std::string(board) + " " + std::string(chipname);
    for (char& c : s) c = (char)tolower((unsigned char)c);

    if (s.find("adreno")!= std::string::npos) return "Adreno";
    if (s.find("qcom")  != std::string::npos) return "Adreno";
    if (s.find("msm")   != std::string::npos) return "Adreno";
    if (s.find("sdm")   != std::string::npos) return "Adreno";
    if (s.find("sm8")   != std::string::npos) return "Adreno";
    if (s.find("mali")  != std::string::npos) return "Mali";
    if (s.find("exynos")!= std::string::npos) return "Mali";
    if (s.find("gs1")   != std::string::npos) return "Mali";
    if (s.find("s5e")   != std::string::npos) return "Mali";
    if (s.find("rogue") != std::string::npos) return "PowerVR";
    if (s.find("pvr")   != std::string::npos) return "PowerVR";

    // Fallback: scan /proc/cpuinfo
    FILE* f = fopen("/proc/cpuinfo", "r");
    if (f) {
        char line[256];
        while (fgets(line, sizeof(line), f)) {
            std::string l(line);
            for (char& c : l) c = (char)tolower((unsigned char)c);
            if (l.find("adreno")  != std::string::npos) { fclose(f); return "Adreno";  }
            if (l.find("mali")    != std::string::npos) { fclose(f); return "Mali";    }
            if (l.find("powervr") != std::string::npos) { fclose(f); return "PowerVR"; }
        }
        fclose(f);
    }
    return "Unknown";
}

// ─────────────────────────────────────────────────────────────────────────────
// Helper: tokenise with b4600 vocab API
// ─────────────────────────────────────────────────────────────────────────────
static std::vector<llama_token> tokenise(
    const llama_vocab* vocab, const std::string& text, bool add_special = true)
{
    std::vector<llama_token> buf(text.size() + 128);
    int n = llama_tokenize(vocab, text.c_str(), (int32_t)text.size(),
                           buf.data(), (int32_t)buf.size(), add_special, /*parse_special=*/true);
    if (n < 0) {
        buf.resize(-n);
        n = llama_tokenize(vocab, text.c_str(), (int32_t)text.size(),
                           buf.data(), (int32_t)buf.size(), add_special, true);
    }
    if (n > 0) buf.resize(n);
    else        buf.clear();
    return buf;
}

// ─────────────────────────────────────────────────────────────────────────────
// JNI implementations
// ─────────────────────────────────────────────────────────────────────────────
extern "C" {

// ── 1. initBackend ────────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_kayan_x_engine_LlamaEngine_initBackend(JNIEnv*, jobject) {
    llama_backend_init();
    LOGI("llama_backend_init() done");
}

// ── 2. loadModel → jlong handle ──────────────────────────────────────────────
JNIEXPORT jlong JNICALL
Java_com_kayan_x_engine_LlamaEngine_loadModel(
    JNIEnv* env, jobject,
    jstring jPath, jint nCtx, jint nThreads, jint nBatch, jint nGpuLayers)
{
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    LOGI("loadModel: %s  ctx=%d threads=%d batch=%d gpu_layers=%d",
         path, nCtx, nThreads, nBatch, nGpuLayers);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = nGpuLayers;

    llama_model* model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jPath, path);

    if (!model) { LOGE("Failed to load model"); return 0L; }

    const llama_vocab* vocab = llama_model_get_vocab(model);
    if (!vocab) { LOGE("Failed to get vocab"); llama_model_free(model); return 0L; }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx      = (uint32_t)nCtx;
    cparams.n_threads  = (uint32_t)nThreads;
    cparams.n_batch    = (uint32_t)nBatch;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) { LOGE("Failed to create context"); llama_model_free(model); return 0L; }

    auto* kctx  = new KayanContext{model, ctx, vocab};
    LOGI("Model loaded OK  handle=0x%llx", (unsigned long long)(uintptr_t)kctx);
    return (jlong)(uintptr_t)kctx;
}

// ── 3. freeModel ─────────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_kayan_x_engine_LlamaEngine_freeModel(JNIEnv*, jobject, jlong handle) {
    if (!handle) return;
    kayan_ctx_free(reinterpret_cast<KayanContext*>((uintptr_t)handle));
    LOGI("Model freed");
}

// ── 4. cancelInference (thread-safe) ─────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_kayan_x_engine_LlamaEngine_cancelInference(JNIEnv*, jobject, jlong handle) {
    if (!handle) return;
    reinterpret_cast<KayanContext*>((uintptr_t)handle)->cancel.store(true, std::memory_order_relaxed);
}

// ── 5. infer → jstring (streaming via TokenCallback) ─────────────────────────
JNIEXPORT jstring JNICALL
Java_com_kayan_x_engine_LlamaEngine_infer(
    JNIEnv* env, jobject,
    jlong handle, jstring jPrompt, jint maxTokens, jfloat temperature, jobject callback)
{
    if (!handle) return env->NewStringUTF("[Error: no model]");
    auto* kctx = reinterpret_cast<KayanContext*>((uintptr_t)handle);
    kctx->cancel.store(false, std::memory_order_relaxed);

    const char* prompt_c = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(prompt_c);
    env->ReleaseStringUTFChars(jPrompt, prompt_c);

    // Tokenise
    auto tokens = tokenise(kctx->vocab, prompt);
    if (tokens.empty()) return env->NewStringUTF("[Error: tokenise failed]");
    LOGD("Prompt tokens: %d", (int)tokens.size());

    // Sampler — use temperature + dist for sampling, greedy if temp==0
    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature > 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    }

    // Decode prompt
    llama_kv_cache_clear(kctx->ctx);
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t)tokens.size());
    if (llama_decode(kctx->ctx, batch) != 0) {
        llama_sampler_free(sampler);
        return env->NewStringUTF("[Error: prompt decode failed]");
    }

    // Callback lookup (nullable)
    jclass    cbClass  = nullptr;
    jmethodID cbMethod = nullptr;
    if (callback) {
        cbClass  = env->GetObjectClass(callback);
        cbMethod = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)Z");
    }

    // Generate
    std::string result;
    result.reserve(1024);
    char piece[256];

    for (int i = 0; i < maxTokens; ++i) {
        if (kctx->cancel.load(std::memory_order_relaxed)) { LOGI("cancelled at %d", i); break; }

        llama_token tok = llama_sampler_sample(sampler, kctx->ctx, -1);
        if (llama_vocab_is_eog(kctx->vocab, tok)) break;

        int len = llama_token_to_piece(kctx->vocab, tok, piece, sizeof(piece), 0, true);
        if (len <= 0) break;
        std::string frag(piece, len);
        result += frag;

        // Stream
        if (callback && cbMethod) {
            jstring jFrag = env->NewStringUTF(frag.c_str());
            jboolean cont = env->CallBooleanMethod(callback, cbMethod, jFrag);
            env->DeleteLocalRef(jFrag);
            if (!cont || env->ExceptionCheck()) break;
        }

        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(kctx->ctx, next) != 0) break;
    }

    llama_sampler_free(sampler);
    llama_kv_cache_clear(kctx->ctx);
    LOGI("Generated %zu chars", result.size());
    return env->NewStringUTF(result.c_str());
}

// ── 6. getModelInfo → jstring (JSON) ─────────────────────────────────────────
JNIEXPORT jstring JNICALL
Java_com_kayan_x_engine_LlamaEngine_getModelInfo(JNIEnv* env, jobject, jlong handle) {
    if (!handle) return env->NewStringUTF("{}");
    auto* kctx = reinterpret_cast<KayanContext*>((uintptr_t)handle);

    int32_t n_vocab  = llama_vocab_n_tokens(kctx->vocab);
    int32_t n_ctx    = llama_n_ctx(kctx->ctx);
    int64_t n_params = llama_model_n_params(kctx->model);
    size_t  sz_bytes = llama_model_size(kctx->model);

    char buf[512];
    snprintf(buf, sizeof(buf),
        "{\"n_vocab\":%d,\"n_ctx\":%d,\"n_params\":%lld,\"model_mb\":%.1f}",
        n_vocab, n_ctx, (long long)n_params, sz_bytes / 1048576.0);
    return env->NewStringUTF(buf);
}

// ── 7. benchmarkTokensPerSec → jfloat ────────────────────────────────────────
JNIEXPORT jfloat JNICALL
Java_com_kayan_x_engine_LlamaEngine_benchmarkTokensPerSec(
    JNIEnv* env, jobject, jlong handle, jstring jPrompt, jint nTokens)
{
    if (!handle) return 0.0f;
    auto* kctx = reinterpret_cast<KayanContext*>((uintptr_t)handle);

    const char* prompt_c = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(prompt_c);
    env->ReleaseStringUTFChars(jPrompt, prompt_c);

    auto tokens = tokenise(kctx->vocab, prompt);
    if (tokens.empty()) return 0.0f;

    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    llama_kv_cache_clear(kctx->ctx);
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t)tokens.size());
    if (llama_decode(kctx->ctx, batch) != 0) { llama_sampler_free(sampler); return 0.0f; }

    struct timespec t0, t1;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    int generated = 0;
    for (int i = 0; i < nTokens; ++i) {
        llama_token tok = llama_sampler_sample(sampler, kctx->ctx, -1);
        if (llama_vocab_is_eog(kctx->vocab, tok)) break;
        llama_batch nb = llama_batch_get_one(&tok, 1);
        if (llama_decode(kctx->ctx, nb) != 0) break;
        ++generated;
    }
    clock_gettime(CLOCK_MONOTONIC, &t1);

    llama_sampler_free(sampler);
    llama_kv_cache_clear(kctx->ctx);

    double elapsed = (t1.tv_sec - t0.tv_sec) + (t1.tv_nsec - t0.tv_nsec) * 1e-9;
    return (generated > 0 && elapsed > 0.0) ? (float)(generated / elapsed) : 0.0f;
}

// ── 8. detectGpuVendor → jstring (STATIC — jclass, not jobject) ──────────────
JNIEXPORT jstring JNICALL
Java_com_kayan_x_engine_LlamaEngine_detectGpuVendor(JNIEnv* env, jclass) {
    return env->NewStringUTF(detect_gpu_vendor_impl().c_str());
}

} // extern "C"
