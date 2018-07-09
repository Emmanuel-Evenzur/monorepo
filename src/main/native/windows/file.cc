// Copyright 2016 The Bazel Authors. All rights reserved.
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

#include <stdint.h>  // uint8_t
#include <windows.h>

#include <memory>
#include <sstream>
#include <string>

#include "src/main/native/windows/file.h"
#include "src/main/native/windows/util.h"

namespace bazel {
namespace windows {

using std::unique_ptr;
using std::wstring;

int IsJunctionOrDirectorySymlink(const WCHAR* path) {
  DWORD attrs = ::GetFileAttributesW(path);
  if (attrs == INVALID_FILE_ATTRIBUTES) {
    return IS_JUNCTION_ERROR;
  } else {
    if ((attrs & FILE_ATTRIBUTE_DIRECTORY) &&
        (attrs & FILE_ATTRIBUTE_REPARSE_POINT)) {
      return IS_JUNCTION_YES;
    } else {
      return IS_JUNCTION_NO;
    }
  }
}

wstring GetLongPath(const WCHAR* path, unique_ptr<WCHAR[]>* result) {
  DWORD size = ::GetLongPathNameW(path, NULL, 0);
  if (size == 0) {
    DWORD err_code = GetLastError();
    return MakeErrorMessage(WSTR(__FILE__), __LINE__, L"GetLongPathNameW", path,
                            err_code);
  }
  result->reset(new WCHAR[size]);
  ::GetLongPathNameW(path, result->get(), size);
  return L"";
}

HANDLE OpenDirectory(const WCHAR* path, bool read_write) {
  return ::CreateFileW(
      /* lpFileName */ path,
      /* dwDesiredAccess */
      read_write ? (GENERIC_READ | GENERIC_WRITE) : GENERIC_READ,
      /* dwShareMode */ 0,
      /* lpSecurityAttributes */ NULL,
      /* dwCreationDisposition */ OPEN_EXISTING,
      /* dwFlagsAndAttributes */ FILE_FLAG_OPEN_REPARSE_POINT |
          FILE_FLAG_BACKUP_SEMANTICS,
      /* hTemplateFile */ NULL);
}

#pragma pack(push, 4)
typedef struct _JunctionDescription {
  typedef struct _Header {
    DWORD ReparseTag;
    WORD ReparseDataLength;
    WORD Reserved;
  } Header;

  typedef struct _WriteDesc {
    WORD SubstituteNameOffset;
    WORD SubstituteNameLength;
    WORD PrintNameOffset;
    WORD PrintNameLength;
  } Descriptor;

  Header header;
  Descriptor descriptor;
  WCHAR PathBuffer[ANYSIZE_ARRAY];
} JunctionDescription;
#pragma pack(pop)

int CreateJunction(const wstring& junction_name, const wstring& junction_target,
                   wstring* error) {
  const wstring target = HasUncPrefix(junction_target.c_str())
                             ? junction_target.substr(4)
                             : junction_target;
  // The entire JunctionDescription cannot be larger than
  // MAXIMUM_REPARSE_DATA_BUFFER_SIZE bytes.
  //
  // The structure's layout is:
  //   [JunctionDescription::Header]
  //   [JunctionDescription::Descriptor]
  //   ---- start of JunctionDescription::PathBuffer ----
  //   [4 WCHARs]             : "\??\" prefix
  //   [target.size() WCHARs] : junction target name
  //   [1 WCHAR]              : null-terminator
  //   [target.size() WCHARs] : junction target displayed name
  //   [1 WCHAR]              : null-terminator
  // The sum of these must not exceed MAXIMUM_REPARSE_DATA_BUFFER_SIZE.
  // We can rearrange this to get the limit for target.size().
  static const size_t kMaxJunctionTargetLen =
      ((MAXIMUM_REPARSE_DATA_BUFFER_SIZE - sizeof(JunctionDescription::Header) -
        sizeof(JunctionDescription::Descriptor) -
        /* one "\??\" prefix */ sizeof(WCHAR) * 4 -
        /* two null terminators */ sizeof(WCHAR) * 2) /
       /* two copies of the string are stored */ 2) /
      sizeof(WCHAR);
  if (target.size() > kMaxJunctionTargetLen) {
    if (error) {
      *error = MakeErrorMessage(WSTR(__FILE__), __LINE__, L"CreateJunction",
                                target, L"target path is too long");
    }
    return CreateJunctionResult::kTargetNameTooLong;
  }
  const wstring name = HasUncPrefix(junction_name.c_str())
                           ? junction_name
                           : (wstring(L"\\\\?\\") + junction_name);

  // `create` is true if we attempt to create or set the junction, i.e. can open
  // the file for writing. `create` is false if we only check that the existing
  // file is a junction, i.e. we can only open it for reading.
  bool create = true;

  // Junctions are directories, so create a directory.
  if (!::CreateDirectoryW(name.c_str(), NULL)) {
    DWORD err = GetLastError();
    if (err == ERROR_PATH_NOT_FOUND) {
      // A parent directory does not exist.
      return CreateJunctionResult::kParentMissing;
    }
    if (err == ERROR_ALREADY_EXISTS) {
      create = false;
    } else {
      // Some unknown error occurred.
      if (error) {
        *error = MakeErrorMessage(WSTR(__FILE__), __LINE__, L"CreateDirectoryW",
                                  name, err);
      }
      return CreateJunctionResult::kError;
    }
    // The directory already existed.
    // It may be a file, an empty directory, a non-empty directory, a junction
    // pointing to the wrong target, or ideally a junction pointing to the
    // right target.
  }

  AutoHandle handle(CreateFileW(
      name.c_str(), GENERIC_READ | GENERIC_WRITE, 0, NULL, OPEN_EXISTING,
      FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS, NULL));
  if (!handle.IsValid()) {
    DWORD err = GetLastError();
    // We can't open the directory for writing: either it disappeared, or turned
    // into a file, or another process holds it open without write-sharing.
    // Either way, don't try to create the junction, just try opening it for
    // reading and check its value.
    create = false;
    handle = CreateFileW(
        name.c_str(), GENERIC_READ, 0, NULL, OPEN_EXISTING,
        FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS, NULL);
    if (!handle.IsValid()) {
      // We can't open the directory even for reading: either it disappeared, or
      // it turned into a file, or another process holds it open without
      // read-sharing. Give up.
      DWORD err = GetLastError();
      if (err == ERROR_SHARING_VIOLATION) {
        // The junction is held open by another process.
        return CreateJunctionResult::kAccessDenied;
      } else if (err == ERROR_FILE_NOT_FOUND || err == ERROR_PATH_NOT_FOUND) {
        // Meanwhile the directory disappeared or one of its parent directories
        // disappeared.
        return CreateJunctionResult::kDisappeared;
      }

      // Some unknown error occurred.
      if (error) {
        *error = MakeErrorMessage(WSTR(__FILE__), __LINE__, L"CreateFileW",
                                  name, err);
      }
      return CreateJunctionResult::kError;
    }
  }

  // We have an open handle to the file! It may still be other than a junction,
  // so check its attributes.
  BY_HANDLE_FILE_INFORMATION info;
  if (!GetFileInformationByHandle(handle, &info)) {
    DWORD err = GetLastError();
    // Some unknown error occurred.
    if (error) {
      *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                                L"GetFileInformationByHandle", name, err);
    }
    return CreateJunctionResult::kError;
  }

