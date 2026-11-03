import subprocess
import unittest

from helpers import node_xml
from sinhanmacro import ui
from sinhanmacro.adb import Adb, AdbError

XML = node_xml(
    ("주식주문", "x:id/menu_order", (0, 0, 200, 100)),
    ("매수", "x:id/btn_buy", (100, 200, 300, 300)),
    ("매수", "x:id/btn_buy2", (100, 400, 300, 500)),
)


class UiTest(unittest.TestCase):
    def test_parse_and_center(self):
        nodes = ui.parse(XML)
        self.assertEqual(len(nodes), 3)
        self.assertEqual(nodes[1].center, (200, 250))

    def test_selectors(self):
        nodes = ui.parse(XML)
        self.assertEqual(ui.find(nodes, {"text": "매수"}).rid, "x:id/btn_buy")
        self.assertEqual(ui.find(nodes, {"text": "매수", "index": 1}).rid, "x:id/btn_buy2")
        self.assertEqual(ui.find(nodes, {"id": "btn_buy2"}).center, (200, 450))
        self.assertIsNone(ui.find(nodes, {"text_contains": "없음"}))
        with self.assertRaises(ValueError):
            ui.find(nodes, {})
        with self.assertRaises(ValueError):
            ui.find(nodes, {"bogus": 1})

    def test_bad_xml(self):
        self.assertEqual(ui.parse(""), [])
        self.assertEqual(ui.parse("<not xml"), [])


class FakeRun:
    def __init__(self, stdout=b"", rc=0):
        self.calls, self.stdout, self.rc = [], stdout, rc

    def __call__(self, cmd, **kw):
        self.calls.append(cmd)
        return subprocess.CompletedProcess(cmd, self.rc, self.stdout, b"err")


class AdbTest(unittest.TestCase):
    def test_tap_command(self):
        run = FakeRun()
        Adb("localhost:5555", runner=run).tap(10, 20)
        self.assertEqual(run.calls[0], ["adb", "-s", "localhost:5555", "shell", "input", "tap", "10", "20"])

    def test_input_text_rejects_unsafe(self):
        adb = Adb("s", runner=FakeRun())
        adb.input_text("12345")
        for bad in ("삼성", "a b", "1;reboot", "$(id)"):
            with self.assertRaises(AdbError):
                adb.input_text(bad)

    def test_failure_raises(self):
        with self.assertRaises(AdbError):
            Adb("s", runner=FakeRun(rc=1)).tap(1, 1)

    def test_connect_requires_connected_output(self):
        with self.assertRaises(AdbError):
            Adb("localhost:5555", runner=FakeRun(b"failed to connect")).connect()
        self.assertIn("connected", Adb("localhost:5555", runner=FakeRun(b"connected to localhost:5555")).connect())

    def test_list_packages(self):
        out = b"package:com.shinhan.a\r\npackage:com.other\r\n"
        self.assertEqual(Adb("s", runner=FakeRun(out)).list_packages("shinhan"), ["com.shinhan.a"])


if __name__ == "__main__":
    unittest.main()
