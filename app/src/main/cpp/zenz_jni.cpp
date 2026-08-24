#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include "llama.h"

#define LOG_TAG "TransformerIME-Zenz"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::mutex mutex;
    float last_margin = 0.0f;
    long last_latency_ms = 0;
    uint64_t params = 0;

    void clear() {
        if (ctx) { llama_free(ctx); ctx = nullptr; }
        if (model) { llama_model_free(model); model = nullptr; }
        vocab = nullptr;
        params = 0;
        last_margin = 0.0f;
        last_latency_ms = 0;
    }
};

Engine g_engines[2];
std::once_flag g_backend_once;

void ensure_backend() {
    std::call_once(g_backend_once, [] {
        llama_backend_init();
        LOGI("llama backend initialized");
    });
}

int thread_count() {
    const unsigned int hw = std::thread::hardware_concurrency();
    const int available = hw > 2 ? static_cast<int>(hw) - 2 : 2;
    return std::clamp(available, 2, 6);
}

void append_utf8(std::string & out, uint32_t cp) {
    if (cp <= 0x7F) {
        out.push_back(static_cast<char>(cp));
    } else if (cp <= 0x7FF) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp <= 0xFFFF) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

// JNI's GetStringUTFChars/NewStringUTF use Modified UTF-8, not ordinary UTF-8.
// llama.cpp consumes and produces ordinary UTF-8 byte streams, so crossing the JNI
// boundary through Modified UTF-8 is unsafe for supplementary characters and for a
// generation that stops in the middle of a multi-byte token piece.
std::string jstring_to_utf8(JNIEnv * env, jstring value) {
    if (!value) return {};
    const jsize length = env->GetStringLength(value);
    const jchar * chars = env->GetStringChars(value, nullptr);
    if (!chars) return {};

    std::string out;
    out.reserve(static_cast<size_t>(length) * 3);
    for (jsize i = 0; i < length; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF) {
            if (i + 1 < length) {
                const uint32_t low = chars[i + 1];
                if (low >= 0xDC00 && low <= 0xDFFF) {
                    cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                    ++i;
                } else {
                    cp = 0xFFFD;
                }
            } else {
                cp = 0xFFFD;
            }
        } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }
        append_utf8(out, cp);
    }
    env->ReleaseStringChars(value, chars);
    return out;
}

bool continuation(uint8_t b) {
    return (b & 0xC0) == 0x80;
}

