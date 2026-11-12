# Localization

Community corrections and new language pull requests are welcome. Keep changes
focused on one language or a small, explained terminology correction. Consult
the short [OEM terminology reference](oem-localization-reference.md) when naming
system screens and features. It records the terms observed in the relevant
English/Japanese OEM interface; it is not a source of reusable APK assets.

## Resource layout

The English fallback is `app/src/main/res/values/`. Simplified Chinese uses
`values-zh-rCN/`; Japanese uses `values-ja/`. Text is split by feature across
many XML files, not just `strings.xml`. Preserve resource names and use matching
feature filenames when adding translations. Change the default English entry
when adding a new user-facing resource, then update published translations.

The published language tags are `en`, `zh-CN`, and `ja`, declared in
`app/src/main/res/xml/locales_config.xml`. The manifest references this file
through `android:localeConfig`. Missing published translations fail the audit.
English coverage comes from `values/`; a duplicate `values-en/` is unnecessary.

For a new language, start with `values-fr/`, for example, or a region/script
qualifier such as `values-pt-rBR/` or `values-b+zh+Hant/`. Translate a coherent
feature first. Omit unfinished resources so Android can fall back to English.
The audit discovers resource directories as well as registered language tags.
Missing entries in a new language are warnings, so a partial contribution is
possible. Invalid XML, duplicate definitions, unknown resource names, and
incompatible formatters remain errors in every language.

Resource variants such as `values-ja-night/` are sparse overrides: they do not
need a second complete catalog. Regional catalogs can inherit their language
catalog. Duplicates are checked within the same complete directory qualifier,
including across files; an intentional night or regional override is distinct.

## Registering App language selection

A new translation directory alone does not add a language to either picker.
When a language is ready to be offered, update these actual registration points
together:

1. Add its BCP-47 tag to `app/src/main/res/xml/locales_config.xml` for Android's
   per-app language settings.
2. Add an entry and a suitable `fromTag` mapping in
   `app/src/main/kotlin/os/kei/hyper/home/ui/main/AppLanguage.kt`. Consider
   regional and script variants explicitly; do not map a language to an
   incompatible script.
3. Add its native display name as a base `language_*` string with
   `translatable="false"`. In `SettingsContent.kt`, update `LanguagePreference`
   to read that label, include it in `remember` keys, and place it in the
   dropdown list in exactly the order used by `AppLanguage.entries`. The menu
   currently pairs the list and enum by index. Keep the System entry first.
4. Extend `app/src/test/kotlin/os/kei/hyper/home/ui/main/AppLanguageTest.kt` for
   the intended tag mappings and unsupported variants. During normal app
   validation, check both the in-app menu and Android's App language settings.

Newly registered languages remain warning-only for missing entries until their
completion gate is adopted. Use `--require-complete TAG` for review; when
publishing a new fully supported language, add its tag to `PUBLISHED` in the
auditor so routine checks enforce its coverage too.

## Copy and fixed identifiers

Use the OEM label for a destination the user must find on their device, and
keep the same feature name across navigation, search keywords, summaries,
accessibility labels, and instructions. Describe what an action actually does:
reading local resources, downloading, generating, importing, and applying are
different steps. Prefer short, natural labels and actionable error text.
Include the affected page and OEM/OS version when proposing a terminology fix.

Keep `KeiHome`, `HyperOS`, `ColorOS`, `LSPosed`, and `MIUIX` recognizable as
product/component names. Preserve the canonical project/repository identity
`pzcn/Perfect-Icons-Completion-Project` in attribution and source links. A
localized descriptive feature title is allowed; source identities, package
names, resource keys, URLs, file extensions, API numbers, and technical tokens
inside instructions must stay exact. Do not translate an imported icon pack's
author-supplied identity as though it were KeiHome UI.

The existing native language names (`language_chinese`, `language_english`,
`language_japanese`) and `status_hero_load_report_api` are marked
`translatable="false"` in the base catalog. They fall back directly and need no
localized copies. Identical product names in translated sentences are
intentional, so the auditor does not flag text simply because it equals English.
Do not use `translatable="false"` to hide an unfinished translation.

Use OEM observations to write concise original UI copy. Do not copy OEM APK
resources, artwork, or long passages into the repository. Keep research and
terminology evidence in the linked reference rather than duplicating it here.

## Formatting and XML

Preserve formatter argument indexes and types: `%1$s` is a string and `%2$d`
is an integer. A translation can reorder them, for example
`%2$d ... %1$s`. Use numbered arguments whenever a message has multiple inputs.
Repeated references to one argument are allowed. Keep precision/width sensible;
integer (`d/o/x`), floating-point (`e/f/g/a`), and date/time conversions are
compared by family. Date fields can differ for a language's presentation.
The checker cannot infer whether two arguments of the same type were given
the wrong semantic meaning.

In a formatted message, write a literal percent as `%%`. An ordinary literal
percentage without formatter arguments is allowed. Use `formatted="false"`
only for a genuinely literal format example. Preserve existing XML/Android
escaping (`&amp;`, `&lt;`, `\n`, `\'`), inline markup, and `xliff:g` content.
Do not replace a formatted argument with a hardcoded sample value.

Preserve string-array item order because consumers may use indexes. Length
differences are review warnings: Android permits them, but callers may assume
the base length. Translate each applicable plural form and always provide
`quantity="other"`. Languages do not need the same plural categories. A plural
can spell out or omit the count, such as "No items"; remaining format arguments
must retain their original indexes and types. Local `@string/...` references
are resolved for formatter checks, including translated targets.

## Lightweight checks

Run from the repository root with Python 3. No third-party packages are needed:

```sh
python3 scripts/checks.py i18n
python3 scripts/checks.py i18n --run
python3 scripts/i18n/check_resources.py --require-complete fr
python3 -m unittest discover -s scripts/i18n -p 'test_*.py'
```

The first command previews; the second audits. The direct command can be used
to require a new language's completion. `--res-dir PATH` supports checking a
small fixture or another resource tree with the same locale-registration
convention. Findings include file/line locations; missing translations point
to the base entry to translate. Errors return exit code 1; warnings return 0
when there are no errors. All findings are printed without a baseline allowlist.

This is a resource consistency check. It parses all XML under `res/` and audits
strings, string arrays, and plurals; it does not replace Android resource
compilation, lint, or actual layout and screen-reader review. It compares
formatter argument contracts, not every Java Formatter flag/width constraint,
and leaves framework/external resource references to Android's tools. For a
language PR, report the audit result and describe any unchecked screens or
remaining warnings. Screenshots are useful for clipped English/Japanese labels
and dialogs. Translation review itself does not require a device install.

## Shared UI controls

Use `LocalizedInputField` for MIUIX input fields in app screens. Its slots keep
the search decoration out of accessibility speech and localize the clear action;
the upstream default descriptions are English literals. Reuse localized action
resources for other accessibility labels, and avoid caching them without a
language/configuration key. Test clearing and locale changes when changing this
wrapper. Authored pack names, package labels and stored theme names remain source
metadata; do not translate them heuristically.

Keep short navigation labels separate from full headings: `navigation_desktop`
is Home / ホーム, while `desktop_home_title` is Home screen / ホーム画面.
The full English phrase wraps in the five-item bottom bar; a literal translation
is not appropriate for every placement.
