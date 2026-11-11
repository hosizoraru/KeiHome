# Perfect Icons: anonymous selective Git synchronization

The resource provider is the fixed `main` branch of
`https://github.com/pzcn/Perfect-Icons-Completion-Project.git`.
Production transport uses Eclipse JGit core `7.8.1.202611041320-r`
(EDL-1.0); it does not require GitHub credentials or a REST API quota.
The application never checks out or executes upstream scripts.

## User flow

1. Opening the page reads local state. Checking updates is explicit and obtains
   the advertised revision plus shallow `blob:none` metadata. It does not
   acquire image resources.
2. The directory index supplies available styles. A style can be selected before
   download. Its local readiness is independent of its remote availability.
3. Download/sync acquires the selected style and the union of styles already
   downloaded, using their actual resource dependencies. It reuses local
   content by Git blob identity. Changing styles does not silently use network.
4. Local previews, generation and system-app fallback use acquired resources.
   A newer remote commit does not disable a usable offline style. A style not
   acquired locally offers a download action instead of an empty preview.
5. Resource management opens in browsing mode with a shared status card.
   Tapping a style opens its download/update and removal actions. The top bar
   Select icon enters contextual multi-selection; only then do checkboxes
   and a single OS4 glass toolbar appear. Its five icon actions are select all,
   deselect all, download, remove and Done. Selection counts appear only in the
   title. Removal is red when enabled; all icons have localized accessible names.
   The top-right filter menu selects all or downloaded styles; it no longer
   occupies the list header. The current option is checked in the menu.
   Changing the filter clears selection. Back or Done exits selection before
   leaving the page. Submitted batch operations return to browsing; canceling
   a removal confirmation retains the selection.
6. Check updates remains in the top bar. The management menu exposes repair,
   cache clearing and full resource deletion directly. The status card and
   Version and source menu item open a MIUIX OS4 dialog with revision, sync,
   storage and attribution details. No source/storage expansion group or
   additional child page is needed. Destructive actions retain MIUIX OS4
   confirmation. New selection and refresh actions are disabled while busy.

The resource selection follows the existing formatter's rules: complete static
layer pairs, style-specific flat/layer overrides and complete native dynamic
groups. Common and base resources are dependencies rather than extra styles.
Identical objects are downloaded once even when multiple paths/styles refer to
them. Unselected styles' private image resources are not fetched.

## Publication and identity

The catalog lists both available and downloaded styles. Its entries contain
resource path, Git blob ID and size. The source hash is a SHA-256 over the
acquired graph and style set; it is not labelled as a Git commit. The actual
upstream commit is retained separately.

Sync builds a new index pinned to one commit, validates its dependencies and
resources, and atomically replaces `catalog.json`. An upstream deletion or
rename disappears from the new index; old cached content is never used to
silently resurrect a removed path. Normal fetch reuses valid local objects.
Repair acquires into a separate generation and preserves the old published
source on failure. The last-good source remains usable through interrupted or
failed acquisition.

Upstream commit date/subject, local last check and local last successful
resource sync are distinct. A check alone never advances the last successful
sync or clears its failure/interruption record. Relative-age badges update
while visible and stop their clock outside the foreground; they do not perform
periodic network checks.

## Storage and deletion

Source objects are read directly; there is no permanent checked-out tree and no
complete repository ZIP to regenerate on every update. Derived extraction,
rendering and preview caches remain independently clearable. Generated native
ZIPs and imported/applied packs are independent publications.

Unchanged revisions do not append duplicate object packs. When a changed
revision leaves more than 96 packs, or stored bytes exceed both 64 MiB and
150% of the current graph's expanded bytes, sync can compact the live graph
before publication. This keeps obsolete object versions bounded without
rewriting the entire local store on every small update.

Confirmed **delete resources** removes source metadata/objects, local catalog,
extracted resources, previews and derived caches under the Perfect source root.
It resets source/check/sync state, retains user options and preserves generated
ZIPs and imported/applied packs. The action is serialized against readers,
generation and sync. **Clear cache** retains source objects and readiness.
Neither operation clears unrelated application settings or data.

## Boundaries and validation

Checks and resource fetches use bounded streaming operations, fixed HTTPS
origin, no redirects/credential providers, cancellation and timeouts. The
metadata fetch rejects image blobs instead of silently accepting a full clone.
Object type, size and Git identity are verified; unsafe source paths, symlinks
and submodules are rejected. Repository scripts are data outside the selected
resource graph and are not run.

Android does not provide JMX or Kerberos classes referenced by JGit's desktop
paths. Release R8 rules explicitly disable file-cache JMX publication and
suppress only the concrete optional missing types. HTTP 401/407 are rejected
before JGit authentication selection, so SPNEGO is not an anonymous fallback.
The reflected `JGitText` constructor, class name and public string-field names
are retained so the bundled properties can be loaded in optimized builds.
There is no blanket keep/dontwarn rule for JGit. Release transport is checked
through the real application after shrinking.

Anonymous Git still depends on network/site availability. The transfer includes
protocol and metadata overhead; compressed image changes generally require
whole changed blobs. There is no promise of pixel-level deltas or a fixed speed
increase.

The focused acceptance evidence is retained under
`.artifacts/research/perfect-git-20261111/`. Repository tests cover local-only
work, metadata-versus-sync state, style readiness, replacement indexes, last-good
preservation, corruption and scoped deletion. Git transport tests exercise a
controlled multi-revision/multi-style fixture. Android acceptance additionally
uses an isolated cache root for real anonymous GitHub metadata and transport
checks. This is distinct from native icon animation or physical-device
acceptance.
