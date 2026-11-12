# Release shrinking boundaries

Release builds use R8, optimized default rules and resource shrinking. Prefer
ordinary reachability and dependency consumer rules over keeping whole packages.

`app/proguard-rules.keep` preserves these application-owned contracts:

- `KeiHomeModule`: its complete small entry surface, including the name in
  `META-INF/xposed/java_init.list`, constructor and six API 102 callbacks. This
  preserves the established boundary across resident reload generations.
  Code optimization is allowed; member shrinking and renaming remain blocked.
- `KeiHomeApplication` and `MainActivity`: manifest component names, constructors,
  and creation callbacks.
- `IconResourceProbeService`: its cross-UID component name, constructor, and
  Android service creation, binding and destruction callbacks.
- `JGitText`: its constructor, class name and public String field names. JGit NLS
  reflectively creates this bundle and populates its fields from the matching
  properties resource.

The probe helpers and fields can be renamed or removed when unreachable; its
callbacks and entry names remain. The UI entry implementations may be optimized.
SystemUI/Security/Settings reflection resolves classes in the **host APK**, so it
does not justify keeping KeiHome's own resolver wrappers. DexKit supplies its
native-member consumer rules.

## Reviewing changes

With the current AGP, run `:app:analyzeReleaseR8Config` without minifying the app.
Inspect `app/build/reports/r8/r8-config-analyzer-release.html` and its accompanying
protobuf file for the configuration analysis. Paths/output formats can change
with AGP; use the actual task outputs.
The November 12 audit retained its raw analysis and per-rule details under
`.artifacts/research/cache-guide-release-20261112/r8/`.

That baseline analysis identified member-wide constraints in the two entry
rules. In that baseline, only the probe rule was narrowed. A callback-only
module rule passed an initial reload but a later mixed diagnostic/Release reload
did not satisfy the complete initialization acknowledgement. Retirement,
adoption and icon callbacks continued, so this does not establish the root cause.
The final configuration conservatively retains the established module rule
rather than depending on an unverified optimization. No APK size or speed
improvement is claimed. Do not remove manifest component guards solely because AAPT produced
overlapping textual rules: optimized resource shrinking consumes manifest inputs
separately.

The subsequent API102 review permits code optimization while preserving the complete
module class/member name and retention boundary. It does not restore callback-only
guards. The AGP analyzer still attributes one class, eight fields and 26 methods to
this rule, with `DONT_OBFUSCATE` and `DONT_SHRINK`; only `DONT_OPTIMIZE` is removed.
See [the source review and validation contract](libxposed-api102-r8.md).

After changing rules, build the signed minified Release, inspect its entry names
and JGit properties, and validate normal API 102 hot reload on the authorized AVD.
Exercise the actual probe binding and NLS initialization as appropriate. Debug
instrumentation establishes UI behavior but does not establish Release reflection.

Local APKs, signing inputs, heap dumps and Perfetto exports are ignored. Keep
source assets, tests and documentation reviewable; `.artifacts/` remains local
evidence and is not a disposal policy.
