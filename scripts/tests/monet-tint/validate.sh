#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

monet_test_script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec "${PYTHON:-python3}" "${monet_test_script_dir}/validate.py" "$@"
