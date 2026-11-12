# Release shrinking boundaries

Release builds use R8, optimized default rules and resource shrinking. Prefer
ordinary reachability and dependency consumer rules over keeping whole packages.

`app/proguard-rules.keep` preserves these application-owned contracts:

- `KeiHomeModule`: the name in `META-INF/xposed/java_init.list`, its constructor,
  and the six API 102 module/package/system-server/hot-reload callbacks.
- `KeiHomeApplication` and `MainActivity`: manifest component names, constructors,
  and creation callbacks.
- `IconResourceProbeService`: its cross-UID component name, constructor, and
  Android service creation, binding and destruction callbacks.
- `JGitText`: its constructor, class name and public String field names. JGit NLS
  reflectively creates this bundle and populates its fields from the matching
  properties resource.

The module/probe helpers and fields can be renamed or removed when unreachable;
the callbacks and entry names remain. Entry implementations may be optimized.
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

That baseline analysis found that the two former member-wide keeps constrained
15 helper fields and 23 helper/synthetic methods unnecessarily. Narrowing these
rules releases those explicit constraints; this is not an APK size or speed
claim. Do not remove the manifest component guards solely because AAPT produced
overlapping textual rules: optimized resource shrinking consumes manifest inputs
separately.

After changing rules, build the signed minified Release, inspect its entry names
and JGit properties, and validate normal API 102 hot reload on the authorized AVD.
Exercise the actual probe binding and NLS initialization as appropriate. Debug
instrumentation establishes UI behavior but does not establish Release reflection.

Local APKs, signing inputs, heap dumps and Perfetto exports are ignored. Keep
source assets, tests and documentation reviewable; `.artifacts/` remains local
evidence and is not a disposal policy.
