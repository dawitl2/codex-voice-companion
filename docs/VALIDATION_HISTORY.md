# Historical validation evidence

This publication preserves the product's real development history. It is a privacy-sanitized continuation of earlier private development, not a newly repeated implementation. Author and committer identities were anonymized; personal setup reports and credentials are excluded. No intentional regressions or fabricated repair commits were added for presentation.

**No tests, builds, device installations or runtime checks were performed for this publication.** Results below describe earlier revisions. They do not certify the current source revision or every supported device.

## Strongest retained numeric evidence

| Check | Recorded result | Evidence reviewed for this publication |
| --- | --- | --- |
| Windows bridge regression | **150 passed, 0 failed, 0 skipped** | Retained runner output; sanitized counters in [bridge summary](evidence/bridge-summary.txt) |
| Android JVM unit suite | **94 passed, 0 failed/errors, 1 skipped** out of 95 cases | Aggregated retained JUnit XML across 14 report files; [numeric summary](evidence/android-junit-summary.json) |
| Earlier integrated bridge regression | **146 passed, 0 failed, 0 skipped** | Retained final-delivery runner output |
| Integrated native audio/text/incoming-call/cleanup acceptance | **1 test passed** | Retained final successful instrumentation verdict, 35.759 seconds |
| Active-call Stop all cleanup | **1 test passed** | Retained successful instrumentation verdict, 45.426 seconds |

The later written usability report records **152 bridge passes** and **95 Android unit passes**. Those higher figures were not independently corroborated by the retained raw artifacts reviewed here: the available bridge output ends at 150 passes, and the available XML contains 94 passes plus one skip. They are report-only figures, not the headline verified counts above. No new run was performed to resolve the discrepancy.

## Suite growth during private development

The following checkpoint counts were recorded in the earlier stage reports. Some raw runner outputs were also retained. These are overlapping regression suites: **do not add the rows together as a total of unique tests.**

| Development checkpoint | Bridge passes | Android unit passes | Separate protocol result |
| --- | ---: | ---: | --- |
| Local bridge | 43 | — | — |
| First native Android app | 43 | 33 | — |
| Private monitoring | 61 | 57 | 1 passed |
| Task notifications | 82 | 68 | 1 passed |
| Private audio | 106 | 77 | Reported separately |
| Local speech | 115 | 80 | 1 passed |
| Voice instructions | 129 | 86 | 1 passed |
| Task controls | 142 | 90 | 1 passed |
| Lifecycle/reliability | 142 | 92 | 1 passed |
| Integrated desktop/mobile delivery | 146 | 94 | Not a new protocol run |
| Later retained usability runner | 150 | — | — |

From monitoring onward, ordinary Android suites commonly skipped one protocol fixture because it required a separate launcher. Protocol counts above are separate executions, not additional ordinary-suite passes. The native and protocol fixtures sometimes used simulated task data; that is distinct from real Codex inference and Google push transport.

## Device evidence and unresolved cases

Earlier reports record a six-case Compose flow suite and three separate private-route/monitor/snapshot cases: nine successful executions at that checkpoint, not one combined nine-case report. Notification development also records a ten-case local invocation (six UI, three notification, one navigation), separate from actual Firebase registration and transport checks. Those repeated UI cases overlap earlier checkpoints.

Other recorded successes include real notification transport under selected background/network conditions, offline speech playback, local microphone-to-recognition behavior, and a confirmed voice instruction round trip. These observations are specific to their original revision and device setup. The public repository excludes raw transcripts, screenshots, pairing records and device identifiers.

There were also failed and partial runs. The reliability-stage report records failed full reliability/voice attempts and an FCM receipt/open race, alongside successful narrower network/setup checks. Earlier integrated-device attempts failed before the later successful acceptance verdict. A successful retry does not make every earlier attempt a pass.

No comprehensive certification is claimed for current full-screen incoming calls, newer Android versions, Bluetooth/wired routing, cellular-call interruption, long outages, long-duration operation, battery consumption or speech accuracy. Current setup controls and the generic Android client configuration were introduced after the cited broad suites. Build and connection observations from earlier delivery are not substitutes for those missing tests.

## Reproducing checks

The existing test sources are retained. [Building](BUILDING.md) lists the bridge, Android unit and instrumentation commands. Anyone reproducing results should use an authorized project and their own devices and credentials, then report the exact revision, skips, failures and simulated inputs.

There is no defensible single lifetime total of unique tests across all these overlapping runs. The public figures intentionally distinguish runner-confirmed counts, report-only counts, physical-device verdicts, and checks not performed.
