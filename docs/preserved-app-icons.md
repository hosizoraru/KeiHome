# Preserved application icons and official Telegram channels

The icon fallback page links to **Preserve original icons**. Entries are package
identities, not labels. The page displays installed application labels, package
names and original APK artwork, including the currently enabled launcher alias.
Missing saved packages remain removable.

## Publication and preview

The list is stored locally in `AppPreferences.preservedIconPackages`. Editing it
updates previews and the generation notice. Existing applied themes must be
generated and applied again; editing the list does not silently replace a theme.

For a preserved package:

- Explicit icon-pack mappings and automatic completion are both skipped.
- Converted exports omit its package and component resources.
- Native archives are copied and pruned by exact owner. Declared disabled aliases
  are included so changing an application's icon cannot revive an old resource.
- Shared foreign activity names retain their canonical `package#activity` owner.
  Preserving one Telegram channel does not remove another channel's resources.
- Generated schema 7 records the preserved set. Published system snapshots use
  that record, rather than uncommitted application preferences.
- A previously themed notification binding can replay fresh original artwork
  when a new published snapshot preserves its package.

Original artwork is read directly from installed resources. It is not read back
from the currently applied theme, and it does not receive the pending icon-pack
mask. Native Android adaptive-icon clipping is retained.

## Telegram completion

Only these application IDs form the official channel family:

`org.telegram.messenger`, `org.telegram.messenger.web`,
`org.telegram.messenger.beta`.

A missing default mapping may borrow a sibling mapping with authored dark
support. The same sibling supplies the light/dark pair, without invented
recoloring. Existing target mappings take precedence. Modern `DefaultIcon`,
legacy `org.telegram.ui.LaunchActivity`, and package-only default requests are
recognized. Vintage, Aqua, Premium, Turbo and Nox aliases, third-party forks and
Telegram X do not participate in this automatic borrowing.

APK resources with night qualifiers are checked in a separate night resource
session. Native donors are checked for actual native dark resources. Generated
resources use canonical component names; foreign legacy bare class names are
never written as channel aliases.

The applied-generation update check also recognizes a newly installed channel
as a completion candidate. A donor without dark support does not create an
unresolvable update notice.

## Work bounds

Application inventory, labels and artwork load on workers. Inventory events are
debounced and tied to the visible page. Alias identity participates in thumbnail
cache keys even when package version and timestamps are unchanged. Lists use
stable package keys and bounded thumbnail caching.

Generation takes a preserved-set snapshot. Disabled activity declarations are
read only while pruning native archives, not from rendering hooks. System icon
consumers use the existing bounded worker cache and coalesced replay. No new
scope, polling loop, login requirement or native patch is introduced.

Official-package/resource research is recorded in
[telegram-channel-icons.md](telegram-channel-icons.md).
