# Perfect Icons connection and resource recovery

## Diagnosis

The physical-device report had two independent causes:

- Git HTTP requests to the original GitHub endpoint timed out in the actual
  application process. A successful host-shell request did not establish that
  Android's application transport could use the same route. Advertisement and
  `ls-refs` probes were bounded and ran separately from resource synchronization.
- Resource management previously depended on the overview to discover the full
  style directory. Entering directly from completion settings could show only
  the locally known defaults. Later sparse reads could also replace complete
  metadata with an incomplete result.

The transport remains anonymous selective JGit synchronization. It does not
replace Git with release APK downloads or repeatedly fetch repository ZIPs.

## Connection settings

The overview and resource manager offer the same OS4 editor:

- Empty acceleration uses the original repository.
- An HTTPS prefix or a template containing one `{url}` wraps the upstream URL.
  A complete HTTPS `.git` URL can also be supplied.
- A compatible service must support Git smart HTTP and filtered fetches.
  A service that only proxies downloadable files is insufficient.
- Routing can follow Android's system policy, explicitly connect directly, or
  use an unauthenticated HTTP or SOCKS proxy. Host and port are validated.
- The example can be filled explicitly. There is no automatic rotation through
  third-party services and no change to the device's network configuration.

Settings belong to this client. Each Git operation snapshots its validated
configuration once; changing a future connection does not reroute an active
transfer. No global JGit factory or process-wide proxy selector is installed.
In system mode, JGit's default DIRECT placeholder allows Android to resolve its
current URL/PAC policy. Explicit direct mode uses `Proxy.NO_PROXY`.

The selected HTTPS origin, port and the two Git request paths are checked on
each connection. Certificate and hostname validation remain enabled. Redirects,
credentials and cookies remain disabled; transfer budgets, cancellation, Git
object verification and atomic publication remain in force.

## Page lifecycle

All resumed resource pages reconcile local state. Direct resource entry also
bootstraps missing directory metadata, independently of the overview. Concurrent
requests coalesce and local reads cannot overwrite a newer active operation.
Complete style metadata survives sparse reads for the same revision.

Successful downloads/removals become visible when returning to the parent.
Missing-index failures can retry on a later resume after 30 seconds and any
server cooldown. There is no background polling loop. Offline downloaded styles
remain usable; an error does not erase the last published resource generation.

Failures, repair guidance and cooldown information appear in one OS4 card
inside resource management. The card disappears after recovery.

## Reference boundary

The user-supplied PerfectIconsPacker reference was inspected for network feature
behavior: selectable original/mirrored URLs and proxy options. This work uses
independent Kotlin implementation and the project's existing JGit transport;
no upstream implementation or assets were copied into KeiHome.

## Focused validation

Evidence is retained under
`.artifacts/research/perfect-network-20261113/`.

- 24 JVM cases cover connection validation/routing, HTTP endpoint restrictions,
  directory bootstrap, retry bounds, metadata retention and coalesced resumes.
- Five Android UI cases cover error/cooldown cards, large text, invalid
  configuration, cancel, and persist-before-retry behavior.
- Physical application-process diagnostics reproduce original-endpoint
  timeouts. With the explicitly selected acceleration example, the production
  resource flow downloads HyperOS4 and shows 1 of 22 styles ready.
- The captured physical result reports 75.27 MB total stored data and 75.76 MB
  logical style resources. These are different accounting measures, not a
  transfer-speed benchmark.
- Returning to icon completion settings recognizes HyperOS4 without manually
  refreshing the overview.

Actual measurements and APK/process identities are recorded separately in the
acceptance and deployment receipts. Success on this connection does not imply
that every network, mirror or proxy is available.
