# Contributing to KeiHome

Focused pull requests for translations, corrections, and app improvements are
welcome. Explain the affected behavior, keep unrelated changes separate, and
include the checks relevant to the change.

For language contributions, start with the [localization guide](docs/localization.md).
It covers the English fallback, feature resource files, partial new languages,
App language registration, fixed source identities, and a Python-only audit:

```sh
python3 scripts/checks.py i18n --run
```

Use the [OEM terminology reference](docs/oem-localization-reference.md) when a
label refers to a system destination. Include the language, affected page, and
OEM/OS version when reporting a translation issue. Preserve canonical source
links and attribution; contribute original translations rather than copied
OEM resources.

For code changes, see the [testing guide](docs/testing.md) and choose the
smallest relevant check group with `python3 scripts/checks.py --list`. The
checks runner previews commands unless `--run` is passed. Include the actual
validation performed and any remaining limitation in the pull request.
