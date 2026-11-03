import unittest
from datetime import datetime, timedelta

from helpers import make_cfg
from sinhanmacro.parser import Signal
from sinhanmacro.risk import Risk, RiskError, State
from sinhanmacro.util import KST

MON_10AM = datetime(2026, 11, 2, 10, 0, tzinfo=KST)  # 2026-11-02 월요일


def sig(side="buy", code="005930", qty=1, price=None):
    return Signal(side, code, qty, price, "raw")


class RiskTest(unittest.TestCase):
    def mk(self, **over):
        cfg = make_cfg(**over)
        return cfg, Risk(cfg, State(cfg.path("state")))

    def test_ok(self):
        _, r = self.mk()
        r.check(sig(), MON_10AM)

    def test_qty_over_max(self):
        _, r = self.mk()
        with self.assertRaises(RiskError):
            r.check(sig(qty=6), MON_10AM)

    def test_trading_hours(self):
        _, r = self.mk(risk={"enforce_trading_hours": True})
        r.check(sig(), MON_10AM)
        with self.assertRaises(RiskError):
            r.check(sig(), MON_10AM.replace(hour=16))
        with self.assertRaises(RiskError):
            r.check(sig(), MON_10AM + timedelta(days=5))  # 토요일

    def test_dedupe_and_daily_limit(self):
        cfg, r = self.mk(risk={"enforce_trading_hours": False, "max_orders_per_day": 2})
        r.state.add_order(sig(), MON_10AM)
        with self.assertRaises(RiskError):  # 같은 방향·종목 30분 내
            r.check(sig(), MON_10AM + timedelta(minutes=10))
        r.check(sig(), MON_10AM + timedelta(minutes=31))  # 시간 지나면 OK
        r.state.add_order(sig("sell"), MON_10AM + timedelta(minutes=1))
        with self.assertRaises(RiskError):  # 일일 2건 초과
            r.check(sig(code="000660"), MON_10AM + timedelta(hours=2))

    def test_kill_switch(self):
        cfg, r = self.mk()
        stop = cfg.path("stop_file")
        stop.parent.mkdir(parents=True, exist_ok=True)
        stop.write_text("x")
        with self.assertRaises(RiskError):
            r.check(sig(), MON_10AM)

    def test_limit_mode_needs_price(self):
        _, r = self.mk(order={"price_mode": "limit_from_signal"})
        with self.assertRaises(RiskError):
            r.check(sig(), MON_10AM)
        r.check(sig(price=70000), MON_10AM)


if __name__ == "__main__":
    unittest.main()
