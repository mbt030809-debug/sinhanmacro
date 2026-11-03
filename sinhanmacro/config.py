from __future__ import annotations

import json
from pathlib import Path

DEFAULTS: dict = {
    # true: 최종 "주문 확인" 버튼을 누르기 직전에 멈춤 (화면 캡처만 저장)
    "dry_run": True,
    "adb": {"bin": "adb", "serial": "localhost:5555"},
    "app": {
        "package": "",  # `find-package 신한` 으로 확인
        "launch_wait_sec": 6,
        "login_check": {},  # 로그인된 메인화면에만 있는 요소 (비우면 검사 생략)
    },
    "signal": {
        "source_packages": [],  # 신호를 보내는 앱의 패키지명 (알림 기준)
        "title_regex": "",
        "poll_sec": 5,
        "skip_existing_on_start": True,  # 시작 시점에 이미 떠 있는 알림은 무시
        "buy_words": ["매수", "BUY"],
        "sell_words": ["매도", "SELL"],
    },
    # 허용 종목 화이트리스트: {"005930": {"name": "삼성전자", "qty": 1, "max_qty": 3}}
    "symbols": {},
    # market: 시장가 / limit_from_signal: 신호에 가격이 있어야 하며 지정가 주문
    "order": {"price_mode": "market"},
    "risk": {
        "max_orders_per_day": 5,
        "dedupe_minutes": 30,
        "enforce_trading_hours": True,
        "trading_hours": ["09:00", "15:20"],
    },
    # flows.buy / flows.sell: {"steps": [...], "success": {selector}}
    "flows": {},
    "telegram": {"token": "", "chat_id": ""},
    "paths": {
        "state": "state/state.json",
        "stop_file": "state/STOP",
        "logs": "logs",
        "shots": "shots",
    },
}


def _merge(base: dict, over: dict) -> dict:
    out = dict(base)
    for k, v in over.items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = _merge(out[k], v)
        else:
            out[k] = v
    return out


class Config:
    def __init__(self, data: dict, base: Path):
        self.data = data
        self.base = Path(base)

    def __getitem__(self, key: str):
        return self.data[key]

    def path(self, key: str) -> Path:
        return self.base / self.data["paths"][key]


def validate(data: dict) -> None:
    for code, s in data["symbols"].items():
        if not (len(code) == 6 and code.isdigit()):
            raise ValueError(f"symbols 키는 6자리 종목코드여야 함: {code!r}")
        if int(s.get("qty", 1)) <= 0 or int(s.get("max_qty", 1)) <= 0:
            raise ValueError(f"{code}: qty/max_qty 는 1 이상이어야 함")
    if data["order"]["price_mode"] not in ("market", "limit_from_signal"):
        raise ValueError("order.price_mode 는 market | limit_from_signal")


def load(path: str | Path) -> Config:
    path = Path(path)
    if not path.exists():
        raise FileNotFoundError(
            f"{path} 없음 → `cp config.example.json config.json` 후 값을 채우세요"
        )
    data = _merge(DEFAULTS, json.loads(path.read_text(encoding="utf-8")))
    validate(data)
    return Config(data, path.resolve().parent)
