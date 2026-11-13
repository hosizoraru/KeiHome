# Apple matching retry history and HTTP cooldowns

## Report and diagnosis

The reported custom-mapping card shows five candidates for 123 Cloud Drive,
an attempt on November 13 at 09:58, and a retry date on November 20 at 09:58.
This seven-day interval matches `AppleAutoMatchPolicy.HISTORY_TTL_MS` in the
published 1.9.0 source (`70ea216`).

The old repository stored successful ambiguous searches as `REVIEW`, with that
seven-day deadline. The browser then treated any future attempt deadline as a
request cooldown, disabled matching, and printed "Retry from". A successful
search could therefore look like a week-long rate limit.

The screenshot establishes a local-history/UI defect. It does not establish
that Apple's servers returned HTTP 429. Real HTTP backoff is separate:
`AppleIconClient` persists per-host limits, handles Retry-After seconds and HTTP
dates, and uses bounded exponential backoff. No server-limit bypass was added.

## Resulting behavior

- Automatic batches retain seven-day deduplication.
- Explicit browser matching can retry saved reviews and misses immediately.
  Existing schema-1 records work directly; no data reset or destructive
  migration is needed.
- Explicit retries reuse search results for 15 minutes, then refresh them on
  demand. Repeated taps on a fresh result do not generate another HTTP request.
- Failed requests retain their retry deadlines. Both explicit and automatic
  batches stop while the shared HTTP cooldown is active, including after
  repository recreation.
- Saved candidates remain available in the existing editor. No automatic
  choice is made when candidates are ambiguous.
- English, Simplified Chinese and Japanese distinguish saved results from
  failed-request deadlines. They no longer describe successful search history
  as a mandatory wait.

## Focused validation

- 42 JVM cases: conservative matching, browser filters, and selection eligibility.
- 37 Android cases with fake transport: matching batches and HTTP scheduling.
  Added coverage includes reopening old seven-day review/miss records, reusing
  fresh candidates without another request, refreshing an expired manual search,
  and preventing explicit retries from bypassing failed-request/server backoff.
- Phone AVD diagnostic 109000456: ordinary API 102 Java handoff passed with
  unchanged critical process identities.

The tests do not issue live Apple requests or reproduce the reporter's network
environment. Logs and deployment receipts are retained under
`.artifacts/research/apple-retry-20261114/`.
