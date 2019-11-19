#!/bin/bash
#
# Copyright 2016 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# An end-to-end test for the wrapper used by our installer and distro packages.

# --- begin runfiles.bash initialization ---
if [[ ! -d "${RUNFILES_DIR:-/dev/null}" && ! -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
    if [[ -f "$0.runfiles_manifest" ]]; then
      export RUNFILES_MANIFEST_FILE="$0.runfiles_manifest"
    elif [[ -f "$0.runfiles/MANIFEST" ]]; then
      export RUNFILES_MANIFEST_FILE="$0.runfiles/MANIFEST"
    elif [[ -f "$0.runfiles/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
      export RUNFILES_DIR="$0.runfiles"
    fi
fi
if [[ -f "${RUNFILES_DIR:-/dev/null}/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
  source "${RUNFILES_DIR}/bazel_tools/tools/bash/runfiles/runfiles.bash"
elif [[ -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  source "$(grep -m1 "^bazel_tools/tools/bash/runfiles/runfiles.bash " \
            "$RUNFILES_MANIFEST_FILE" | cut -d ' ' -f 2-)"
else
  echo >&2 "ERROR: cannot find @bazel_tools//tools/bash/runfiles:runfiles.bash"
  exit 1
fi
# --- end runfiles.bash initialization ---

source "$(rlocation "io_bazel/src/test/shell/integration_test_setup.sh")" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

set -e

wrapper=$(rlocation io_bazel/scripts/packages/bazel.sh)

mock_bazel() {
  cat > "$1" <<'EOF'
#!/bin/bash

set -euo pipefail

echo "Hello from $(basename $0)!"
echo "My args: $@"

exit 0
EOF
  chmod +x "$1"
}

setup_mock() {
  # Setup a mock workspace.
  mkdir ws ws/subdir
  touch ws/{BUILD,WORKSPACE}
  touch ws/subdir/BUILD

  # Setup a mock "/usr/bin" folder with the wrapper and some "Bazel" binaries.
  mkdir bin
  cp "$wrapper" "bin/bazel"
  chmod +x "bin/bazel"
  mock_bazel "bin/bazel-0.29.1"
  mock_bazel "bin/bazel-1.0.1"
  mock_bazel "bin/bazel-real"

  cd ws
}

test_use_bazel_version_envvar() {
  setup_mock

  USE_BAZEL_VERSION="0.29.1" ../bin/bazel version &> "$TEST_log"
  expect_log "Hello from bazel-0.29.1"
  expect_log "My args: version"
}

test_bazelversion_file() {
  setup_mock

  echo "1.0.1" > .bazelversion

  ../bin/bazel info &> "$TEST_log"
  expect_log "Hello from bazel-1.0.1"
  expect_log "My args: info"

  cd subdir
  ../../bin/bazel build //src:bazel &> "$TEST_log"
  expect_log "Hello from bazel-1.0.1"
  expect_log "My args: build //src:bazel"
}

test_uses_bazelreal() {
  setup_mock

  ../bin/bazel &> "$TEST_log"
  expect_log "Hello from bazel-real"
  expect_log "My args:"
}

test_uses_latest_version() {
  setup_mock

  rm ../bin/bazel-real
  ../bin/bazel &> "$TEST_log"
  expect_log "Hello from bazel-1.0.1"

  rm ../bin/bazel-1.0.1
  ../bin/bazel &> "$TEST_log"
  expect_log "Hello from bazel-0.29.1"
}

test_error_message_for_no_available_bazel_version() {
  setup_mock

  rm ../bin/bazel-*
  if ../bin/bazel &> "$TEST_log"; then
    fail "Bazel wrapper should have failed"
  fi
  expect_log "No installed Bazel version found, cannot continue"
}

test_error_message_for_required_bazel_not_found() {
  setup_mock

  if USE_BAZEL_VERSION="foobar" ../bin/bazel &> "$TEST_log"; then
    fail "Bazel wrapper should have failed"
  fi
  expect_log "ERROR: Required Bazel binary \"bazel-foobar\" (specified in \$USE_BAZEL_VERSION) not found"
}

test_delegates_to_wrapper_if_present() {
  setup_mock

  mkdir tools
  cat > tools/bazel <<'EOF'
#!/bin/bash

set -euo pipefail

echo "Hello from the wrapper tools/bazel!"
echo "BAZEL_REAL = ${BAZEL_REAL}"
echo "My args: $@"

exit 0
EOF
  chmod +x tools/bazel

  USE_BAZEL_VERSION="0.29.1" ../bin/bazel build //src:bazel &> "$TEST_log"
  expect_log "Hello from the wrapper tools/bazel!"
  expect_log "BAZEL_REAL = .*/bin/bazel-0.29.1"
  expect_log "My args: build //src:bazel"
}

run_suite "Integration tests for scripts/packages/bazel.sh."
