#include "DirectCefRuntime.h"

#include <jni.h>

#include <memory>
#include <mutex>
#include <unordered_map>

namespace {
std::mutex g_mutex;
std::unordered_map<jlong, std::shared_ptr<DirectCefRuntime>> g_runtimes;
jlong g_next_handle = 1;
std::shared_ptr<DirectCefRuntime> Find(jlong handle) {
  std::lock_guard<std::mutex> lock(g_mutex);
  auto it = g_runtimes.find(handle);
  return it == g_runtimes.end() ? nullptr : it->second;
}
std::string Utf8(JNIEnv* env, jstring value) {
  if (!value) return {};
  const jchar* chars = env->GetStringChars(value, nullptr);
  if (!chars) return {};
  const int length = env->GetStringLength(value);
  const auto* wide = reinterpret_cast<const wchar_t*>(chars);
  const int utf8_length = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, wide,
                                               length, nullptr, 0, nullptr, nullptr);
  std::string out;
  if (utf8_length > 0) {
    out.resize(static_cast<std::size_t>(utf8_length));
    WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, wide, length, out.data(),
                        utf8_length, nullptr, nullptr);
  }
  env->ReleaseStringChars(value, chars);
  return out;
}
jstring JavaUtf8(JNIEnv* env, const std::string& value) {
  if (value.empty()) return env->NewStringUTF("");
  const int wide_length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS,
                                               value.data(), static_cast<int>(value.size()),
                                               nullptr, 0);
  if (wide_length <= 0) return nullptr;
  std::wstring wide(static_cast<std::size_t>(wide_length), L'\0');
  MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(),
                      static_cast<int>(value.size()), wide.data(), wide_length);
  return env->NewString(reinterpret_cast<const jchar*>(wide.data()), wide_length);
}
}

extern "C" JNIEXPORT jlong JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nCreate(
    JNIEnv* env, jclass, jstring url, jstring cache, jstring helper, jlong parent, jint width,
    jint height, jint target_hz) {
  auto runtime = DirectCefRuntime::Create(Utf8(env, url), Utf8(env, cache), Utf8(env, helper),
                                          reinterpret_cast<void*>(parent), width, height, target_hz);
  if (!runtime) return 0;
  std::lock_guard<std::mutex> lock(g_mutex);
  const jlong handle = g_next_handle++;
  g_runtimes.emplace(handle, std::shared_ptr<DirectCefRuntime>(std::move(runtime)));
  return handle;
}

