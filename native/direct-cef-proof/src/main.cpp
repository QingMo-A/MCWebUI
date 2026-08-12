#include <windows.h>

#include <filesystem>
#include <iostream>
#include <string>
#include <cstdlib>

#include "include/cef_app.h"
#include "include/cef_command_line.h"
#include "include/cef_sandbox_win.h"

#include "ProofApp.h"

namespace {
std::string FileUrlFromPath(const std::string& input) {
  std::filesystem::path path = std::filesystem::absolute(std::filesystem::path(input));
  std::string value = path.generic_string();
  if (value.size() >= 2 && value[1] == ':') return "file:///" + value;
  return "file://" + value;
}

std::string ReadSwitch(CefRefPtr<CefCommandLine> command_line,
                       const char* name,
                       const std::string& fallback) {
  const std::string value = command_line->GetSwitchValue(name).ToString();
  return value.empty() ? fallback : value;
}

int ReadIntSwitch(CefRefPtr<CefCommandLine> command_line, const char* name, int fallback) {
  const std::string value = ReadSwitch(command_line, name, std::to_string(fallback));
  char* end = nullptr;
  const long parsed = std::strtol(value.c_str(), &end, 10);
  return end != value.c_str() && *end == '\0' ? static_cast<int>(parsed) : fallback;
}
}

int main(int argc, char* argv[]) {
  UNREFERENCED_PARAMETER(argc);
  UNREFERENCED_PARAMETER(argv);
  HINSTANCE hInstance = GetModuleHandle(nullptr);
  CefMainArgs main_args(hInstance);
  int exit_code = CefExecuteProcess(main_args, nullptr, nullptr);
  if (exit_code >= 0) return exit_code;

  CefRefPtr<CefCommandLine> command_line = CefCommandLine::CreateCommandLine();
  command_line->InitFromString(::GetCommandLineW());
  const std::string dist = ReadSwitch(command_line, "dist", "frontend/playground/dist/index.html");
  const std::string url = ReadSwitch(command_line, "url", FileUrlFromPath(dist));
  const std::string mode = ReadSwitch(command_line, "mode", "backend-default");
  const int width = ReadIntSwitch(command_line, "width", 1280);
  const int height = ReadIntSwitch(command_line, "height", 720);
  const int target_hz = ReadIntSwitch(command_line, "target-hz", 30);
  const int duration_ms = ReadIntSwitch(command_line, "duration-ms", 5000);
  const bool accelerated = command_line->HasSwitch("accelerated");
  const bool simulator_mailbox = command_line->HasSwitch("simulator-mailbox");
  const bool simulator_coupled = command_line->HasSwitch("simulator-coupled") ||
      (command_line->HasSwitch("simulator") && !simulator_mailbox);
  const bool simulator = simulator_coupled || simulator_mailbox;
  const std::string present_mode = ReadSwitch(command_line, "present-mode", "vsync");
  const bool animate = !command_line->HasSwitch("idle");
  const std::string output = ReadSwitch(command_line, "output", "direct-cef-proof-result.json");

  wchar_t temporary_path[MAX_PATH] = {};
  if (GetTempPathW(MAX_PATH, temporary_path) == 0) {
    std::cerr << "[direct-cef-proof] GetTempPathW failed" << std::endl;
    return 2;
  }
  const std::filesystem::path proof_runtime =
      std::filesystem::path(temporary_path) / L"mcwebui-direct-cef-runtime";

  CefSettings settings;
  settings.no_sandbox = true;
  settings.windowless_rendering_enabled = true;
  settings.multi_threaded_message_loop = true;
  settings.log_severity = LOGSEVERITY_WARNING;
  CefString(&settings.root_cache_path) = proof_runtime.wstring();
  CefString(&settings.cache_path) = (proof_runtime / L"cache").wstring();
  CefString(&settings.log_file) = (proof_runtime / L"cef.log").wstring();

  CefRefPtr<ProofApp> app = new ProofApp(url, mode, width, height, target_hz,
                                        duration_ms, accelerated, animate, simulator,
                                        simulator_mailbox, present_mode, output);
  if (!CefInitialize(main_args, settings, app, nullptr)) {
    std::cerr << "[direct-cef-proof] CefInitialize failed" << std::endl;
    return 2;
  }

  app->WaitForClose();
  const bool wrote_result = app->WriteResult();
  app = nullptr;
  CefShutdown();
  return wrote_result ? 0 : 3;
}
