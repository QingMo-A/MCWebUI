#include <windows.h>

#include "DirectCefApp.h"
#include "include/cef_app.h"

int main(int argc, char** argv) {
  (void)argc;
  (void)argv;
  CefMainArgs args(GetModuleHandleW(nullptr));
  return CefExecuteProcess(args, CreateDirectCefApp(), nullptr);
}
