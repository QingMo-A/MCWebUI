#include "DirectCefRuntime.h"

#include <jni.h>

#include <memory>
#include <mutex>
#include <unordered_map>

namespace {
std::mutex g_mutex;
std::unordered_map<jlong, std::unique_ptr<DirectCefRuntime>> g_runtimes;
jlong g_next_handle = 1;
DirectCefRuntime* Find(jlong handle) {
  std::lock_guard<std::mutex> lock(g_mutex);
  auto it = g_runtimes.find(handle);
  return it == g_runtimes.end() ? nullptr : it->second.get();
}
std::string Utf8(JNIEnv* env, jstring value) {
  if (!value) return {};
  const char* chars = env->GetStringUTFChars(value, nullptr);
  std::string out = chars ? chars : "";
  if (chars) env->ReleaseStringUTFChars(value, chars);
  return out;
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
  g_runtimes.emplace(handle, std::move(runtime));
  return handle;
}

extern "C" JNIEXPORT void JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nDestroy(JNIEnv*, jclass, jlong handle) {
  std::unique_ptr<DirectCefRuntime> runtime;
  { std::lock_guard<std::mutex> lock(g_mutex); auto it=g_runtimes.find(handle); if(it!=g_runtimes.end()){ runtime=std::move(it->second); g_runtimes.erase(it); } }
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nResize(JNIEnv*, jclass, jlong h, jint w, jint ht) { auto* r=Find(h); return r&&r->Resize(w,ht); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nSetVisible(JNIEnv*, jclass, jlong h, jboolean v) { auto* r=Find(h); return r&&r->SetVisible(v); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nRequestFrame(JNIEnv*, jclass, jlong h) { auto* r=Find(h); return r&&r->RequestFrame(); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nSetFocus(JNIEnv*, jclass, jlong h, jboolean v) { auto* r=Find(h); return r&&r->SetFocus(v); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nMouseMove(JNIEnv*, jclass, jlong h, jint x, jint y, jint modifiers, jboolean leave) { auto* r=Find(h); return r&&r->SendMouseMove(x,y,static_cast<uint32_t>(modifiers),leave); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nMouseButton(JNIEnv*, jclass, jlong h, jint x, jint y, jint modifiers, jint button, jboolean up, jint count) { auto* r=Find(h); return r&&r->SendMouseButton(x,y,static_cast<uint32_t>(modifiers),button,up,count); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nMouseWheel(JNIEnv*, jclass, jlong h, jint x, jint y, jint modifiers, jint dx, jint dy) { auto* r=Find(h); return r&&r->SendMouseWheel(x,y,static_cast<uint32_t>(modifiers),dx,dy); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nKey(JNIEnv*, jclass, jlong h, jint message, jlong wparam, jlong lparam) { auto* r=Find(h); return r&&r->SendKey(static_cast<uint32_t>(message),static_cast<uintptr_t>(wparam),static_cast<intptr_t>(lparam)); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nText(JNIEnv* env, jclass, jlong h, jstring value) { auto* r=Find(h); if(!r||!value)return false; const jchar* chars=env->GetStringChars(value,nullptr); const jsize length=env->GetStringLength(value); std::u16string text(reinterpret_cast<const char16_t*>(chars), static_cast<std::size_t>(length)); env->ReleaseStringChars(value,chars); return r->SendText(text); }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nBeginRenderFrame(JNIEnv*, jclass, jlong h) { auto* r=Find(h); return r&&r->BeginRenderFrame(); }
extern "C" JNIEXPORT void JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nEndRenderFrame(JNIEnv*, jclass, jlong h) { if(auto* r=Find(h)) r->EndRenderFrame(); }
extern "C" JNIEXPORT jint JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nTextureId(JNIEnv*, jclass, jlong h) { auto* r=Find(h); return r ? static_cast<jint>(r->TextureId()) : 0; }
extern "C" JNIEXPORT jstring JNICALL Java_dev_qingmo_mcwebui_nativecef_DirectCefRuntime_nDiagnostics(JNIEnv* env, jclass, jlong h) { auto* r=Find(h); const std::string json=r?r->DiagnosticsJson():"{\"ready\":false}"; return env->NewStringUTF(json.c_str()); }
