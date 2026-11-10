# Native fallback for factory data apps

Some HyperOS factory APKs live under `/product/data-app` and are removable.
After updating, they run from `/data/app` without either Android system-app flag.
Treating every such app as an ordinary third-party app can put a full native icon
inside the generic ColorOS fallback plate, shrinking its visible content.

`SystemIconFallbackRenderer` now admits factory data apps to system-icon fallback
only when the system supplies a valid static icon or native layer pair for the
exact package/component. It accepts an installed APK inside a canonical, trusted
factory `data-app` directory. Updated APKs outside that directory must match an
original factory package and its signing certificates. Android's signing-certificate
API also recognizes authorized signing history. This does not change Android
application flags or grant any application system privileges.

The factory directories are discovered under product/system/system_ext/vendor/odm.
A product-to-system/product symlink is supported; symlinks escaping a factory root,
mutable data-directory roots, invalid package names and malformed certificate
records are rejected. Enumeration is bounded by depth, directory count, APK count,
APK size and signer count. Unavailable/damaged archives fail closed; cancellation
does not publish an incomplete index.

The process caches only factory package/file/certificate metadata. Manifest-only
indexing avoids reading every large factory APK's certificates. Certificates are
loaded lazily for a matching package, and installed signing identity is checked
per renderer. Files on the read-only system image are assumed stable for the
process lifetime; an ordinary system-image update restarts the process. No polling,
network request, Binder icon-bind hook or per-frame scan was added. This work runs
in the existing export/preview worker paths.

Exact icon-pack mappings retain priority. The existing optional system-app name
matching also uses this restricted eligibility when Android flags are absent;
its switch and conservative name/role rules remain unchanged. Live clock/calendar
originals are excluded before static rendering. Ordinary user apps and factory
partner apps lacking matching native artwork retain the existing third-party
fallback. Apple preset/name classification is not broadened by this change.

The fallback source identity advances to `system-fallback-v3`. Original source
association recognizes both v1 and v2 publications; old publications can offer one
regeneration rather than silently reusing stale white-plate output. Existing active
themes are not regenerated/applied automatically.

Phone AVD audit, 2026-11-10: 37 factory APKs, 24 installed non-system preloads,
15 with stock art. Thirteen static candidates were checked against all ten bundled
ColorOS combinations in both appearances, including wallet, global roaming,
scanner, the XiaoAi proxy, Notes, Calculator, Compass, Recorder and Screen Recorder.
The separate clock/calendar test keeps their live originals. Tests export only to
private cache; they do not apply another active theme or open those applications.

Evidence: `.artifacts/research/preinstalled-fallback-20261110/acceptance.json`.
The evidence is from the Phone AVD, not every OEM image or a physical-device run.
