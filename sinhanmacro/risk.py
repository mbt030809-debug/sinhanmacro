"""주문 전 안전 검사 + 상태(중복 방지, 일일 한도) 저장."""
from __future__ import annotations

import json
import os
from datetime import datetime, time
from pathlib import Path

from .parser import Signal


class RiskError(Exception):
    pass


class State:
    """state/state.json — 처리한 알림 키, 최근 주문 기록."""

    def __init__(self, path: Path):
        self.path = Path(path)
        self.data = {"seen": [], "orders": []}
        if self.path.exists():
            try:
                self.data.update(json.loads(self.path.read_text(encoding="utf-8")))
            except (ValueError, OSError):
                pass  # 손상된 파일은 새로 시작

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.data, ensure_ascii=False, indent=1), encoding="utf-8")
        os.replace(tmp, self.path)

    def seen(self, key: str) -> bool:
        return key in self.data["seen"]

    def mark_seen(self, key: str) -> None:
        if key not in self.data["seen"]:
            self.data["seen"].append(key)
            self.data["seen"] = self.data["seen"][-500:]
            self.save()

    def add_order(self, sig: Signal, now: datetime) -> None:
        self.data["orders"].append(
            {"ts": now.isoformat(), "side": sig.side, "code": sig.code, "qty": sig.qty}
        )
        self.data["orders"] = self.data["orders"][-200:]
        self.save()


def _hhmm(s: str) -> time:
    h, m = s.split(":")
    return time(int(h), int(m))


class Risk:
    def __init__(self, cfg, state: State):
        self.cfg = cfg
        self.state = state

    def killed(self) -> bool:
        return self.cfg.path("stop_file").exists()

    def check(self, sig: Signal, now: datetime) -> None:
        cfg = self.cfg
        if self.killed():
            raise RiskError("킬스위치(STOP) 활성")

        r = cfg["risk"]
        if r["enforce_trading_hours"]:
            start, end = (_hhmm(x) for x in r["trading_hours"])
            if now.weekday() >= 5 or not (start <= now.time().replace(tzinfo=None) <= end):
                raise RiskError(f"거래시간 외 ({r['trading_hours'][0]}~{r['trading_hours'][1]}, 평일)")

        max_qty = int(cfg["symbols"][sig.code].get("max_qty", 1))
        if sig.qty > max_qty:
            raise RiskError(f"수량 {sig.qty} > 종목 한도 {max_qty}")

        if cfg["order"]["price_mode"] == "limit_from_signal" and not sig.price:
            raise RiskError("지정가 모드인데 신호에 가격 없음")

        today = [o for o in self.state.data["orders"] if o["ts"][:10] == now.date().isoformat()]
        if len(today) >= int(r["max_orders_per_day"]):
            raise RiskError(f"일일 주문 한도 {r['max_orders_per_day']}건 초과")

        window = int(r["dedupe_minutes"]) * 60
        for o in reversed(self.state.data["orders"]):
            age = (now - datetime.fromisoformat(o["ts"])).total_seconds()
            if age > window:
                break
            if o["side"] == sig.side and o["code"] == sig.code:
                raise RiskError(f"중복 신호 ({r['dedupe_minutes']}분 내 동일 주문)")
