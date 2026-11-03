import json
import subprocess
import unittest

from helpers import make_cfg, node_xml, quiet_log
from sinhanmacro.app import App
from sinhanmacro.executor import Executor, StepError
from sinhanmacro.listener import NotificationListener
from sinhanmacro.parser import Signal
from sinhanmacro.risk import State

SCREEN = node_xml(
    ("주식주문", "x:id/menu", (0, 0, 100, 100)),
    ("", "x:id/edit_qty", (0, 200, 100, 300)),
    ("", "x:id/edit_price", (0, 400, 100, 500)),
    ("매수주문", "x:id/btn_go", (200, 600, 400, 700)),
    ("주문이 접수되었습니다", "x:id/msg", (0, 800, 100, 900)),
)


class FakeAdb:
    def __init__(self, xml=SCREEN):
        self.xml, self.log = xml, []

    def wake(self): self.log.append(("wake",))
    def start_app(self, p): self.log.append(("start", p))
    def dump_ui(self): return self.xml
    def tap(self, x, y): self.log.append(("tap", x, y))
    def keyevent(self, *c): self.log.append(("key", c[0], len(c)))
    def input_text(self, t): self.log.append(("text", t))
    def swipe(self, *a): self.log.append(("swipe",))
    def screencap(self, path):
        self.log.append(("shot",))
        return path

    def taps(self):
        return [e[1:] for e in self.log if e[0] == "tap"]

    def texts(self):
        return [e[1] for e in self.log if e[0] == "text"]


def sig(price=None):
    return Signal("buy", "005930", 3, price, "raw")


def mk_exec(adb, **cfg_over):
    cfg = make_cfg(**cfg_over)
    return cfg, Executor(cfg, adb, quiet_log(), sleep=lambda s: None)


class ExecutorTest(unittest.TestCase):
    def test_dry_run_stops_before_final(self):
        adb = FakeAdb()
        _, ex = mk_exec(adb, dry_run=True)
        res = ex.execute(sig())
        self.assertEqual((res.status, res.submitted), ("dry_run", False))
        self.assertNotIn((300, 650), adb.taps())  # 매수주문 버튼 미클릭
        self.assertEqual(adb.texts(), ["3"])  # market 모드 → 가격 입력 step 건너뜀

    def test_live_submits_and_confirms(self):
        adb = FakeAdb()
        _, ex = mk_exec(adb, dry_run=False)
        res = ex.execute(sig())
        self.assertEqual((res.status, res.submitted), ("ok", True))
        self.assertEqual(adb.taps()[-1], (300, 650))

    def test_limit_mode_types_price(self):
        adb = FakeAdb()
        _, ex = mk_exec(adb, dry_run=False, order={"price_mode": "limit_from_signal"})
        ex.execute(sig(price=70000))
        self.assertEqual(adb.texts(), ["3", "70000"])

    def test_unconfirmed_when_no_success_text(self):
        adb = FakeAdb(node_xml(*[(t, r, b) for t, r, b in [
            ("주식주문", "x:id/menu", (0, 0, 100, 100)),
            ("", "x:id/edit_qty", (0, 200, 100, 300)),
            ("매수주문", "x:id/btn_go", (200, 600, 400, 700)),
        ]]))
        _, ex = mk_exec(adb, dry_run=False)
        # success 대기가 10초 걸리지 않도록 monotonic 대기 단축
        ex.cfg.data["flows"]["buy"]["success"] = {"text_contains": "접수"}
        import sinhanmacro.executor as m
        orig = m.time.monotonic
        t = [0.0]
        m.time.monotonic = lambda: (t.__setitem__(0, t[0] + 5), t[0])[1]
        try:
            res = ex.execute(sig())
        finally:
            m.time.monotonic = orig
        self.assertEqual((res.status, res.submitted), ("unconfirmed", True))

    def test_missing_element_raises_before_submit(self):
        adb = FakeAdb(node_xml(("엉뚱한화면", "", (0, 0, 10, 10))))
        _, ex = mk_exec(adb, dry_run=False)
        import sinhanmacro.executor as m
        orig = m.time.monotonic
        t = [0.0]
        m.time.monotonic = lambda: (t.__setitem__(0, t[0] + 10), t[0])[1]
        try:
            with self.assertRaises(StepError):
                ex.execute(sig())
        finally:
            m.time.monotonic = orig
        self.assertEqual(adb.taps(), [])

    def test_requires_final_step(self):
        cfg = make_cfg(dry_run=False)
        cfg.data["flows"]["buy"]["steps"][-1].pop("final")
        ex = Executor(cfg, FakeAdb(), quiet_log(), sleep=lambda s: None)
        with self.assertRaises(StepError):
            ex.execute(sig())


class AppTest(unittest.TestCase):
    def mk(self, **over):
        over.setdefault("dry_run", False)
        cfg = make_cfg(**over)
        adb = FakeAdb()
        ex = Executor(cfg, adb, quiet_log(), sleep=lambda s: None)
        sent = []

        class N:
            def send(self, m): sent.append(m)

        return App(cfg, adb=adb, executor=ex, notifier=N(), log=quiet_log()), adb, sent

    def test_end_to_end_and_dedupe(self):
        app, adb, sent = self.mk()
        self.assertIsNotNone(app.process_text("삼성전자 매수 1주"))
        self.assertEqual(len(app.state.data["orders"]), 1)
        taps_before = len(adb.taps())
        self.assertIsNone(app.process_text("삼성전자 매수 1주"))  # 중복 → 차단
        self.assertEqual(len(adb.taps()), taps_before)
        self.assertTrue(any("중복" in m for m in sent))

    def test_dry_run_records_nothing(self):
        app, _, _ = self.mk(dry_run=True)
        app.process_text("삼성전자 매수 1주")
        self.assertEqual(app.state.data["orders"], [])

    def test_unparseable_is_ignored(self):
        app, adb, _ = self.mk()
        self.assertIsNone(app.process_text("오늘의 증시 브리핑"))
        self.assertEqual(adb.log, [])


class ListenerTest(unittest.TestCase):
    def test_prime_then_poll_only_new(self):
        cfg = make_cfg()
        state = State(cfg.path("state"))
        notes = [
            {"packageName": "com.test.signal", "title": "신호", "content": "삼성전자 매수", "when": "1"},
            {"packageName": "com.other", "title": "광고", "content": "매수 이벤트", "when": "2"},
        ]

        def runner(cmd, **kw):
            return subprocess.CompletedProcess(cmd, 0, json.dumps(notes).encode(), b"")

        lst = NotificationListener(cfg, state, quiet_log(), runner=runner)
        self.assertEqual(lst.prime(), 1)
        self.assertEqual(lst.poll(), [])  # 시작 시 존재하던 알림은 무시
        notes.append({"packageName": "com.test.signal", "title": "신호", "content": "삼성전자 매도", "when": "3"})
        self.assertEqual(lst.poll(), ["신호 삼성전자 매도"])
        self.assertEqual(lst.poll(), [])  # 재처리 없음


if __name__ == "__main__":
    unittest.main()
