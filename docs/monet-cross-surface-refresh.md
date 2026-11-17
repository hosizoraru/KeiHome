# Monet icon publication and refresh

The applied theme archive and committed `keihome_maml_palette_state` value are
the common inputs for SystemUI, recent tasks, share, sidebar and Settings icon
caches. Appearance observers use each cache's existing worker. A duplicate
palette notification does not create another generation. Secure night-mode
changes are observed separately because a host can pin its configuration.

## Identity before presentation

SystemUI tracks authenticated application icons through Bitmap, Icon and
Drawable wrappers. Bitmap identity is preserved only for the exact source
object; an unrelated copy, avatar or content image clears a view's association.
Native current-user checks and PackageManager application metadata remain the
authority for package identity.

`AppIconsManager.getAppIconInner` is another shared application-icon producer.
Its installed call graph is verified against `loadAppIcon` and the native
folded-notification consumer. Its drawable results use the same prepared theme
snapshots as the bitmap producer. Native custom-icon branches are retained.

## Composite notifications

`FoldNotifControllerImpl.sendFoldNotification` draws multiple application
icons into a single RemoteViews bitmap. Retinting that final bitmap or rebinding
an ImageView cannot recover individual application identities.

The compatibility runtime records only the application snapshots consumed
during this native producer call. When those snapshots change, it coalesces a
request to the controller's existing `onMiuiThemeChanged` callback. The native
controller rebuilds its notification, preserving its count, ordering, click
action, user filtering, layout and custom images.

The owner is captured from native send calls. For an already running SystemUI,
the runtime also attempts to read the existing application factory, initialized
component and configuration-listener list. It never creates a second SystemUI
component or configuration controller. Private-member availability is reported;
when discovery is unavailable, native calls still establish ownership.

There is no permanent polling or additional worker. Pending module replay is
cancelled on retirement. Weak snapshot references and a bounded dependency map
avoid retaining bitmap allocations after the shared cache evicts them.

## Super Island

Default native application icons share this publication path. The installed
Market producer also copies its own application icon into two explicit island
picture slots. Only those verified identity slots for the current user are
adopted. Downloaded-app artwork, success marks, AOD images and arbitrary template
pictures stay under native ownership. See
[the island contract](super-island-app-icons-20261114.md).

## KeiHome launch surface

The own-app splash builder samples one committed palette for both the prepared
center icon and its background. The background keeps the declared light/dark
brightness class while adopting the effective palette hue. Disabling Monet
retains the declared background. Other applications' authored splash artwork
remains protected.

## Local inspiration delivery

The overview install action resolves its built-in source from the repository
when the optional catalog has not been loaded. Preparation has cancellation,
progress and error ownership; it does not silently return for an unopened
catalog. Result display is independent of catalog-page discovery.

Generated palette resources publish complete canonical day/night fallbacks.
Generic recoloring of system fallback artwork is accepted only with explicit
generation metadata and the existing exact template validation. The handoff is
fully inspected before native import. Invalid output does not create a
manually applicable native entry, and an earlier ineligible receipt leads to
regeneration guidance.
