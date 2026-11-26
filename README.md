# S-R — NIFTY Seller Intelligence

Android-first, read-only analysis of official NSE end-of-day NIFTY option-chain archives. The app fetches reports directly from allow-listed NSE/NSE Indices archive hosts, validates source files and contract identity, then keeps working reports in app-private cache. It does not use a hosted backend, request a broker login, or place trades.

## Analysis rules

- Select an NSE session date; the app requires all three official reports for that exact date.
- It scans backward for 21 additional report-complete sessions (including special sessions), validates the 22-session window, then offers only expiries contract-mapped throughout the entire window.
- The selected expiry is held constant. Analysis uses the first 17 sessions for recurring EOD OI/premium patterns, the next 4 for confirmation, and the selected final session as the target.
- S1/S2 and R1/R2 are descriptive, cross-sectional option-chain levels. Relative scores use equal-weight percentile ranks for OI, one-session OI change, volume, premium change, and same-side 17/4-session persistence.
- A rise in OI while premium falls is only a possible writer-activity proxy. Public EOD reports cannot identify a trader, establish intraday absorption/distribution, or predict price. No predictive ML is claimed.

## Privacy and reports

All requests use HTTPS and normal public archive URLs; the app does not bypass login, CAPTCHA, or access controls. Reports are held under private app cache only. Use **Delete temporary reports** in the app to review the file list and confirm deletion. No raw exchange file or user data is committed to this repository. The Python audit tool writes under `artifacts/nse/`, which is git-ignored.

## Validation and APK

The Android GitHub Actions workflow runs local unit tests, builds a release-variant APK signed with the runner's temporary Android debug certificate, verifies its signature, and uploads only that APK as a 30-day build artifact. Each CI build uses a fresh signing key, so it is suitable for a one-off install/test; a later build will require uninstalling the earlier copy before reinstalling.

The independent Python audit script downloads and validates a real 22-session source window using pandas, NumPy, requests, official report schemas, SHA-256 manifests, MII-to-Bhavcopy ID/expiry/strike/type checks, and NIFTY 50 index-date/OHLC checks. It never writes reports into source-control history. Run it only in a private temporary workspace and remove those temporary reports when you are done.

This is a development prototype, not investment advice or a production-grade trading system. Its EOD-only signals are descriptive and require independent validation before any financial use.
