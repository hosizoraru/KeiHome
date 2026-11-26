"""Report whether a GitHub Actions run needs the Android build jobs."""

import os
from pathlib import Path, PurePosixPath
import subprocess
import sys


DOCS_ONLY_FILES = {
    ".github/README.md",
    "AGENTS.md",
    "CONTRIBUTING.md",
    "README.md",
    "app/README.md",
    "app/src/main/kotlin/os/kei/hyper/home/iconpack/README.md",
    "build-logic/README.md",
    "scripts/README.md",
}


def is_documentation(path: str) -> bool:
    return path in DOCS_ONLY_FILES or (
        path.startswith("docs/") and PurePosixPath(path).suffix == ".md"
    )


def build_required(base: str) -> bool:
    if not base:
        return True

    # Count both sides of a rename so moving code into docs still runs checks.
    diff = subprocess.run(
        ["git", "diff", "--name-only", "--no-renames", "-z", base, "HEAD", "--"],
        capture_output=True,
    )
    if diff.returncode:
        print("Could not compare revisions. Running the full build.")
        return True

    changed = [os.fsdecode(path) for path in diff.stdout.split(b"\0") if path]
    return not changed or any(not is_documentation(path) for path in changed)


if __name__ == "__main__":
    required = build_required(sys.argv[1])
    result = f"required={str(required).lower()}"
    print(result)
    with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
        print(result, file=output)
