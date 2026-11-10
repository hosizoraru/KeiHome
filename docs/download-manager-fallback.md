# Download manager system fallback

## Cause

On the tested HyperOS Phone AVD, `com.android.providers.downloads.ui` is installed
under `/data/app` without `FLAG_SYSTEM` or `FLAG_UPDATED_SYSTEM_APP`. Its paired
`com.android.providers.downloads` service remains a system package. Both packages
have matching signing identities and identical original layered artwork under
`/product/media/theme/miui_mod_icons/dynamic/`.

The bundled ColorOS packs have no explicit mapping for this UI package. The old
system-only fallback guard rejected it, so ColorOS's general fallback treated its
rounded bitmap as a transparent foreground. That path added a white plate and
applied the normal foreground inset. The result was the small centered green icon
reported by the user. The original artwork itself was not missing.

## Correction

`SystemIconCompanions` recognizes the exact download UI/service pair. The UI is
eligible for the existing system fallback only when the installed service is a
system application and PackageManager confirms matching signatures. Similar
package names, arbitrary `com.android` prefixes, missing hosts and mismatched
signatures do not qualify.

The existing fallback renderer then uses the original layered artwork and native
mask, respecting the user's selected fallback style. Explicit pack mappings keep
priority. Classification is cached per renderer session; there is no new launcher
hook, frame callback, background poll or global PackageManager interception.

The generated fallback contract advances from `system-fallback-v1` to
`system-fallback-v2`. Legacy source association is preserved, while existing
generated copies receive a one-time update notice. Updating the module alone
does not rewrite an already imported theme archive: regenerate/update the icon
scheme and apply it once.

## Verification

Evidence: `.artifacts/research/download-icon-20261110/acceptance.json`.
Device tests cover the data-installed signed companion, native artwork equality,
and light/dark exports for every bundled ColorOS style. The target UI's fallback
must match the system download service's full-size artwork on this AVD fixture.
Unit tests cover untrusted companions and legacy publication/update handling.

This is Phone AVD acceptance; it does not claim inspection of the reporting
user's physical device or blanket support for other split system applications.

The subsequent [factory data-app audit](preinstalled-icon-fallback.md) extends the
eligibility rule through exact factory package/certificate evidence and stock
artwork presence. Its v3 source identity supersedes this checkpoint's v2 identity.