  if (info.dwFileAttributes == INVALID_FILE_ATTRIBUTES) {
    DWORD err = GetLastError();
    // Some unknown error occurred.
    if (error) {
      *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                                L"GetFileInformationByHandle", name, err);
    }
    return CreateJunctionResult::kError;
  }

  if (info.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) {
    // The path already exists and it's a junction. Do not overwrite, just check
    // its target.
    create = false;
  }

  if (create) {
    if (!(info.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) {
      // Even though we managed to create the directory and it didn't exist
      // before, another process changed it in the meantime so it's no longer a
      // directory.
      create = false;
      if (!(info.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT)) {
        // The path is no longer a directory, and it's not a junction either.
        return CreateJunctionResult::kAlreadyExistsButNotJunction;
      }
    }
  }

  if (!create) {
    // The path already exists. Check if it's a junction.
    if (!(info.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT)) {
      return CreateJunctionResult::kAlreadyExistsButNotJunction;
    }
  }

  uint8_t reparse_buffer_bytes[MAXIMUM_REPARSE_DATA_BUFFER_SIZE];
  JunctionDescription* reparse_buffer =
      reinterpret_cast<JunctionDescription*>(reparse_buffer_bytes);
  if (create) {
    // The junction doesn't exist yet, and we have an open handle to the
    // candidate directory with write access and no sharing. Proceed to turn the
    // directory into a junction.

    memset(reparse_buffer_bytes, 0, MAXIMUM_REPARSE_DATA_BUFFER_SIZE);

    // "\??\" is meaningful to the kernel, it's a synomym for the "\DosDevices\"
    // object path. (NOT to be confused with "\\?\" which is meaningful for the
    // Win32 API.) We need to use this prefix to tell the kernel where the
    // reparse point is pointing to.
    memcpy(reparse_buffer->PathBuffer, L"\\??\\", 4 * sizeof(WCHAR));
    memcpy(reparse_buffer->PathBuffer + 4, target.c_str(),
           target.size() * sizeof(WCHAR));

    // In addition to their target, junctions also have another string which is
    // a user-visible name of where the junction points, as listed by "dir".
    // This can be any string and won't affect the usability of the junction.
    // MKLINK uses the target path without the "\??\" prefix as the display
    // name, so let's do that here too. This is also in line with how UNIX
    // behaves. Using a dummy or fake display name would be misleading, it would
    // make the output of `dir` look like:
    //   2017-01-18  01:37 PM    <JUNCTION>     juncname [dummy string]
    memcpy(reparse_buffer->PathBuffer + 4 + target.size() + 1, target.c_str(),
           target.size() * sizeof(WCHAR));

    reparse_buffer->descriptor.SubstituteNameOffset = 0;
    reparse_buffer->descriptor.SubstituteNameLength =
        (4 + target.size()) * sizeof(WCHAR);
    reparse_buffer->descriptor.PrintNameOffset =
        reparse_buffer->descriptor.SubstituteNameLength +
        /* null-terminator */ sizeof(WCHAR);
    reparse_buffer->descriptor.PrintNameLength = target.size() * sizeof(WCHAR);

    reparse_buffer->header.ReparseTag = IO_REPARSE_TAG_MOUNT_POINT;
    reparse_buffer->header.ReparseDataLength =
        sizeof(JunctionDescription::Descriptor) +
        reparse_buffer->descriptor.SubstituteNameLength +
        reparse_buffer->descriptor.PrintNameLength +
        /* 2 null-terminators */ (2 * sizeof(WCHAR));
    reparse_buffer->header.Reserved = 0;

    DWORD bytes_returned;
    if (!::DeviceIoControl(handle, FSCTL_SET_REPARSE_POINT, reparse_buffer,
                           reparse_buffer->header.ReparseDataLength +
                               sizeof(JunctionDescription::Header),
                           NULL, 0, &bytes_returned, NULL)) {
      DWORD err = GetLastError();
      if (err == ERROR_DIR_NOT_EMPTY) {
        return CreateJunctionResult::kAlreadyExistsButNotJunction;
      }
      // Some unknown error occurred.
      if (error) {
        *error = MakeErrorMessage(WSTR(__FILE__), __LINE__, L"DeviceIoControl",
                                  name, err);
      }
      return CreateJunctionResult::kError;
    }
  } else {
    // The junction already exists. Check if it points to the right target.

    DWORD bytes_returned;
    if (!::DeviceIoControl(handle, FSCTL_GET_REPARSE_POINT, NULL, 0,
                           reparse_buffer, MAXIMUM_REPARSE_DATA_BUFFER_SIZE,
                           &bytes_returned, NULL)) {
      DWORD err = GetLastError();
      // Some unknown error occurred.
      if (error) {
        *error = MakeErrorMessage(WSTR(__FILE__), __LINE__, L"DeviceIoControl",
                                  name, err);
      }
      return CreateJunctionResult::kError;
    }

    WCHAR* actual_target = reparse_buffer->PathBuffer +
                           reparse_buffer->descriptor.SubstituteNameOffset +
                           /* "\??\" prefix */ 4;
    if (reparse_buffer->descriptor.SubstituteNameLength !=
            (/* "\??\" prefix */ 4 + target.size()) * sizeof(WCHAR) ||
        _wcsnicmp(actual_target, target.c_str(), target.size()) != 0) {
      return CreateJunctionResult::kAlreadyExistsWithDifferentTarget;
    }
  }

  return CreateJunctionResult::kSuccess;
}

int DeletePath(const wstring& path, wstring* error) {
  const wchar_t* wpath = path.c_str();
  if (!DeleteFileW(wpath)) {
    DWORD err = GetLastError();
    if (err == ERROR_SHARING_VIOLATION) {
      // The file or directory is in use by some process.
      return DELETE_PATH_ACCESS_DENIED;
    } else if (err == ERROR_FILE_NOT_FOUND || err == ERROR_PATH_NOT_FOUND) {
      // The file or directory does not exist, or a parent directory does not
      // exist, or a parent directory is actually a file.
      return DELETE_PATH_DOES_NOT_EXIST;
    } else if (err != ERROR_ACCESS_DENIED) {
      // Some unknown error occurred.
      if (error) {
        *error = MakeErrorMessage(WSTR(__FILE__), __LINE__, L"DeleteFileW",
                                  path, err);
      }
      return DELETE_PATH_ERROR;
    }

    // DeleteFileW failed with access denied, because the file is read-only or
    // it is a directory.
    DWORD attr = GetFileAttributesW(wpath);
    if (attr == INVALID_FILE_ATTRIBUTES) {
      err = GetLastError();
      if (err == ERROR_FILE_NOT_FOUND || err == ERROR_PATH_NOT_FOUND) {
        // The file disappeared, or one of its parent directories disappeared,
        // or one of its parent directories is no longer a directory.
        return DELETE_PATH_DOES_NOT_EXIST;
      }

      // Some unknown error occurred.
      if (error) {
        *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                                  L"GetFileAttributesW", path, err);
      }
      return DELETE_PATH_ERROR;
    }

    if (attr & FILE_ATTRIBUTE_DIRECTORY) {
      // It's a directory or a junction.
      if (!RemoveDirectoryW(wpath)) {
        // Failed to delete the directory.
        err = GetLastError();
        if (err == ERROR_SHARING_VIOLATION) {
          // The junction or directory is in use by another process.
          return DELETE_PATH_ACCESS_DENIED;
        } else if (err == ERROR_DIR_NOT_EMPTY) {
          // The directory is not empty.
          return DELETE_PATH_DIRECTORY_NOT_EMPTY;
        } else if (err == ERROR_FILE_NOT_FOUND || err == ERROR_PATH_NOT_FOUND) {
          // The directory or one of its directories disappeared or is no longer
          // a directory.
          return DELETE_PATH_DOES_NOT_EXIST;
        }

        // Some unknown error occurred.
        if (error) {
          *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                                    L"DeleteDirectoryW", path, err);
        }
        return DELETE_PATH_ERROR;
      }
    } else {
      // It's a file and it's probably read-only.
      // Make it writable then try deleting it again.
      attr &= ~FILE_ATTRIBUTE_READONLY;
      if (!SetFileAttributesW(wpath, attr)) {
        err = GetLastError();
        if (err == ERROR_FILE_NOT_FOUND || err == ERROR_PATH_NOT_FOUND) {
          // The file disappeared, or one of its parent directories disappeared,
          // or one of its parent directories is no longer a directory.
          return DELETE_PATH_DOES_NOT_EXIST;
        }
        // Some unknown error occurred.
        if (error) {
          *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                                    L"SetFileAttributesW", path, err);
        }
        return DELETE_PATH_ERROR;
      }

      if (!DeleteFileW(wpath)) {
        // Failed to delete the file again.
        err = GetLastError();
        if (err == ERROR_FILE_NOT_FOUND || err == ERROR_PATH_NOT_FOUND) {
          // The file disappeared, or one of its parent directories disappeared,
          // or one of its parent directories is no longer a directory.
          return DELETE_PATH_DOES_NOT_EXIST;
        }

        // Some unknown error occurred.
        if (error) {
          *error = MakeErrorMessage(WSTR(__FILE__), __LINE__, L"DeleteFileW",
                                    path, err);
        }
        return DELETE_PATH_ERROR;
      }
    }
  }
  return DELETE_PATH_SUCCESS;
}

}  // namespace windows
}  // namespace bazel
