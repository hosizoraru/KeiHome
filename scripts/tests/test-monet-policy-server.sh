#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Standalone host checks; no Gradle, Android device, network, or binary fixtures.
set -euo pipefail

monet_test_script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
monet_test_repo="$(cd -- "${monet_test_script_dir}/../.." && pwd)"
monet_test_build_dir="$(mktemp -d "${TMPDIR:-/tmp}/keihome-monet-host.XXXXXX")"
trap 'rm -rf -- "$monet_test_build_dir"' EXIT

# CXX may select a host C++ compiler executable. Keep assertions active even
# when the invoking environment normally builds with NDEBUG.
"${CXX:-clang++}" \
    -std=c++17 -UNDEBUG -fno-exceptions -fno-rtti \
    -Wall -Wextra -Werror -O1 -g -fno-omit-frame-pointer \
    -fsanitize=address,undefined -fno-sanitize-recover=all -pthread \
    "${monet_test_repo}/app/src/test/cpp/monet_policy_server_test.cpp" \
    -o "${monet_test_build_dir}/monet-policy-server-test"

"${monet_test_build_dir}/monet-policy-server-test"
