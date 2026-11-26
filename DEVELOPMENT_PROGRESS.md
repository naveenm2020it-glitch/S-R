# S-R Development Progress

**Target:** Free, Android-first, historical NIFTY support/resistance and seller-intelligence app. Development is isolated to S-R; no production deployment, trading execution, paid data, or paid hosting.

## Repository inspection and architecture

**Status: Complete (26-Nov-2026).** S-R `main` contains one initial commit and only a README; there were no existing implementation files to preserve. The current work branch is `feat/nifty-seller-intelligence`. The reference repository was inspected for its official NSE archives, retry/validation, SHA-256 manifests, 22-session dataset, and data-provenance conventions. Its analytics remain unimplemented and are not treated as completed work. Its audited date window and reports are useful validation evidence, not a permanent runtime dependency.

**Architecture decision:** Native Kotlin Android app for offline/on-device operation, plus a standalone Python 3.12 downloader/validator for reproducible Ubuntu GitHub Actions acquisition and audit. The app will fetch official public NSE/NSE Indices archives directly when used; Actions artifacts are optional diagnostics/build outputs and are not an app database. No broker, cloud service, API token, access-control bypass, or live feed.

## Phases and evidence

| Phase | Status | Evidence / gate |
|---|---|---|
| 0 — Repository inspection and architecture | Complete | S-R had only the initial README; reference report patterns and scope were reviewed. Work is isolated on `feat/nifty-seller-intelligence`. |
| 1 — Official NSE acquisition | Complete for audited window | The Ubuntu-compatible Python downloader retrieved the 22-session window 2026-10-21 through 2026-11-19 from official archives. All 66 required source reports passed container checks (3/3 for every session); the manifest includes source URLs, sizes, hashes, and contract validation. |
| 2 — Historical validation and storage | Complete for audited window | Kotlin integration parsed the genuine report set across all 22 dates, validated contract mapping, and found a single expiry common to the full window. Fixed uniqueness validation to include expiry, strike, and option type because official chains include repeated strikes across expiries. |
| 3 — 22-session processing | Complete for audited window | Real-data test confirms 22 sessions and the required 17 learning / 4 confirmation / 1 target split; no missing dates were substituted. |
| 4 — Multi-strike seller evidence | In progress | Analysis executes on the official window. EOD OI increase with premium decline is only a possible-writing proxy; results are not verified ground truth for seller identity or intraday absorption/distribution. |
| 5 — Explainable S1/S2/R1/R2 | In progress | Transparent relative scoring is implemented, but levels are descriptive and have not been validated as predictive price targets. Unsupported evidence remains insufficient rather than fabricated. |
| 6 — Explainable ML | Deferred by evidence | No validated predictive model is justified by 22 sessions. The analysis is deterministic descriptive statistics, not a claimed ML forecast. |
| 7 — Android dashboard | In progress | Native dashboard compiles into the release package and includes date/expiry controls, report status, analysis, provenance and caveats. No emulator/device UI test was available in this environment. |
| 8 — Temporary data and deletion | In progress | Downloaded archives and manifests use app cache; user-confirmed deletion clears those files and in-memory analysis. Device-level deletion behavior still needs Android runtime verification. |
| 9 — Tests and bug fixing | In progress | `testDebugUnitTest` passes, including the genuine 22-session parse-and-analysis integration test and six total unit/integration cases. Downloader format/edge-case coverage is not exhaustive. |
| 10 — Release APK | In progress | `assembleRelease` produced a signed APK (`com.sr.niftyseller`, version 0.1.0), verified with APK Signature Scheme v2 and a temporary Android debug certificate. The first GitHub Actions run failed before creating any jobs/checks and produced no artifact or logs; retry/dispatch calls are denied by the current GitHub integration scope. The verified APK is available as a direct download; emulator verification remains unavailable. |

## Guardrails and known validation boundary

- Sources: official `archives.nseindia.com` / `nsearchives.nseindia.com` F&O Bhavcopy and MII contract archives, and the NSE All Indices daily CSV archive. Public participant-wise reports may be recorded only if their official availability, format and date coverage are validated; they are not assumed to identify individual option writers.
- NSE can change URLs/formats or deny requests. The app must show a clear incomplete/error state and must not bypass authentication, CAPTCHAs or terms.
- The reference's audited 22-session window is 21-Oct-2026 through 19-Nov-2026 (including the reported Muhurat session); the selected expiry still must be present and validated across all 22 sessions before analysis.
- A 22-session dataset is too small to claim dependable predictive ML. Deterministic transparent metrics and unsupervised similarity/clustering are appropriate only as descriptive confirmation; ML performance claims are out of scope without more genuine data and walk-forward tests.
- No phase is complete from code written alone. Update this table only with actual test/build evidence.
