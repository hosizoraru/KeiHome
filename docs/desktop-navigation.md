# Desktop categories

Desktop is a category overview rather than a full settings form:

| Page | Controls | Status |
| --- | --- | --- |
| Overview | Category navigation | Launcher connection only |
| Dock | Material, corners, height, spacing and offsets | Dock acknowledgement and capability only |
| Search and labels | Search-bar material and label display | Search material and label acknowledgements and capabilities |
| Icons and launch screens | Icon mask/effects, launch-background adaptation | Each control's saved value, availability and read/write failures |

The last two controls moved from Settings > System and Hook. Their persisted
keys and Hook behavior are unchanged. Global Settings search still finds their
existing controls; the normal scope page contains no functional switches.

Settings > Scope authorization is a read-only scope viewer, with authorization
refresh and an external module-manager shortcut. Grant requests remain available
through onboarding and the Status attention card. Authorization is separate
from target loading, native support and acknowledgement of a saved setting.

The overview never aggregates the acknowledgement counters of hidden
categories. Category status cards count only the controls on that page; an
unsupported Dock retains its native appearance without blocking another
category. A saved icon/launch choice is not presented as a native acknowledgement.

Serializable Desktop routes live on the main navigation stack. Back returns to
the same tab or, when opened from onboarding, the Desktop overview and then the
same wizard step. Each page owns its scroll state and OS4 top bar. Existing
bottom-navigation style selection and the Home tab icon are unchanged.

Observers stop below `STARTED`. The overview polls framework/connection state
without reading category settings. Each child reads only its own controls;
Icons and launch screens does not poll native launcher receipts. The scope
viewer reads only framework authorization, and existing Settings search retains
the broader observer required by searchable controls.

Focused verification:

```sh
python3 scripts/checks.py desktop-state --run
python3 scripts/checks.py ui-desktop --serial emulator-5580 --run
```

`DesktopNavigationTest` opens each category without changing a control, checks
the absence of functional switches and grant requests in the scope viewer,
and verifies the wizard's nested return path. Settings search and the affected
screens receive separate targeted checks.

Dock material now lives above Dock style/layout on the Dock page. Search
material has independent System/Light/Dark choices and Follow Dock. A missing
search preference follows Dock, preserving the old shared appearance. Protocol
10 carries the resolved pair; legacy protocol 9 still controls both together.
The optional capability 64 requires material capability 16. Runtime report
schema 8 appends the effective search acknowledgement and reads schemas 1–7.

Each section has one canonical ordered feature list for both control cards and
status pills. Native-receipt features display an indeterminate MIUIX linear
indicator under the edited option until acknowledgement, failure or a 30-second
timeout. A new choice or manual refresh starts a new wait. The two stored-only
controls use a saved-state summary, never a native-receipt claim. Stable help
stays in the control descriptions. Desktop status symbols use a fixed 80dp
square, with matching ring and checkmark proportions.

The overview includes a bounded short installed Launcher version, obtained via
PackageManager on IO at lifecycle/explicit-refresh boundaries. It does not load
APK contents or poll package metadata. Connection fault and version pills have
independent colors.

After authorized reinstallation and managed cold startup, the Phone AVD passed
the focused navigation/progress checks and live material-pair acknowledgements.
Schema-8 reports carry capabilities 127; independent values, Follow Dock and
restoration all used the same Launcher PID/start identity. See
`development-avd-recovery-20261111.md` for the test-selector rerun and separate
unresolved emulator host crashes. This does not establish physical-device or
warm-reboot stability.