extern "C" JNIEXPORT void JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nDestroy(JNIEnv*, jclass, jlong handle) {
  std::shared_ptr<DirectCefRuntime> runtime;
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_runtimes.find(handle);
    if (it == g_runtimes.end()) return;
    runtime = std::move(it->second);
    g_runtimes.erase(it);
  }
  // Destroy outside the registry lock so CEF callbacks never contend with a
  // process-global JNI mutex while CloseBrowser waits for the UI-thread drain.
  runtime.reset();
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nResize(JNIEnv*, jclass, jlong h, jint w, jint ht) { auto r=Find(h); return r&&r->Resize(w,ht); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nSetVisible(JNIEnv*, jclass, jlong h, jboolean v) { auto r=Find(h); return r&&r->SetVisible(v); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nRefreshGlContext(JNIEnv*, jclass, jlong h) { auto r=Find(h); return r&&r->RefreshGlContext(); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nRequestFrame(JNIEnv*, jclass, jlong h) { auto r=Find(h); return r&&r->RequestFrame(); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nSetFocus(JNIEnv*, jclass, jlong h, jboolean v) { auto r=Find(h); return r&&r->SetFocus(v); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nSetMouseButtons(JNIEnv*, jclass, jlong h, jint buttons) { auto r=Find(h); return r&&r->SetMouseButtons(static_cast<uint32_t>(buttons)); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nMouseMove(JNIEnv*, jclass, jlong h, jint x, jint y, jint modifiers, jboolean leave) { auto r=Find(h); return r&&r->SendMouseMove(x,y,static_cast<uint32_t>(modifiers),leave); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nMouseButton(JNIEnv*, jclass, jlong h, jint x, jint y, jint modifiers, jint button, jboolean up, jint count) { auto r=Find(h); return r&&r->SendMouseButton(x,y,static_cast<uint32_t>(modifiers),button,up,count); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nMouseWheel(JNIEnv*, jclass, jlong h, jint x, jint y, jint modifiers, jint dx, jint dy) { auto r=Find(h); return r&&r->SendMouseWheel(x,y,static_cast<uint32_t>(modifiers),dx,dy); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nKey(JNIEnv*, jclass, jlong h, jint message, jlong wparam, jlong lparam) { auto r=Find(h); return r&&r->SendKey(static_cast<uint32_t>(message),static_cast<uintptr_t>(wparam),static_cast<intptr_t>(lparam)); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nText(JNIEnv* env, jclass, jlong h, jstring value) { auto r=Find(h); if(!r||!value)return false; const jchar* chars=env->GetStringChars(value,nullptr); const jsize length=env->GetStringLength(value); std::u16string text(reinterpret_cast<const char16_t*>(chars), static_cast<std::size_t>(length)); env->ReleaseStringChars(value,chars); return r->SendText(text); }
extern "C" JNIEXPORT jobjectArray JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nPollBridgeQuery(JNIEnv* env, jclass, jlong h) {
  auto r = Find(h);
  DirectCefRuntime::BridgeQuery query;
  if (!r || !r->PollBridgeQuery(&query)) return nullptr;
  jclass string_class = env->FindClass("java/lang/String");
  if (!string_class) return nullptr;
  jobjectArray values = env->NewObjectArray(2, string_class, nullptr);
  const std::string id = std::to_string(query.id);
  env->SetObjectArrayElement(values, 0, JavaUtf8(env, id));
  env->SetObjectArrayElement(values, 1, JavaUtf8(env, query.request));
  return values;
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nCompleteBridgeQuery(
    JNIEnv* env, jclass, jlong h, jlong id, jstring response, jint error_code,
    jstring error_message) {
  auto r = Find(h);
  return r && r->CompleteBridgeQuery(static_cast<std::uint64_t>(id), Utf8(env, response),
                                      error_code, Utf8(env, error_message));
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nDeliverBridgeMessage(
    JNIEnv* env, jclass, jlong h, jlong navigation_epoch, jstring message) {
  auto r = Find(h);
  return r && message && r->DeliverBridgeMessage(
      static_cast<std::uint64_t>(navigation_epoch), Utf8(env, message));
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nBridgeNavigationEpoch(
    JNIEnv*, jclass, jlong h) {
  auto r = Find(h);
  return r ? static_cast<jlong>(r->BridgeNavigationEpoch()) : 0;
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nBeginRenderFrame(JNIEnv*, jclass, jlong h) { auto r=Find(h); return r&&r->BeginRenderFrame(); }
extern "C" JNIEXPORT void JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nEndRenderFrame(JNIEnv*, jclass, jlong h) { if(auto r=Find(h)) r->EndRenderFrame(); }
extern "C" JNIEXPORT void JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nMarkFrameDrawn(JNIEnv*, jclass, jlong h) { if(auto r=Find(h)) r->MarkFrameDrawn(); }
extern "C" JNIEXPORT jint JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nTextureId(JNIEnv*, jclass, jlong h) { auto r=Find(h); return r ? static_cast<jint>(r->TextureId()) : 0; }
extern "C" JNIEXPORT jstring JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nDiagnostics(JNIEnv* env, jclass, jlong h) { auto r=Find(h); const std::string json=r?r->DiagnosticsJson():"{\"ready\":false}"; return JavaUtf8(env, json); }
