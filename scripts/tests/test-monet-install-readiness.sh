#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Host installer orchestration only; no image patching, transport, or device.
set -euo pipefail

readiness_script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readiness_repo="$(cd -- "${readiness_script_dir}/../.." && pwd)"
readiness_build_dir="$(mktemp -d "${TMPDIR:-/tmp}/keihome-readiness-host.XXXXXX")"
trap 'rm -rf -- "$readiness_build_dir"' EXIT

"${CXX:-clang++}" \
    -std=c++17 -UNDEBUG -fno-exceptions -fno-rtti \
    -Wall -Wextra -Werror -O1 -g -fno-omit-frame-pointer \
    -fsanitize=address,undefined -fno-sanitize-recover=all \
    -I "${readiness_repo}/app/src/main/cpp" \
    -I "${readiness_script_dir}/icon-highlight/stubs" \
    "${readiness_script_dir}/icon-highlight/test_monet_readiness.cpp" \
    -o "${readiness_build_dir}/monet-install-readiness-test"

"${readiness_build_dir}/monet-install-readiness-test"
