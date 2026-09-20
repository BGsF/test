from __future__ import annotations

import os
import time
from dataclasses import dataclass
from typing import Any

import requests

BASE_URL = "https://api.kiwoom.com"


class KiwoomApiError(RuntimeError):
    pass


@dataclass
class Page:
    body: dict[str, Any]
    cont_yn: str | None
    next_key: str | None


class KiwoomClient:
    def __init__(self, app_key: str, app_secret: str, *, timeout: int = 30):
        if not app_key or not app_secret:
            raise ValueError("KIWOOM_APP_KEY / KIWOOM_APP_SECRET are required")
        self.app_key = app_key
        self.app_secret = app_secret
        self.timeout = timeout
        self.session = requests.Session()
        self._token: str | None = None

    @classmethod
    def from_env(cls) -> "KiwoomClient":
        return cls(
            os.environ.get("KIWOOM_APP_KEY", "").strip(),
            os.environ.get("KIWOOM_APP_SECRET", "").strip(),
        )

    def issue_token(self) -> str:
        r = self.session.post(
            BASE_URL + "/oauth2/token",
            json={
                "grant_type": "client_credentials",
                "appkey": self.app_key,
                "secretkey": self.app_secret,
            },
            headers={"Content-Type": "application/json;charset=UTF-8"},
            timeout=self.timeout,
        )
        r.raise_for_status()
        body = r.json()
        if body.get("return_code") not in (None, 0):
            raise KiwoomApiError(f"token error: {body}")
        token = body.get("token")
        if not token:
            raise KiwoomApiError(f"token missing: {body}")
        self._token = str(token)
        return self._token

    @property
    def token(self) -> str:
        return self._token or self.issue_token()

    def fetch_page(
        self,
        *,
        api_id: str,
        path: str,
        body: dict[str, Any],
        cont_yn: str | None = None,
        next_key: str | None = None,
        retry: bool = True,
    ) -> Page:
        headers = {
            "Content-Type": "application/json;charset=UTF-8",
            "api-id": api_id,
            "authorization": f"Bearer {self.token}",
        }
        if cont_yn is not None:
            headers["cont-yn"] = cont_yn
        if next_key is not None:
            headers["next-key"] = next_key

        r = self.session.post(
            BASE_URL + path,
            json=body,
            headers=headers,
            timeout=self.timeout,
        )
        try:
            data = r.json()
        except Exception:
            data = {"return_msg": r.text[:500]}

        auth_fail = r.status_code == 401 or str(data.get("return_code")) in {"8005"}
        if auth_fail and retry:
            self._token = None
            self.issue_token()
            return self.fetch_page(
                api_id=api_id, path=path, body=body,
                cont_yn=cont_yn, next_key=next_key, retry=False
            )

        if r.status_code >= 400:
            raise KiwoomApiError(f"HTTP {r.status_code}: {data}")
        if data.get("return_code") not in (None, 0):
            raise KiwoomApiError(
                f"{api_id} error {data.get('return_code')}: {data.get('return_msg')}"
            )
        return Page(
            body=data,
            cont_yn=r.headers.get("cont-yn"),
            next_key=r.headers.get("next-key"),
        )

    def iter_pages(
        self,
        *,
        api_id: str,
        path: str,
        body: dict[str, Any],
        max_pages: int,
        delay: float = 0.22,
    ):
        cont_yn = None
        next_key = None
        for _ in range(max_pages):
            page = self.fetch_page(
                api_id=api_id, path=path, body=body,
                cont_yn=cont_yn, next_key=next_key
            )
            yield page
            if page.cont_yn != "Y":
                break
            cont_yn, next_key = page.cont_yn, page.next_key
            time.sleep(delay)

    def get_universe(self) -> list[dict[str, str]]:
        out: dict[str, dict[str, str]] = {}
        for market, inds in (("0", "001"), ("1", "101")):
            body = {"mrkt_tp": market, "inds_cd": inds, "stex_tp": "1"}
            for page in self.iter_pages(
                api_id="ka20002", path="/api/dostk/sect", body=body, max_pages=10
            ):
                for row in page.body.get("inds_stkpc", []) or []:
                    if not isinstance(row, dict):
                        continue
                    code = str(row.get("stk_cd", "")).strip()
                    name = str(row.get("stk_nm", "")).strip()
                    if len(code) == 6 and code.isdigit():
                        out[code] = {
                            "code": code,
                            "name": name or code,
                            "market": "KOSPI" if market == "0" else "KOSDAQ",
                        }
        return sorted(out.values(), key=lambda x: x["code"])

    def get_daily(self, code: str, base_date: str, *, max_pages: int) -> list[dict[str, Any]]:
        body = {"stk_cd": code, "base_dt": base_date, "upd_stkpc_tp": "1"}
        rows: list[dict[str, Any]] = []
        for page in self.iter_pages(
            api_id="ka10081", path="/api/dostk/chart", body=body, max_pages=max_pages
        ):
            records = page.body.get("stk_dt_pole_chart_qry", []) or []
            for row in records:
                if isinstance(row, dict):
                    rows.append(row)
        return rows
