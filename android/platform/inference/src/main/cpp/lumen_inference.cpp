// The JNI bridge to llama.cpp.
//
// This file is the whole native surface: load a model, generate a
// completion, unload the model. Everything above it is Kotlin.

#include <jni.h>
#include <android/log.h>

#include "llama.h"

#define LOG_TAG "LumenInference"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

llama_model* g_model = nullptr;
llama_context* g_context = nullptr;

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_dev_lumen_platform_inference_LlamaNative_isReady(JNIEnv*, jobject) {
    return g_model != nullptr && g_context != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_dev_lumen_platform_inference_LlamaNative_loadedModel(JNIEnv* env, jobject) {
    return env->NewStringUTF("");
}

JNIEXPORT jboolean JNICALL
Java_dev_lumen_platform_inference_LlamaNative_loadModel(JNIEnv* env, jobject, jstring path, jint contextSize) {
    const char* modelPath = env->GetStringUTFChars(path, nullptr);
    if (modelPath == nullptr) {
        return JNI_FALSE;
    }

    llama_backend_init();

    llama_model_params modelParams = llama_model_default_params();
    g_model = llama_model_load_from_file(modelPath, modelParams);
    env->ReleaseStringUTFChars(path, modelPath);

    if (g_model == nullptr) {
        LOGI("the model could not be loaded");
        return JNI_FALSE;
    }

    llama_context_params contextParams = llama_context_default_params();
    contextParams.n_ctx = contextSize;
    g_context = llama_new_context(g_model, contextParams);

    return g_context != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_lumen_platform_inference_LlamaNative_unloadModel(JNIEnv*, jobject) {
    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    llama_backend_free();
}

JNIEXPORT jstring JNICALL
Java_dev_lumen_platform_inference_LlamaNative_complete(
        JNIEnv* env, jobject, jstring prompt, jint maxTokens, jlong seed) {
    if (g_context == nullptr) {
        return nullptr;
    }

    const char* promptText = env->GetStringUTFChars(prompt, nullptr);
    if (promptText == nullptr) {
        return nullptr;
    }

    llama_token* tokens = llama_tokenize(g_context, promptText, -1, true, true);
    env->ReleaseStringUTFChars(prompt, nullptr);

    if (tokens == nullptr) {
        return nullptr;
    }

    const int tokenCount = -llama_n_tokens(g_context);
    llama_batch batch = llama_batch_get_one(tokens, tokenCount);

    if (llama_decode(g_context, batch) != 0) {
        free(tokens);
        return nullptr;
    }

    const int capacity = maxTokens > 0 ? maxTokens : 256;
    std::string output;
    for (int i = 0; i < capacity; ++i) {
        const int token = llama_sampler_sample(g_context, -1);
        if (token == llama_token_eos(g_model)) {
            break;
        }
        char buffer[256];
        const int length = llama_token_to_piece(g_model, token, buffer, sizeof(buffer), 0, true, nullptr);
        if (length > 0) {
            output.append(buffer, length);
        }
        llama_sampler_accept(g_context, token);
    }

    free(tokens);
    return env->NewStringUTF(output.c_str());
}

}  // extern "C"
