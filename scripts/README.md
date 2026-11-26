# Development scripts

Run these commands from the repository root. Each script's `--help` or linked guide documents its inputs and prerequisites.

| Task | Where to start |
| --- | --- |
| List focused checks | `python3 scripts/checks.py --list` |
| Preview a check | `python3 scripts/checks.py i18n` |
| Run the localization audit | `python3 scripts/checks.py i18n --run` |
| Generate branding assets | [`export-brand-icon.py`](export-brand-icon.py) · [Branding guide](../docs/branding/README.md) |
| Package built-in icons | [`pack-builtin-icons.py`](pack-builtin-icons.py) · [Built-in icon storage](../docs/builtin-icon-storage.md) |
| Validate a device or release candidate | [Testing guide](../docs/testing.md) · [Icon-pack release checks](../docs/release-iconpack-acceptance.md) |

[`tests/`](tests/) contains focused harnesses and fixtures. [`research/`](research/) contains inspection tools used for particular investigations. Use an explicitly selected device for device checks and keep generated output and local evidence under `.artifacts/`.

[Project overview](../docs/README.md) · [Project structure](../docs/project-structure.md) · [CI](../docs/ci.md)