// Lossy but crash-proof UTF-8 -> Java UTF-16 conversion.
// A truncated tail is dropped because llama token pieces may stop mid-codepoint when
// max_tokens is reached. Invalid bytes inside the stream become U+FFFD. This is done
// before JNI sees the data, so CheckJNI can never abort on malformed Modified UTF-8.
jstring utf8_to_jstring(JNIEnv * env, const std::string & text) {
    std::vector<jchar> utf16;
    utf16.reserve(text.size());
    bool repaired = false;
    bool dropped_tail = false;

    size_t i = 0;
    while (i < text.size()) {
        const uint8_t b0 = static_cast<uint8_t>(text[i]);
        uint32_t cp = 0;
        size_t need = 0;

        if (b0 <= 0x7F) {
            cp = b0;
            need = 1;
        } else if (b0 >= 0xC2 && b0 <= 0xDF) {
            need = 2;
        } else if (b0 >= 0xE0 && b0 <= 0xEF) {
            need = 3;
        } else if (b0 >= 0xF0 && b0 <= 0xF4) {
            need = 4;
        } else {
            utf16.push_back(static_cast<jchar>(0xFFFD));
            repaired = true;
            ++i;
            continue;
        }

        if (i + need > text.size()) {
            dropped_tail = true;
            break;
        }

        bool valid = true;
        if (need >= 2 && !continuation(static_cast<uint8_t>(text[i + 1]))) valid = false;
        if (need >= 3 && !continuation(static_cast<uint8_t>(text[i + 2]))) valid = false;
        if (need >= 4 && !continuation(static_cast<uint8_t>(text[i + 3]))) valid = false;

        if (valid && need == 2) {
            cp = ((b0 & 0x1F) << 6) |
                 (static_cast<uint8_t>(text[i + 1]) & 0x3F);
        } else if (valid && need == 3) {
            const uint8_t b1 = static_cast<uint8_t>(text[i + 1]);
            if ((b0 == 0xE0 && b1 < 0xA0) || (b0 == 0xED && b1 >= 0xA0)) {
                valid = false;
            } else {
                cp = ((b0 & 0x0F) << 12) |
                     ((b1 & 0x3F) << 6) |
                     (static_cast<uint8_t>(text[i + 2]) & 0x3F);
            }
        } else if (valid && need == 4) {
            const uint8_t b1 = static_cast<uint8_t>(text[i + 1]);
            if ((b0 == 0xF0 && b1 < 0x90) || (b0 == 0xF4 && b1 >= 0x90)) {
                valid = false;
            } else {
                cp = ((b0 & 0x07) << 18) |
                     ((b1 & 0x3F) << 12) |
                     ((static_cast<uint8_t>(text[i + 2]) & 0x3F) << 6) |
                     (static_cast<uint8_t>(text[i + 3]) & 0x3F);
            }
        }

        if (!valid || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
            utf16.push_back(static_cast<jchar>(0xFFFD));
            repaired = true;
            ++i;
            continue;
        }

        if (cp <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
        i += need;
    }

    if (repaired || dropped_tail) {
        LOGW("sanitized model UTF-8 before JNI (repaired=%d dropped_tail=%d bytes=%zu)",
             repaired ? 1 : 0, dropped_tail ? 1 : 0, text.size());
    }

    const jchar empty = 0;
    return env->NewString(utf16.empty() ? &empty : utf16.data(),
                          static_cast<jsize>(utf16.size()));
}

std::vector<llama_token> tokenize(const Engine & e, const std::string & text, bool add_special = true) {
    if (!e.vocab) return {};
    int32_t capacity = std::max<int32_t>(32, static_cast<int32_t>(text.size()) + 16);
    std::vector<llama_token> result(capacity);
    int32_t count = llama_tokenize(e.vocab, text.c_str(), static_cast<int32_t>(text.size()),
                                   result.data(), capacity, add_special, true);
    if (count < 0) {
        capacity = -count;
        result.resize(capacity);
        count = llama_tokenize(e.vocab, text.c_str(), static_cast<int32_t>(text.size()),
                               result.data(), capacity, add_special, true);
    }
    if (count <= 0) return {};
    result.resize(count);
    return result;
}

std::string token_piece(const Engine & e, llama_token token) {
    char small[128];
    int32_t n = llama_token_to_piece(e.vocab, token, small, sizeof(small), 0, false);
    if (n >= 0) return std::string(small, small + n);
    std::vector<char> buffer(static_cast<size_t>(-n));
    n = llama_token_to_piece(e.vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, false);
    return n > 0 ? std::string(buffer.data(), buffer.data() + n) : std::string();
}

struct Generation {
    std::string text;
    long latency_ms = 0;
    float margin = 0.0f;
};

struct TokenScore {
    llama_token token = 0;
    float logit = -INFINITY;
};

struct GenerationSet {
    std::vector<Generation> items;
    long latency_ms = 0;
};

bool decode_prompt(Engine & e, const std::vector<llama_token> & prompt_tokens) {
    if (prompt_tokens.empty()) return false;
    llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(prompt_tokens.data()),
                                            static_cast<int32_t>(prompt_tokens.size()));
    if (llama_decode(e.ctx, batch) != 0) {
        LOGE("prompt decode failed");
        return false;
    }
    return true;
}

std::vector<TokenScore> top_tokens(const Engine & e, float * logits, int count, bool exclude_eog) {
    std::vector<TokenScore> top;
    if (!logits || !e.vocab || count <= 0) return top;
    const int32_t n_vocab = llama_vocab_n_tokens(e.vocab);
    top.reserve(static_cast<size_t>(count));
    for (int32_t id = 0; id < n_vocab; ++id) {
        const llama_token token = static_cast<llama_token>(id);
        if (exclude_eog && llama_vocab_is_eog(e.vocab, token)) continue;
        const float value = logits[id];
        if (!std::isfinite(value)) continue;
        auto it = std::lower_bound(top.begin(), top.end(), value,
                                   [](const TokenScore & item, float v) { return item.logit > v; });
        if (static_cast<int>(top.size()) < count || it != top.end()) {
            top.insert(it, TokenScore{token, value});
            if (static_cast<int>(top.size()) > count) top.pop_back();
        }
    }
    return top;
}

