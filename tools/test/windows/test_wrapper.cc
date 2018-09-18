// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Test wrapper implementation for Windows.
// Design:
// https://github.com/laszlocsomor/proposals/blob/win-test-runner/designs/2018-07-18-windows-native-test-runner.md

#define WIN32_LEAN_AND_MEAN
#include <lmcons.h>  // UNLEN
#include <windows.h>

#include <stdio.h>
#include <string.h>
#include <wchar.h>

#include <algorithm>
#include <functional>
#include <memory>
#include <string>

#include "src/main/cpp/util/file_platform.h"
#include "src/main/cpp/util/path_platform.h"
#include "src/main/cpp/util/strings.h"
#include "src/main/native/windows/file.h"
#include "tools/cpp/runfiles/runfiles.h"

namespace {

class Defer {
 public:
  explicit Defer(std::function<void()> f) : f_(f) {}
  ~Defer() { f_(); }

 private:
  std::function<void()> f_;
};

// A lightweight path abstraction that stores a Unicode Windows path.
//
// The class allows extracting the underlying path as a (immutable) string so
// it's easy to pass the path to WinAPI functions, but the class does not allow
// mutating the unterlying path so it's safe to pass around Path objects.
class Path {
 public:
  Path() {}
  Path(const Path& other) = delete;
  Path& operator=(const Path& other) = delete;
  const std::wstring& Get() const { return path_; }
  bool Set(const std::wstring& path);
  void Absolutize(const Path& cwd);

