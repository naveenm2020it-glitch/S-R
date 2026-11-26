#!/usr/bin/env python3
"""Acquire and validate a complete 22-session NSE EOD window; never substitute missing reports."""
from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import io
import json
import time
import zipfile
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlparse

import numpy as np
import pandas as pd
import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

FO = "https://archives.nseindia.com/content/fo"
INDEX_HOSTS = ("https://archives.nseindia.com", "https://nsearchives.nseindia.com")
OFFICIAL_HOSTS = {"archives.nseindia.com", "nsearchives.nseindia.com"}
MAX_BYTES = 80 * 1024 * 1024
ROOT = Path("artifacts/nse")


def session_with_retries() -> requests.Session:
    retry = Retry(total=3, connect=3, read=3, status=3, backoff_factor=0.7,
                  status_forcelist=(429, 500, 502, 503, 504), allowed_methods={"GET"},
                  respect_retry_after_header=True, raise_on_status=False)
    adapter = HTTPAdapter(max_retries=retry)
    client = requests.Session()
    client.mount("https://", adapter)
    client.headers.update({
        "User-Agent": "NIFTY-Seller-Intelligence/1.0 (official public archive client)",
        "Accept": "application/zip,application/gzip,text/csv,application/octet-stream,*/*",
        "Referer": "https://www.nseindia.com/",
    })
    return client


CLIENT = session_with_retries()


def fetch(urls: list[str], filename: str, report_type: str, trading_date: date, directory: Path) -> dict:
    failures = []
    for url in urls:
        try:
            with CLIENT.get(url, timeout=(15, 45), stream=True, allow_redirects=True) as response:
                final_host = urlparse(response.url).hostname
                if final_host not in OFFICIAL_HOSTS:
                    raise ValueError(f"Rejected redirect to non-official host: {final_host}")
                if response.status_code == 404:
                    failures.append({"url": url, "http_status": 404, "status": "NOT_PUBLISHED"})
                    continue
                if response.status_code in (401, 403):
                    raise RuntimeError(f"HTTP {response.status_code}; no authentication or access restriction was bypassed")
                if response.status_code != 200:
                    failures.append({"url": url, "http_status": response.status_code, "status": "HTTP_ERROR"})
                    continue
                content = bytearray()
                for chunk in response.iter_content(64 * 1024):
                    if chunk:
                        content.extend(chunk)
                        if len(content) > MAX_BYTES:
                            raise ValueError(f"Report exceeds {MAX_BYTES} byte safety limit")
                data = bytes(content)
                validate_container(data, filename, report_type)
                path = directory / filename
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
                return {
                    "type": report_type,
                    "status": "DOWNLOADED_AND_CONTAINER_VALIDATED",
                    "trading_date": trading_date.isoformat(),
                    "filename": filename,
                    "path": str(path),
                    "source_url": response.url,
                    "downloaded_at_utc": datetime.now(timezone.utc).isoformat(),
                    "bytes": len(data),
                    "sha256": hashlib.sha256(data).hexdigest(),
                    "content_type": response.headers.get("Content-Type"),
                }
        except Exception as exc:
            failures.append({"url": url, "status": "ERROR", "error": f"{type(exc).__name__}: {exc}"})
    raise FileNotFoundError(json.dumps({"report": report_type, "date": trading_date.isoformat(), "attempts": failures}))


def validate_container(data: bytes, filename: str, report_type: str) -> None:
    if filename.endswith(".zip"):
        if not data.startswith(b"PK"):
            raise ValueError("Bhavcopy response is not a ZIP archive")
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            members = [name for name in archive.namelist() if name.lower().endswith(".csv")]
            if len(members) != 1:
                raise ValueError(f"Expected exactly one CSV in Bhavcopy ZIP; found {len(members)}")
            if archive.testzip() is not None:
                raise ValueError("Bhavcopy ZIP CRC check failed")
            with archive.open(members[0]) as stream:
                header = next(csv.reader(io.TextIOWrapper(stream, encoding="utf-8-sig")))
    elif filename.endswith(".gz"):
        if not data.startswith(b"\x1f\x8b"):
            raise ValueError("MII response is not GZIP")
        with gzip.GzipFile(fileobj=io.BytesIO(data)) as stream:
            header = next(csv.reader(io.TextIOWrapper(stream, encoding="utf-8-sig")))
    else:
        header = next(csv.reader(io.StringIO(data.decode("utf-8-sig"))))
    columns = {normalize(column) for column in header}
    required = {
        "fo_bhavcopy": {"traddt", "fininstrmid", "tckrsymb", "optntp", "xprydt", "strkpric", "opnpric", "hghpric", "lwpric", "clspric", "ttltradgvol", "opnintrst"},
        "mii_contract": {"fininstrmid", "tckrsymb", "optntp", "xprydt", "strkpric"},
        "nifty_indices": {"indexname", "indexdate", "openindexvalue", "highindexvalue", "lowindexvalue", "closingindexvalue"},
    }[report_type]
    missing = required - columns
    if missing:
        raise ValueError(f"NSE report schema changed or is invalid; missing columns: {sorted(missing)}")


