# Themes auto-apply authorization recovery

The physical audit warning at 2026-11-14 15:30:06.653 was an automatic-apply
authorization failure while delivering a saved imported receipt. It was not
another archive submission. Themes installed its new bridge at 15:30:06.864.
Previously, delivering the imported receipt completed the app's pending operation
without retaining an owner to retry authorization.

## Scope and behavior

`IconPackImportMonitor.Watch` keeps a small authorization state for its current
logical request and local icon ID. Authorization retries run synchronously on
the existing owned import worker, before terminal receipt delivery:

- One initial call and at most two retries, after 250 ms and 750 ms.
- A 15-second window limits admission of retries, not the duration of a running
  Binder call. The existing bridge bind itself can wait up to 10 seconds.
- Only `IOException` (including the private bridge-unavailable exception) and
  `RemoteException` retry. Every failed call reaches the existing error reporter.
  Other exceptions are reported and terminal; interruption propagates.
- A `false` reply is terminal. It can represent an already-consumed native
  ticket and must not be interpreted as a reason to re-arm it.
- Delivery errors and recheck nonce changes do not reset the logical request's
  authorization outcome or retry budget. Successful authorization is reused for
  report delivery; it does not execute the apply task.
- The current request, foreground user and worker state are checked before
  authorization work and after waits/calls. The original request's ten-minute
  freshness limit also gates authorization and token delivery.
- Expiry/exhaustion preserves the successful imported result, local identity and
  converted readiness, with an explicit automatic-application failure message.
  No archive import, reconciliation or proof registration is invoked by retries.

The durable journal and import submission logic are unchanged. A worker
replacement restores the journal and uses the existing persistent Themes
single-use gate; app navigation remains protected by the existing consumed-token
state. Neither Native code nor the app receipt/navigation implementation changes.

## Retirement

No timer, executor or extra thread owns retries. `stop()` closes the worker,
removes queued tasks and interrupts its wait. The worker's existing thread
remains the owner of an in-flight Binder call; `RestartableMonitor.awaitStopped`
cannot report retirement while that thread is alive. Late authorization results
cannot be delivered after closure.

Listener release now uses `MonitorRegistrations`. The looper quits only after
release succeeds; failed cleanup remains owned and keeps `cleanupComplete=false`
until an explicit stop retries it. Diagnostics failure does not erase registration
ownership. This follows the existing monitor cleanup contract.

## Focused JVM verification

The following focused run passed all 40 cases (15 authorization, 12 receipt and
13 monitor cases), with no failures, errors or skips:

```sh
./gradlew :app:testDebugUnitTest \
  --tests os.kei.hyper.home.xposed.IconAutoApplyAuthorizationTest \
  --tests os.kei.hyper.home.xposed.IconPackImportMonitorTest \
  --tests os.kei.hyper.home.xposed.RestartableMonitorTest
```

The new tests cover transient recovery and visible diagnostics; exhausted
attempt/time budgets; terminal consumed/declined and security/contract replies;
request expiry before/during work; request changes while waiting/in flight;
interrupted waits; mismatched receipt identities/conversion approval; cached
delivery and single-consumption app navigation; and refusal to retire while a
modeled uninterruptible Binder transaction still owns the worker.

Existing receipt tests cover archive/proof durability and replay. Existing monitor
tests cover failed listener release, repeated stop, and replacement admission.
Debug and AndroidTest compilation also passed. Raw build output and JUnit XML
are retained under `.artifacts/research/brand-dark-contrast-20261114/`.
The modeled blocked transaction verifies ownership; it does not reproduce or
certify recovery of the original physical-device request.