 private:
  std::wstring path_;
};

void LogError(const int line, const char* msg) {
  printf("ERROR(" __FILE__ ":%d) %s\n", line, msg);
}

void LogErrorWithValue(const int line, const char* msg, DWORD error_code) {
  printf("ERROR(" __FILE__ ":%d) error code: %d (0x%08x): %s\n", line,
         error_code, error_code, msg);
}

void LogErrorWithArgAndValue(const int line, const char* msg, const char* arg,
                             DWORD error_code) {
  printf("ERROR(" __FILE__ ":%d) error code: %d (0x%08x), argument: %s: %s\n",
         line, error_code, error_code, arg, msg);
}

void LogErrorWithArgAndValue(const int line, const char* msg,
                             const wchar_t* arg, DWORD error_code) {
  printf("ERROR(" __FILE__ ":%d) error code: %d (0x%08x), argument: %ls: %s\n",
         line, error_code, error_code, arg, msg);
}

inline void CreateDirectories(const Path& path) {
  blaze_util::MakeDirectoriesW(bazel::windows::HasUncPrefix(path.Get().c_str())
                                   ? path.Get()
                                   : L"\\\\?\\" + path.Get(),
                               0777);
}

bool GetEnv(const wchar_t* name, std::wstring* result) {
  static constexpr size_t kSmallBuf = MAX_PATH;
  WCHAR value[kSmallBuf];
  DWORD size = GetEnvironmentVariableW(name, value, kSmallBuf);
  DWORD err = GetLastError();
  if (size == 0 && err == ERROR_ENVVAR_NOT_FOUND) {
    result->clear();
    return true;
  } else if (0 < size && size < kSmallBuf) {
    *result = value;
    return true;
  } else if (size >= kSmallBuf) {
    std::unique_ptr<WCHAR[]> value_big(new WCHAR[size]);
    GetEnvironmentVariableW(name, value_big.get(), size);
    *result = value_big.get();
    return true;
  } else {
    LogErrorWithArgAndValue(__LINE__, "Failed to read envvar", name, err);
    return false;
  }
}

bool GetPathEnv(const wchar_t* name, Path* result) {
  std::wstring value;
  if (!GetEnv(name, &value)) {
    return false;
  }
  return result->Set(value);
}

bool SetEnv(const wchar_t* name, const std::wstring& value) {
  if (SetEnvironmentVariableW(name, value.c_str()) != 0) {
    return true;
  } else {
    LogErrorWithArgAndValue(__LINE__, "Failed to set envvar", name,
                            GetLastError());
    return false;
  }
}

bool GetCwd(Path* result) {
  static constexpr size_t kSmallBuf = MAX_PATH;
  WCHAR value[kSmallBuf];
  DWORD size = GetCurrentDirectoryW(kSmallBuf, value);
  DWORD err = GetLastError();
  if (size > 0 && size < kSmallBuf) {
    return result->Set(value);
  } else if (size >= kSmallBuf) {
    std::unique_ptr<WCHAR[]> value_big(new WCHAR[size]);
    GetCurrentDirectoryW(size, value_big.get());
    return result->Set(value_big.get());
  } else {
    LogErrorWithValue(__LINE__, "Failed to get current directory", err);
    return false;
  }
}

bool ExportUserName() {
  std::wstring value;
  if (!GetEnv(L"USER", &value)) {
    return false;
  }
  if (!value.empty()) {
    // Respect the value passed by Bazel via --test_env.
    return true;
  }
  WCHAR buffer[UNLEN + 1];
  DWORD len = UNLEN + 1;
  if (GetUserNameW(buffer, &len) == 0) {
    LogErrorWithValue(__LINE__, "Failed to query user name", GetLastError());
    return false;
  }
  return SetEnv(L"USER", buffer);
}

bool ExportSrcPath(const Path& cwd, Path* result) {
  if (!GetPathEnv(L"TEST_SRCDIR", result)) {
    return false;
  }
  if (blaze_util::IsAbsolute(result->Get())) {
    return true;
  } else {
    result->Absolutize(cwd);
    return SetEnv(L"TEST_SRCDIR", result->Get());
  }
}

bool ExportTmpPath(const Path& cwd, Path* result) {
  if (!GetPathEnv(L"TEST_TMPDIR", result)) {
    return false;
  }
  if (!blaze_util::IsAbsolute(result->Get())) {
    result->Absolutize(cwd);
    if (!SetEnv(L"TEST_TMPDIR", result->Get())) {
      return false;
    }
  }
  // Create the test temp directory, which may not exist on the remote host when
  // doing a remote build.
  CreateDirectories(*result);
  return true;
}

inline void PrintTestLogStartMarker() {
  // This header marks where --test_output=streamed will start being printed.
  printf(
      "------------------------------------------------------------------------"
      "-----\n");
}

inline bool GetWorkspaceName(std::wstring* result) {
  return GetEnv(L"TEST_WORKSPACE", result) && !result->empty();
}

inline void StripLeadingDotSlash(std::wstring* s) {
  if (s->size() >= 2 && (*s)[0] == L'.' && (*s)[1] == L'/') {
    s->erase(0, 2);
  }
}

bool FindTestBinary(const Path& argv0, std::wstring test_path, Path* result) {
  if (!blaze_util::IsAbsolute(test_path)) {
    std::string argv0_acp;
    uint32_t err;
    if (!blaze_util::WcsToAcp(argv0.Get(), &argv0_acp, &err)) {
      LogErrorWithArgAndValue(__LINE__, "Failed to convert string",
                              argv0.Get().c_str(), err);
      return false;
    }

    std::string error;
    std::unique_ptr<bazel::tools::cpp::runfiles::Runfiles> runfiles(
        bazel::tools::cpp::runfiles::Runfiles::Create(argv0_acp, &error));
    if (runfiles == nullptr) {
      LogError(__LINE__, "Failed to load runfiles");
      return false;
    }

    std::wstring workspace;
    if (!GetWorkspaceName(&workspace)) {
      LogError(__LINE__, "Failed to read %TEST_WORKSPACE%");
      return false;
    }

    StripLeadingDotSlash(&test_path);
    test_path = workspace + L"/" + test_path;

    std::string utf8_test_path;
    if (!blaze_util::WcsToUtf8(test_path, &utf8_test_path, &err)) {
      LogErrorWithArgAndValue(__LINE__, "Failed to convert string to UTF-8",
                              test_path.c_str(), err);
      return false;
    }

    std::string rloc = runfiles->Rlocation(utf8_test_path);
    if (!blaze_util::Utf8ToWcs(rloc, &test_path, &err)) {
      LogErrorWithArgAndValue(__LINE__, "Failed to convert string",
                              utf8_test_path.c_str(), err);
    }
  }

  return result->Set(test_path);
}

bool StartSubprocess(const Path& path, HANDLE* process) {
  // kMaxCmdline value: see lpCommandLine parameter of CreateProcessW.
  static constexpr size_t kMaxCmdline = 32768;

  std::unique_ptr<WCHAR[]> cmdline(new WCHAR[kMaxCmdline]);
  size_t len = path.Get().size();
  wcsncpy(cmdline.get(), path.Get().c_str(), len + 1);

  PROCESS_INFORMATION processInfo;
  STARTUPINFOW startupInfo = {0};

  if (CreateProcessW(NULL, cmdline.get(), NULL, NULL, FALSE, 0, NULL, NULL,
                     &startupInfo, &processInfo) != 0) {
    CloseHandle(processInfo.hThread);
    *process = processInfo.hProcess;
    return true;
  } else {
    LogErrorWithValue(__LINE__, "CreateProcessW failed", GetLastError());
    return false;
  }
}

int WaitForSubprocess(HANDLE process) {
  DWORD result = WaitForSingleObject(process, INFINITE);
  switch (result) {
    case WAIT_OBJECT_0: {
      DWORD exit_code;
      if (!GetExitCodeProcess(process, &exit_code)) {
        LogErrorWithValue(__LINE__, "GetExitCodeProcess failed",
                          GetLastError());
        return 1;
      }
      return exit_code;
    }
    case WAIT_FAILED:
      LogErrorWithValue(__LINE__, "WaitForSingleObject failed", GetLastError());
      return 1;
    default:
      LogErrorWithValue(
          __LINE__, "WaitForSingleObject returned unexpected result", result);
      return 1;
  }
}

bool Path::Set(const std::wstring& path) {
  std::wstring result;
  std::string error;
  if (!blaze_util::AsWindowsPath(path, &result, &error)) {
    LogError(__LINE__, error.c_str());
    return false;
  }
  path_ = result;
  return true;
}

void Path::Absolutize(const Path& cwd) {
  if (!path_.empty() && !blaze_util::IsAbsolute(path_)) {
    path_ = cwd.path_ + L"\\" + path_;
  }
}

}  // namespace

