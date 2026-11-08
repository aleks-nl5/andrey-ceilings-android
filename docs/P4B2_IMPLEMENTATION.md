# P4B2 — transient individual quote, PR #112

Implements #110 under #44 in `feat/p4b2-transient-individual-estimate`.

## Behavior and boundaries

- `TransientQuotePresenter` uses a coherent `LivePricingReader` snapshot, source-bound persisted roles,
  the existing scoped `LivePricingEngine` and `IndividualEstimatePricing`. One genuine composition
  UUID and visit UUID fence the preview; request generations reject late responses.
- Confirmed optional work starts included. Discussing work starts excluded and requires explicit
  selection. Mandatory confirmed canvas/profile cannot be deselected. Confirmed omissions list the
  actual exclusion or unresolved reasons; an inline checkbox acknowledges the exact fingerprint.
- `BillableOverrideRules` isolates the removable optional-quantity experiment. Optional eligibility
  requires a validated exact MANUAL (or compatible ENTERED_LENGTH) quantity, NONE placement kind,
  no placed quantity and no point/line/area references. Derived/placed/unknown/range work is read-only.
  Canvas/profile retain captured source quantities; a confirmed billable override changes one line.
- All new numeric controls use the existing shared decimal editor. Partial/invalid/unconfirmed input
  hides monetary totals and clears acknowledgement. Integer pieces/sets reject fractional values.
  Inactive or disappeared override rows require explicit removal rather than silent rebinding.
- `QuoteDiscount` parses up to two percentage decimals into 0..10000 integer basis points. It rounds
  half-up once on selected subtotal using split integer arithmetic, including `Long.MAX_VALUE`.
  The fingerprint includes the visit, scoped preview, input spelling, percentage and resulting amounts.
- Details exposes a guarded “Попередній кошторис” action outside the existing P4B1 information card.
  Both hosts use the common screen. A fixed-height sticky summary hands off to the final summary
  using measured LazyColumn viewport/item bounds, opacity and translation. Only one final amount
  owns accessibility semantics during the handoff. Compose animation observes motion duration scale.
- `MeasurementQuoteSession` belongs to Android's living editor ViewModel / the desktop window guard.
  Accepted host navigation retains it across Details/Quote/Edit within one measurement and clears it
  when leaving or changing measurements/tabs. Reopening always reads again and clears acknowledgement.
  Rotation retains the ViewModel's composition inputs; route restoration opens Details, from which the
  retained quote can be reopened. New processes have a new guard and default quote inputs.
  DB-owner replacement and reader shutdown invalidate presenters immediately; no stale fallback.

No SQL definitions, schema versions, migrations, SQLCipher/WAL/backup code, measurement writers,
catalog prices, historical estimates, numbering, frozen drawings or PDF behavior change.
There is no Save action, autosave, quote status or financial state in navigation snapshots.

## Verification evidence

Added common tests in `TransientQuoteTest`, physical no-write/session scenarios in the existing
cross-platform `LivePricingProof`, Android `TransientQuoteTouchTest`, and desktop
`TransientQuoteSceneTest` (both themes, viewport handoff, resize and stale totals).
Existing physical proof entry points run on JDBC plus Android/Windows/iOS SQLCipher.
Existing coherent-read and reader-drain/restore tests remain required regressions.

Local attempt:

```
gradle --no-daemon :shared:jvmTest :desktopApp:test :app:testDebugUnitTest
```

**Blocked before execution:** Gradle is absent (`command not found`); downloads of required 9.7.0
from services.gradle.org and downloads.gradle.org both returned HTTP 504. JDK 21 is installed.
`git diff --check` passes. No local KMP/build/UI test success is claimed.

CI results and exact implementation SHA belong in the PR body. Green documentation-only runs on
`8466534` do not validate this implementation. iOS evidence is common/KMP and Simulator only;
there is no runnable full iOS UI host claim.

## Acceptance still required

- Same-final-SHA Android and Windows/iOS workflows, including physical encrypted DB tests.
- Independent code review and correction of blockers.
- Owner APK acceptance: both themes, discussing inclusion, exclusions/partial checkbox, source versus
  billable quantities/reset, leading decimals/partial edits, overall discount, Back/Edit/tab/reopen,
  short/long scrolling, keyboard, rotation, and accessibility/reduced-motion behavior.

Keep Draft. Only the owner accepts the APK, marks Ready, merges, or closes #110/#44.