def normalize(value: str) -> str:
    return "".join(character.lower() for character in value.strip().lstrip("\ufeff") if character.isalnum())


def report_urls(trading_date: date) -> list[tuple[str, str, list[str]]]:
    ymd = trading_date.strftime("%Y%m%d")
    dmy = trading_date.strftime("%d%m%Y")
    return [
        ("fo_bhavcopy", f"BhavCopy_NSE_FO_0_0_0_{ymd}_F_0000.csv.zip", [f"{FO}/BhavCopy_NSE_FO_0_0_0_{ymd}_F_0000.csv.zip"]),
        ("mii_contract", f"NSE_FO_contract_{dmy}.csv.gz", [f"{FO}/NSE_FO_contract_{dmy}.csv.gz"]),
        ("nifty_indices", f"ind_close_all_{dmy}.csv", [f"{host}/content/indices/ind_close_all_{dmy}.csv" for host in INDEX_HOSTS]),
    ]


def acquire_day(trading_date: date) -> list[dict]:
    reports = []
    missing = []
    for kind, filename, urls in report_urls(trading_date):
        try:
            reports.append(fetch(urls, filename, kind, trading_date, ROOT / kind))
        except FileNotFoundError as error:
            payload = json.loads(str(error))
            attempts = payload["attempts"]
            if attempts and all(item.get("http_status") == 404 for item in attempts):
                missing.append(kind)
            else:
                raise
        time.sleep(0.15)
    if not reports:
        return []  # No official archive exists for this candidate; likely a non-session.
    if missing:
        raise FileNotFoundError(f"Incomplete official report set for {trading_date}; missing {', '.join(missing)}. No substitution is permitted.")
    return reports


def find_window(target: date) -> list[tuple[date, list[dict]]]:
    found = []
    for offset in range(91):
        current = target - timedelta(days=offset)
        try:
            reports = acquire_day(current)
        except Exception as error:
            raise RuntimeError(f"Cannot validate candidate {current}: {error}") from error
        if offset == 0 and not reports:
            raise RuntimeError(f"The selected analysis date {target} has no complete official report set; a different date will not be substituted.")
        if reports:
            found.append((current, reports))
            print(f"Validated archive containers for {current}: 3/3 reports")
            if len(found) == 22:
                break
    if len(found) != 22:
        raise RuntimeError(f"Only {len(found)} complete sessions found within 90 days; 22 are required.")
    return list(reversed(found))


def read_bhavcopy(path: str) -> pd.DataFrame:
    with zipfile.ZipFile(path) as archive:
        member = next(name for name in archive.namelist() if name.lower().endswith(".csv"))
        return pd.read_csv(archive.open(member), low_memory=False)


