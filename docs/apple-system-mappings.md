# Apple system mappings and incremental requests

The presets match these products. Exact verified OEM packages may be ordinary
user-installed/updatable apps; generic role names remain restricted to system
apps. Manual mappings and exclusions take precedence.

| Apple product | HyperOS role |
| --- | --- |
| Notes | Notes |
| Wallet | Wallet |
| Messages | Messages |
| Passwords | Passwords / password manager |
| Find My | Find / find device |
| Contacts | Contacts |
| Translate | Translate |
| Phone | Phone |
| Calculator | Calculator |
| Voice Memos | Recorder |
| Feedback Assistant | User feedback / services and feedback |
| Mail | Email |
| Health | Mi Fitness |
| Compass | Compass |
| TV | Mi Video |
| Siri | Both XiaoAI packages |
| Games | Game center |
| Calendar | Calendar; native live template on iOS/iPadOS selections |
| Clock | Clock; native live template on iOS/iPadOS selections |

The mobile Siri preset uses App Store ID `6758482875`, bundle
`com.apple.SiriApp`. Its black-orb native artwork comes from the iOS runtime's
`SiriApp.app`, rather than the legacy colorful macOS Siri icon. macOS keeps its
own native Siri source. Mobile-only public products are not presented as macOS
products.

Phone and Contacts can share one Android package. Package-based mappings use the
actual launcher activity label to choose the role; they do not promise two
independent icons for activities sharing one package. Calendar/Clock now have a
bounded mobile dynamic path; their generation tests and pending actual launcher
acceptance are distinguished below.

## Resource evidence

The public Apple lookup/catalog responses and 30 validated CDN renditions are
retained under `.artifacts/research/apple-system-batch-20261108/`. Most products
have distinct light/dark renditions. Wallet and Passwords also have genuine
native iOS fallback pairs. Feedback uses Apple's macOS layered catalog rendered
with its native iOS presentation; provenance records distinguish the source
platform from the requested presentation.

Compass, TV and Voice Memos share their original dark-looking artwork between
appearances. Identical decoded pixels are retained as a shared image, never
dimmed or recolored to manufacture an independent dark variant. The browser
distinguishes shared artwork from a limited/incomplete metadata response.

Bundled artwork is reproduced by `scripts/prepare-apple-system-icons.py`.
`--only siri wallet passwords` updates those roles while retaining the other
hash-verified assets. The manifest records catalog hashes, actual appearance
codes, 1024px source dimensions and packaged WebP hashes.

## Request and cache policy

New configurations derive the default storefront from the system locale's
explicit region, then its language/script when no region is present. Existing
saved options are not rewritten. For a new mapping/search, a name consisting of
Han characters and punctuation prefers CN; mixed Latin/numeric names retain the
selected region. An existing mapping's region or a region explicitly chosen in
the search editor always wins. This is a character heuristic, not language
identification: some Han-only Japanese names can also prefer CN, and the visible
region chooser remains available. Search cache keys and artwork identities retain
the effective storefront, so results from different regions are not mixed.

- Group pending metadata by the mapping's own storefront and platform, deduplicate
  App Store IDs, and request at most 25 IDs per catalog batch.
- Prefer the public catalog collection endpoint (`apps?ids=...`). The older
  single-resource endpoint returned HTTP 429 for Messages during investigation
  while the collection endpoint returned its layered artwork.
- Reuse published images and valid disk caches. Completed light/dark pairs and
  confirmed shared artwork are not queried again by ordinary generation or
  **Complete missing resources**.
- A limited legacy metadata record gets a one-time upgrade attempt. Subsequent
  ordinary retries have a 15-minute metadata cooldown, and server backoff is
  enforced independently.
- HTTP 429/503 backoff honors `Retry-After` within the client's bounded policy.
  Per-host cooldown/strike state is persisted in MMKV, including across app
  restarts. No retry loop, polling service or background download timer is added.
- A failed catalog batch can use one bulk standard lookup, rather than one
  fallback request per app. Failed or deferred items retain their previous
  published artwork when available.
- The UI shows the next retry time. Its single suspended deadline update performs
  no network request. The user explicitly resumes incomplete work.
- **Refresh artwork for this app** refreshes only that mapping's metadata and
  images. It marks the updated pair for the next incremental generation, leaving
  other apps cached. Applying the generated pack remains necessary.

See the research `acceptance.json` for the final APK, device identities,
instrumentation results and actual UI acceptance. Source inspection and desktop
API responses alone are not device acceptance.

## Additional static presets, 2026-11-08

This checkpoint adds fifteen mobile Apple roles: Books, Home, Apple Store
(retail), Research, Podcasts, Reminders, Stocks, Shortcuts, Measure, Magnifier,
Tips, Support, Preview, Freeform, and Maps. Exact English, Simplified/Traditional
Chinese, and Japanese names are accepted where appropriate. Generic role
matching still requires an Android system app. These mobile IDs are not used
as substitutes for unverified macOS artwork.

New exact OEM mappings:

| Android product | Apple product |
| --- | --- |
| AI Scanner (`com.xiaomi.scanner`) | Preview |
| Duokan (`com.duokan.reader`) | Apple Books |
| Xiaomi Shop (`com.xiaomi.shop`) | Apple Store, distinct from App Store |
| Xiaomi Health Research (`com.mi.healthresearch`) | Apple Research |
| Xiaomi Speaker (`com.xiaomi.mico`) | Home |
| Google Find Hub (`com.google.android.apps.adm`) | Find My |
| Xiaomi Storage (`com.xiaomi.station`) | Files; genuine Finder for macOS |
| Xiaomi mini-game hub (`com.xiaomi.tinygame`) | Games |

Mi Home (`com.xiaomi.smarthome`) deliberately has **no automatic Home mapping**,
including the Chinese name. Users may search for its own App Store product or
choose Home manually. Its existing custom mapping/exclusion is preserved.
Unrelated products such as Themes, Security, Xiaomi Community, roaming services,
earphone utilities, and migration tools are not forced into superficially
similar Apple roles.

Ten additional exact same-product bindings cover Google, Drive, Photos, Docs,
Sheets, Slides, Translate, Outlook, OneDrive, and Edge. A known Google Photos
package wins over an Android label such as "Photos"; it does not become Apple
Photos simply because it is preinstalled.

For automatic presets only, known empty CN responses for Google, Translate,
Research, Spotify, and ChatGPT select their verified US source. Other countries
and saved manual storefronts are preserved. This is a preset source hint, not a
general cross-region retry loop.

Apple identities were checked using bounded official iTunes lookup batches;
Maps and Preview also have official listings:
[Maps](https://apps.apple.com/us/app/apple-maps/id915056765),
[Preview](https://apps.apple.com/us/app/preview/id536344167).
No independent dark variant is invented when a source intentionally shares
artwork. Existing completed caches remain reusable.

Retained inventory evaluation increases iOS/iPadOS matches from 21 to 22 of
61 AVD targets, and from 32 to 42 of 187 physical-device targets. This is a
mapping count, not a claim that every live artwork request succeeded.
Evidence and final device acceptance:
`.artifacts/research/apple-preinstalled-20261108/acceptance.json`.

## Native Calendar/Clock and current-theme comparison, 2026-11-08

Calendar (`1108185179`) and Clock (`1584215688`) retain their product mappings
and now opt into two canonical live kinds for iOS/iPadOS selections. This does
not enable dynamic rendering for every Apple mapping or use mobile resources
for macOS.

The native layers come from iOS 27.3 Simulator `MobileCal` and `ClockAngel`
CoreUI catalogs, with source hashes and extraction provenance under
`app/src/main/assets/apple-dynamic-icons/`. Twenty records share ten deduplicated
lossless images, totalling **973,388 image bytes**. Calendar uses blank native
backgrounds; Clock uses native dials and full-canvas transparent hands normalized
offline. No Apple font is redistributed.

The templates have `frameRate="0"`. Calendar uses `DateTime.Minute` and localized
`EEEE`/`d`; Clock uses `DateTime.Second`, with second-aware hour/minute positions.
They add no settings binders or module-owned periodic timers. The native updater
still schedules the necessary ticks. Dynamic layers are at most 512px; a
1024px browsing snapshot is downsampled to 512px for native fallback handoff.
Template/resource admission is finite and strict, not a general MAML interpreter.

Apple previews are moment-in-time snapshots using the system language, default
time zone and current date/time. The Calendar text uses Android system fonts,
not pixel-identical SF typography. Neither a static browser snapshot nor its
fallback represents a continuously running preview of the live icon.

Cards now compare the resolved current theme with the Apple destination. The
current-theme side shows one icon when only one rendition is available or both
appearances are pixel-identical, and two for distinct decoded appearances.
It reads a complete, revision-matched applied
archive, with a **2 MiB result cache and at most two concurrent loads**. It does
not substitute APK artwork when the current themed image cannot be established.
An existing live-MAML icon cannot be previewed by this reader and is explicitly
unavailable; the Apple side may still display its labelled snapshot. Existing
download caches, per-mapping storefronts, overrides and exclusions remain intact.

The current implementation passes 788 unit tests and 44/44 focused device tests
(35 dynamic/repository and nine preview tests), including `ConvertedIconArchive`
inspection of 1024px-preview / 512px-handoff resources. The current-theme
comparison screenshot is accepted and actual Clock seconds advance.
Settled Phone AVD screens verify Chinese, English and Japanese weekdays,
light/dark artwork, and natural midnight from November 8 / Sunday to November 9 /
Monday. Midnight and the separate light/dark toggle preserve the same launcher
PID. Global locale changes caused graceful native exits and slow cold startup;
those transitions are explicitly separate from the normal-install/API 102 gate.
Time-zone rendering has injected device-test coverage, without a live
time-zone-switch claim. See
[the dynamic acceptance receipt](../.artifacts/research/apple-dynamic-20261108/acceptance.json)
for the final artifact, lifecycle sample, identity gate and restored state.
Detailed source, update and preview bounds are in
[Apple icon pack](apple-icon-pack.md#native-calendar-and-clock-2026-11-08).