Generation continue_branch(Engine & e, llama_token first_token, int max_tokens, float initial_margin) {
    Generation out;
    const auto started = std::chrono::steady_clock::now();
    if (max_tokens <= 0 || llama_vocab_is_eog(e.vocab, first_token)) return out;
    const std::string first_piece = token_piece(e, first_token);
    if (first_piece.empty()) return out;
    out.text += first_piece;

    float margin_sum = std::max(0.0f, initial_margin);
    int margin_count = initial_margin > 0.0f ? 1 : 0;
    llama_token next = first_token;
    llama_batch first_batch = llama_batch_get_one(&next, 1);
    if (llama_decode(e.ctx, first_batch) != 0) {
        LOGE("forced token decode failed");
        return out;
    }

    for (int step = 1; step < max_tokens; ++step) {
        float * logits = llama_get_logits_ith(e.ctx, -1);
        if (!logits) break;
        const auto best = top_tokens(e, logits, 2, false);
        if (best.empty()) break;
        if (best.size() > 1) {
            margin_sum += best[0].logit - best[1].logit;
            margin_count++;
        }
        const llama_token token = best[0].token;
        if (llama_vocab_is_eog(e.vocab, token)) break;
        const std::string piece = token_piece(e, token);
        if (piece.empty()) break;
        out.text += piece;
        llama_token greedy = token;
        llama_batch next_batch = llama_batch_get_one(&greedy, 1);
        if (llama_decode(e.ctx, next_batch) != 0) {
            LOGE("token decode failed at step %d", step);
            break;
        }
    }

    const auto ended = std::chrono::steady_clock::now();
    out.latency_ms = std::chrono::duration_cast<std::chrono::milliseconds>(ended - started).count();
    out.margin = margin_count > 0 ? margin_sum / static_cast<float>(margin_count) : 0.0f;
    return out;
}

GenerationSet multi_generate(Engine & e, const std::string & prompt, int max_tokens, int branches) {
    GenerationSet result;
    if (!e.model || !e.ctx || !e.vocab || max_tokens <= 0 || branches <= 0) return result;

    const auto all_started = std::chrono::steady_clock::now();
    llama_kv_cache_clear(e.ctx);
    auto prompt_tokens = tokenize(e, prompt, true);
    if (prompt_tokens.empty()) return result;

    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(e.ctx));
    if (static_cast<int32_t>(prompt_tokens.size()) + max_tokens + 2 >= n_ctx) {
        const size_t keep = std::max<size_t>(1, n_ctx - max_tokens - 4);
        if (prompt_tokens.size() > keep) prompt_tokens.erase(prompt_tokens.begin(), prompt_tokens.end() - keep);
    }

    if (!decode_prompt(e, prompt_tokens)) {
        llama_kv_cache_clear(e.ctx);
        return result;
    }

    float * first_logits = llama_get_logits_ith(e.ctx, -1);
    const auto first_choices = top_tokens(e, first_logits, std::clamp(branches, 1, 12), true);
    if (first_choices.empty()) {
        llama_kv_cache_clear(e.ctx);
        return result;
    }

    result.items.reserve(first_choices.size());
    for (size_t i = 0; i < first_choices.size(); ++i) {
        if (i > 0) {
            llama_kv_cache_clear(e.ctx);
            if (!decode_prompt(e, prompt_tokens)) break;
        }
        float initial_margin = 0.0f;
        if (i == 0 && first_choices.size() > 1) {
            initial_margin = first_choices[0].logit - first_choices[1].logit;
        } else if (i + 1 < first_choices.size()) {
            initial_margin = std::max(0.0f, first_choices[i].logit - first_choices[i + 1].logit);
        }
        Generation branch = continue_branch(e, first_choices[i].token, max_tokens, initial_margin);
        if (!branch.text.empty()) result.items.push_back(std::move(branch));
    }

    llama_kv_cache_clear(e.ctx);
    const auto all_ended = std::chrono::steady_clock::now();
    result.latency_ms = std::chrono::duration_cast<std::chrono::milliseconds>(all_ended - all_started).count();
    e.last_latency_ms = result.latency_ms;
    e.last_margin = result.items.empty() ? 0.0f : result.items.front().margin;
    return result;
}

