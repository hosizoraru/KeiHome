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

## Codex guidance

Keep project-wide requirements in [AGENTS.md](AGENTS.md) and recurring device
workflows in the [device-validation skill](.agents/skills/keihome-device-validation/SKILL.md).
For a task, describe the intended behavior and how it will be reviewed. Attach
the affected screen and state when appearance matters. Give complex work clear
completion criteria and room to investigate competing explanations.

Choose the model and reasoning effort for the task in the client. Sol suits
routine work and Astra suits deeper cross-system investigation. Use Medium for
a quick survey, High when tracing behavior across systems, and consider Extra
High when multiple causes may interact. See the
[GPT-6.2 release notes](https://openai.com/index/introducing-gpt-6-2-astra-sol/)
and the [Codex prompting guide](https://learn.chatgpt.com/guides/prompting-gpt-6-2-astra).