def validate_sessions(window: list[tuple[date, list[dict]]]) -> dict:
    prior_contracts: dict[str, tuple[pd.Timestamp, float, str]] = {}
    expiry_sets = []
    audit_rows = []
    for trading_date, reports in window:
        for report in reports:
            report_path = Path(report["path"])
            if not report_path.is_file() or report_path.stat().st_size != report["bytes"]:
                raise ValueError(f"Downloaded report is missing or changed: {report_path}")
            if hashlib.sha256(report_path.read_bytes()).hexdigest() != report["sha256"]:
                raise ValueError(f"Downloaded report hash mismatch: {report_path}")
        files = {item["type"]: item["path"] for item in reports}
        bhav = read_bhavcopy(files["fo_bhavcopy"])
        mii = pd.read_csv(files["mii_contract"], compression="gzip", low_memory=False)
        indices = pd.read_csv(files["nifty_indices"], low_memory=False)
        bh = {normalize(column): column for column in bhav.columns}
        mi = {normalize(column): column for column in mii.columns}
        ix = {normalize(column): column for column in indices.columns}
        date_column, symbol_column = bh["traddt"], bh["tckrsymb"]
        actual_dates = pd.to_datetime(bhav[date_column], errors="coerce").dt.date.dropna().unique()
        if set(actual_dates) != {trading_date}:
            raise ValueError(f"Bhavcopy trade date mismatch for {trading_date}: {actual_dates}")
        options = bhav[(bhav[symbol_column].astype(str).str.strip().str.upper() == "NIFTY") &
                       bhav[bh["optntp"]].astype(str).str.strip().str.upper().isin(("CE", "PE"))].copy()
        if options.empty:
            raise ValueError(f"No NIFTY CE/PE rows in Bhavcopy for {trading_date}")
        all_master_ids = pd.to_numeric(mii[mi["fininstrmid"]], errors="coerce").dropna()
        if all_master_ids.duplicated().any():
            raise ValueError(f"Duplicate instrument IDs in MII contract master on {trading_date}")
        all_bhav_ids = pd.to_numeric(bhav[bh["fininstrmid"]], errors="coerce").dropna()
        if all_bhav_ids.duplicated().any():
            raise ValueError(f"Duplicate instrument IDs in Bhavcopy on {trading_date}")
        numeric_columns = {
            "volume": pd.to_numeric(options[bh["ttltradgvol"]], errors="coerce").to_numpy(dtype=float),
            "open_interest": pd.to_numeric(options[bh["opnintrst"]], errors="coerce").to_numpy(dtype=float),
            "open": pd.to_numeric(options[bh["opnpric"]], errors="coerce").to_numpy(dtype=float),
            "high": pd.to_numeric(options[bh["hghpric"]], errors="coerce").to_numpy(dtype=float),
            "low": pd.to_numeric(options[bh["lwpric"]], errors="coerce").to_numpy(dtype=float),
            "close": pd.to_numeric(options[bh["clspric"]], errors="coerce").to_numpy(dtype=float),
        }
        if any(not np.isfinite(values).all() for values in numeric_columns.values()):
            raise ValueError(f"Non-finite NIFTY option values in Bhavcopy on {trading_date}")
        if (numeric_columns["volume"] < 0).any() or (numeric_columns["open_interest"] < 0).any():
            raise ValueError(f"Negative volume/open interest in Bhavcopy on {trading_date}")
        if any((numeric_columns[name] < 0).any() for name in ("open", "high", "low", "close")):
            raise ValueError(f"Negative option prices in Bhavcopy on {trading_date}")
        traded = numeric_columns["volume"] > 0
        if ((numeric_columns["high"] < np.maximum.reduce([numeric_columns[name] for name in ("open", "high", "low", "close")])) & traded).any():
            raise ValueError(f"Option high is below a same-day OHLC value on {trading_date}")
        if ((numeric_columns["low"] > np.minimum.reduce([numeric_columns[name] for name in ("open", "high", "low", "close")])) & traded).any():
            raise ValueError(f"Option low is above a same-day OHLC value on {trading_date}")
        master = mii[(mii[mi["tckrsymb"]].astype(str).str.strip().str.upper() == "NIFTY") &
                     mii[mi["optntp"]].astype(str).str.strip().str.upper().isin(("CE", "PE"))].copy()
        master["_id"] = pd.to_numeric(master[mi["fininstrmid"]], errors="coerce").astype("Int64")
        if master["_id"].duplicated().any():
            raise ValueError(f"Duplicate NIFTY MII instrument IDs on {trading_date}")
        master["_expiry"] = pd.to_datetime(pd.to_numeric(master[mi["xprydt"]], errors="coerce"), unit="s", origin="1980-01-01", errors="coerce").dt.date
        master["_strike"] = pd.to_numeric(master[mi["strkpric"]], errors="coerce") / 100.0
        master["_type"] = master[mi["optntp"]].astype(str).str.strip().str.upper()
        mapped = {str(int(row["_id"])): (row["_expiry"], float(row["_strike"]), row["_type"])
                  for _, row in master.dropna(subset=["_id", "_expiry", "_strike"]).iterrows()}
        bhav_ids = pd.to_numeric(options[bh["fininstrmid"]], errors="coerce").astype("Int64")
        if bhav_ids.duplicated().any():
            raise ValueError(f"Duplicate NIFTY Bhavcopy instrument IDs on {trading_date}")
        expiries = set()
        mapped_count = 0
        for (_, row), instrument_id in zip(options.iterrows(), bhav_ids):
            contract = mapped.get(str(int(instrument_id))) if pd.notna(instrument_id) else None
            if contract is None and pd.notna(instrument_id):
                contract = prior_contracts.get(str(int(instrument_id)))
            if contract is None:
                raise ValueError(f"Unmatched NIFTY contract ID {instrument_id} on {trading_date}")
            expiry = pd.to_datetime(row[bh["xprydt"]], errors="coerce")
            strike = pd.to_numeric(row[bh["strkpric"]], errors="coerce")
            option_type = str(row[bh["optntp"]]).strip().upper()
            if pd.isna(expiry) or expiry.date() != contract[0] or not np.isclose(float(strike), contract[1], atol=1e-8) or option_type != contract[2]:
                raise ValueError(f"MII/Bhavcopy contract identity mismatch for ID {instrument_id} on {trading_date}")
            expiries.add(contract[0].isoformat())
            mapped_count += 1
        prior_contracts = mapped
        ix_matches = indices[indices[ix["indexname"]].astype(str).str.strip().str.lower() == "nifty 50"]
        if len(ix_matches) != 1:
            raise ValueError(f"Expected exactly one NIFTY 50 index row on {trading_date}; found {len(ix_matches)}")
        ix_row = ix_matches.iloc[0]
        index_date = pd.to_datetime(ix_row[ix["indexdate"]], dayfirst=True, errors="coerce")
        if pd.isna(index_date) or index_date.date() != trading_date:
            raise ValueError(f"NIFTY 50 index date mismatch for {trading_date}")
        ohlc = [float(ix_row[ix[name]]) for name in ("openindexvalue", "highindexvalue", "lowindexvalue", "closingindexvalue")]
        if not all(np.isfinite(ohlc)) or ohlc[0] <= 0 or ohlc[1] < max(ohlc) or ohlc[2] > min(ohlc):
            raise ValueError(f"NIFTY 50 index OHLC validation failed for {trading_date}")
        expiry_sets.append(expiries)
        audit_rows.append({"trading_date": trading_date.isoformat(), "nifty_option_rows": len(options),
                           "contract_mappings_validated": mapped_count, "index_close": ohlc[3],
                           "available_expiries": sorted(expiries)})
    common_expiries = set.intersection(*expiry_sets)
    if not common_expiries:
        raise ValueError("No expiry is present and contract-mapped in all 22 sessions")
    return {"valid_sessions": len(audit_rows), "window": [r["trading_date"] for r in audit_rows],
            "common_expiries": sorted(common_expiries), "session_audit": audit_rows}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--analysis-date", required=True, help="Target NSE trading date (YYYY-MM-DD); no fallback is selected")
    args = parser.parse_args()
    target = date.fromisoformat(args.analysis_date)
    ROOT.mkdir(parents=True, exist_ok=True)
    window = find_window(target)
    validation = validate_sessions(window)
    manifest = {
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
        "source": "Official NSE/NSE Indices public archive hosts",
        "analysis_date": target.isoformat(),
        "session_rule": "22 complete dates including analysis date; first 17 learning, next 4 confirmation, final date target",
        "source_files": [file for _, files in window for file in files],
        "validation": validation,
        "missing_reports": [],
        "status": "COMPLETE_SOURCE_AND_CONTRACT_VALIDATION",
        "limitations": ["EOD open interest does not identify option writers", "no intraday absorption claim", "no predictive ML accuracy claim"],
    }
    manifest_path = ROOT / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps({"manifest": str(manifest_path), "sessions": validation["valid_sessions"],
                      "common_expiries": validation["common_expiries"], "report_files": len(manifest["source_files"])}, indent=2))


if __name__ == "__main__":
    main()