jobjectArray make_generation_result(JNIEnv * env, const Generation & result) {
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(3, string_class, nullptr);
    env->SetObjectArrayElement(array, 0, utf8_to_jstring(env, result.text));
    env->SetObjectArrayElement(array, 1, utf8_to_jstring(env, std::to_string(result.latency_ms)));
    env->SetObjectArrayElement(array, 2, utf8_to_jstring(env, std::to_string(result.margin)));
    return array;
}

jobjectArray make_generation_set_result(JNIEnv * env, const GenerationSet & result) {
    jclass string_class = env->FindClass("java/lang/String");
    const jsize size = static_cast<jsize>(1 + result.items.size() * 2);
    jobjectArray array = env->NewObjectArray(size, string_class, nullptr);
    env->SetObjectArrayElement(array, 0, utf8_to_jstring(env, std::to_string(result.latency_ms)));
    jsize pos = 1;
    for (const auto & item : result.items) {
        env->SetObjectArrayElement(array, pos++, utf8_to_jstring(env, item.text));
        env->SetObjectArrayElement(array, pos++, utf8_to_jstring(env, std::to_string(item.margin)));
    }
    return array;
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_ikegami_transformerime_model_ZenzaiNative_nativeInit(JNIEnv *, jobject) {
    ensure_backend();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ikegami_transformerime_model_ZenzaiNative_nativeLoadModel(JNIEnv * env, jobject, jint index, jstring jpath) {
    ensure_backend();
    if (index < 0 || index > 1 || !jpath) return 0;
    Engine & e = g_engines[index];
    std::lock_guard<std::mutex> guard(e.mutex);
    e.clear();

    const std::string path = jstring_to_utf8(env, jpath);
    if (path.empty()) return 0;
    llama_model_params mp = llama_model_default_params();
    mp.use_mmap = true;
    mp.use_mlock = false;
    mp.n_gpu_layers = 0;
    e.model = llama_model_load_from_file(path.c_str(), mp);
    if (!e.model) {
        LOGE("failed to load model %d", index);
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 256;
    cp.n_batch = 256;
    cp.n_ubatch = 256;
    cp.n_threads = thread_count();
    cp.n_threads_batch = cp.n_threads;
    e.ctx = llama_init_from_model(e.model, cp);
    if (!e.ctx) {
        LOGE("failed to create context %d", index);
        e.clear();
        return 0;
    }
    e.vocab = llama_model_get_vocab(e.model);
    e.params = llama_model_n_params(e.model);
    LOGI("loaded model %d params=%llu threads=%d", index,
         static_cast<unsigned long long>(e.params), cp.n_threads);
    return static_cast<jlong>(e.params);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_ikegami_transformerime_model_ZenzaiNative_nativeGenerate(JNIEnv * env, jobject, jint index, jstring jprompt, jint max_tokens) {
    if (index < 0 || index > 1 || !jprompt) return make_generation_result(env, {});
    Engine & e = g_engines[index];
    std::lock_guard<std::mutex> guard(e.mutex);
    const std::string prompt = jstring_to_utf8(env, jprompt);
    if (prompt.empty()) return make_generation_result(env, {});
    const GenerationSet set = multi_generate(e, prompt, std::clamp<int>(max_tokens, 1, 64), 1);
    Generation first = set.items.empty() ? Generation{} : set.items.front();
    first.latency_ms = set.latency_ms;
    return make_generation_result(env, first);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_ikegami_transformerime_model_ZenzaiNative_nativeGenerateCandidates(JNIEnv * env, jobject, jint index, jstring jprompt, jint max_tokens, jint branches) {
    if (index < 0 || index > 1 || !jprompt) return make_generation_set_result(env, {});
    Engine & e = g_engines[index];
    std::lock_guard<std::mutex> guard(e.mutex);
    const std::string prompt = jstring_to_utf8(env, jprompt);
    if (prompt.empty()) return make_generation_set_result(env, {});
    const GenerationSet result = multi_generate(e, prompt,
        std::clamp<int>(max_tokens, 1, 64), std::clamp<int>(branches, 1, 12));
    return make_generation_set_result(env, result);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ikegami_transformerime_model_ZenzaiNative_nativeParameterCount(JNIEnv *, jobject, jint index) {
    if (index < 0 || index > 1) return 0;
    return static_cast<jlong>(g_engines[index].params);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ikegami_transformerime_model_ZenzaiNative_nativeFree(JNIEnv *, jobject) {
    for (auto & e : g_engines) {
        std::lock_guard<std::mutex> guard(e.mutex);
        e.clear();
    }
}
