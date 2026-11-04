#!/usr/bin/env bash
set -euo pipefail

test_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "$test_dir/../../.." && pwd)"
test_tmp="$(mktemp -d "${TMPDIR:-/tmp}/dock-glass-tests.XXXXXX")"
trap 'rm -rf -- "$test_tmp"' EXIT

"${CXX:-c++}" -std=c++17 -Wall -Wextra -Wpedantic -Werror -O2 \
    -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$repo_dir/app/src/main/cpp" "$test_dir/test_geometry.cpp" \
    -o "$test_tmp/test_geometry"
"$test_tmp/test_geometry"
