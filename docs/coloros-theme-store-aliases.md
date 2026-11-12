# ColorOS theme-store aliases

The optional sharing/name rules are configured in [Recommended mappings](recommended-icon-mappings.md). Existing choices are preserved; generated themes require updating and reapplying after a change.

ColorOS built-in packs use the canonical `com.heytap.themestore` artwork for both HyperOS theme applications:

- `com.android.thememanager` (主题壁纸)
- `com.miui.themestore` (主题商店)

The aliases are installed when a built-in descriptor is loaded. Mapping browsing, light/dark previews, generation and system-app fallback therefore use the same entries. Inspiration uses the classic ART+ source and retains its native monochrome metadata for Monet rendering. This requires neither optional system-name matching nor broad label-based guesses.

Existing exact package and activity mappings take priority. Third-party APK/MTZ/ZIP descriptors are not rewritten. If the canonical ColorOS entry is missing, nothing is fabricated.

The built-in publication source identity includes `theme-alias-1`. Previously generated ColorOS themes can consequently be offered an update even when the original resource digest has not changed. Updating uses the retained local artwork; it does not force a resource download or automatically apply a new theme.

Tests cover alias precedence, idempotence, unsupported inputs and publication invalidation. A device test renders both aliases and compares their light/dark pixels and source metadata with the canonical entry across every locally available built-in descriptor.
