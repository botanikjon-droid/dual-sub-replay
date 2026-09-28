// JNI bridge between BergamotNative.kt and Mozilla's Bergamot translator.
// One BlockingService serves every model; Kotlin serialises calls with a mutex.

#include <jni.h>

#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "translator/parser.h"
#include "translator/response_options.h"
#include "translator/service.h"
#include "translator/translation_model.h"

namespace {

using marian::bergamot::BlockingService;
using marian::bergamot::Response;
using marian::bergamot::ResponseOptions;
using marian::bergamot::TranslationModel;

BlockingService &service() {
  static BlockingService instance{BlockingService::Config{}};
  return instance;
}

std::shared_ptr<TranslationModel> &model(jlong handle) {
  return *reinterpret_cast<std::shared_ptr<TranslationModel> *>(handle);
}

std::string toString(JNIEnv *env, jstring value) {
  const char *chars = env->GetStringUTFChars(value, nullptr);
  std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

std::vector<std::string> toStrings(JNIEnv *env, jobjectArray values) {
  jsize count = env->GetArrayLength(values);
  std::vector<std::string> result;
  result.reserve(count);
  for (jsize i = 0; i < count; ++i) {
    auto value = static_cast<jstring>(env->GetObjectArrayElement(values, i));
    result.push_back(toString(env, value));
    env->DeleteLocalRef(value);
  }
  return result;
}

jobjectArray toJava(JNIEnv *env, const std::vector<Response> &responses) {
  jclass stringClass = env->FindClass("java/lang/String");
  jobjectArray result = env->NewObjectArray(static_cast<jsize>(responses.size()), stringClass, nullptr);
  for (size_t i = 0; i < responses.size(); ++i) {
    jstring text = env->NewStringUTF(responses[i].target.text.c_str());
    env->SetObjectArrayElement(result, static_cast<jsize>(i), text);
    env->DeleteLocalRef(text);
  }
  return result;
}

void throwJava(JNIEnv *env, const char *message) {
  env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_kienhoang_dualsubreplay_translation_BergamotNative_loadModel(JNIEnv *env, jobject, jstring config) {
  try {
    auto options = marian::bergamot::parseOptionsFromString(toString(env, config));
    auto loaded = std::make_shared<TranslationModel>(options);
    return reinterpret_cast<jlong>(new std::shared_ptr<TranslationModel>(std::move(loaded)));
  } catch (const std::exception &error) {
    throwJava(env, error.what());
    return 0;
  }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_kienhoang_dualsubreplay_translation_BergamotNative_translate(JNIEnv *env, jobject, jlong handle,
                                                                        jobjectArray texts) {
  try {
    auto sources = toStrings(env, texts);
    std::vector<ResponseOptions> options(sources.size());
    return toJava(env, service().translateMultiple(model(handle), std::move(sources), options));
  } catch (const std::exception &error) {
    throwJava(env, error.what());
    return nullptr;
  }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_kienhoang_dualsubreplay_translation_BergamotNative_pivot(JNIEnv *env, jobject, jlong first, jlong second,
                                                                    jobjectArray texts) {
  try {
    auto sources = toStrings(env, texts);
    std::vector<ResponseOptions> options(sources.size());
    return toJava(env, service().pivotMultiple(model(first), model(second), std::move(sources), options));
  } catch (const std::exception &error) {
    throwJava(env, error.what());
    return nullptr;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_kienhoang_dualsubreplay_translation_BergamotNative_releaseModel(JNIEnv *, jobject, jlong handle) {
  delete reinterpret_cast<std::shared_ptr<TranslationModel> *>(handle);
}