int wmain(int argc, wchar_t** argv) {
  // TODO(laszlocsomor): Implement the functionality described in
  // https://github.com/laszlocsomor/proposals/blob/win-test-runner/designs/2018-07-18-windows-native-test-runner.md

  Path argv0;
  if (!argv0.Set(argv[0])) {
    return 1;
  }
  argc--;
  argv++;
  bool suppress_output = false;
  if (argc > 0 && wcscmp(argv[0], L"--no_echo") == 0) {
    // Don't print anything to stdout in this special case.
    // Currently needed for persistent test runner.
    suppress_output = true;
    argc--;
    argv++;
  } else {
    std::wstring test_target;
    if (!GetEnv(L"TEST_TARGET", &test_target)) {
      return 1;
    }
    printf("Executing tests from %ls\n", test_target.c_str());
  }

  if (argc < 1) {
    LogError(__LINE__, "Usage: $0 [--no_echo] <test_path> [test_args...]");
    return 1;
  }

  if (!suppress_output) {
    PrintTestLogStartMarker();
  }

  Path exec_root;
  if (!GetCwd(&exec_root)) {
    return 1;
  }

  Path srcdir, tmpdir, xml_output;
  if (!ExportUserName() || !ExportSrcPath(exec_root, &srcdir) ||
      !ExportTmpPath(exec_root, &tmpdir)) {
    return 1;
  }

  const wchar_t* test_path_arg = argv[0];
  Path test_path;
  if (!FindTestBinary(argv0, test_path_arg, &test_path)) {
    return 1;
  }

  HANDLE process;
  if (!StartSubprocess(test_path, &process)) {
    return 1;
  }
  Defer close_process([process]() { CloseHandle(process); });

  return WaitForSubprocess(process);
}
