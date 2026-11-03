from __future__ import annotations

import argparse
import sys

from . import config as config_mod
from .adb import Adb, AdbError
from .app import App
from .parser import ParseError, parse_signal
from .util import now_kst, stamp


def _print_nodes(nodes, show_all: bool) -> None:
    for n in nodes:
        if not (show_all or n.text or n.desc or n.rid):
            continue
        flag = "탭" if n.clickable else "  "
        print(f"[{flag}] text={n.text!r} id={n.rid!r} desc={n.desc!r} center={n.center}")


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="sinhanmacro", description="신한투자증권 앱 자동매매 (안드로이드)")
    p.add_argument("-c", "--config", default="config.json")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("run", help="알림 감시 시작 (본 실행)")
    s = sub.add_parser("parse", help="신호 텍스트 파싱만 테스트")
    s.add_argument("text")
    s = sub.add_parser("inject", help="신호 텍스트를 수동 주입 (리스크 검사 → 실행)")
    s.add_argument("text")
    s.add_argument("--no-exec", action="store_true", help="검사까지만")
    s = sub.add_parser("dump", help="현재 화면 UI 요소 목록 (flow 작성용)")
    s.add_argument("--all", action="store_true", help="텍스트 없는 요소도 표시")
    sub.add_parser("shot", help="현재 화면 스크린샷")
    s = sub.add_parser("find-package", help="설치된 앱 패키지명 검색")
    s.add_argument("keyword")
    sub.add_parser("connect", help="adb connect")
    s = sub.add_parser("pair", help="adb pair HOST:PORT CODE")
    s.add_argument("hostport")
    s.add_argument("code")
    sub.add_parser("stop", help="킬스위치 ON (모든 주문 차단)")
    sub.add_parser("resume", help="킬스위치 OFF")
    sub.add_parser("status")
    a = p.parse_args(argv)

    try:
        cfg = config_mod.load(a.config)
    except (FileNotFoundError, ValueError) as e:
        print(f"설정 오류: {e}", file=sys.stderr)
        return 2

    stop_file = cfg.path("stop_file")
    try:
        if a.cmd == "stop":
            stop_file.parent.mkdir(parents=True, exist_ok=True)
            stop_file.write_text(now_kst().isoformat(), encoding="utf-8")
            print("킬스위치 ON")
        elif a.cmd == "resume":
            stop_file.unlink(missing_ok=True)
            print("킬스위치 OFF")
        elif a.cmd == "status":
            print(f"dry_run={cfg['dry_run']} 킬스위치={'ON' if stop_file.exists() else 'OFF'}")
            print(f"허용종목={list(cfg['symbols'])} 감시앱={cfg['signal']['source_packages']}")
        elif a.cmd == "parse":
            print(parse_signal(a.text, cfg))
        elif a.cmd in ("connect", "pair", "find-package", "shot", "dump"):
            adb = Adb(cfg["adb"]["serial"], cfg["adb"]["bin"])
            if a.cmd == "connect":
                print(adb.connect())
            elif a.cmd == "pair":
                print(adb.pair(a.hostport, a.code))
            elif a.cmd == "find-package":
                print("\n".join(adb.list_packages(a.keyword)) or "(없음)")
            elif a.cmd == "shot":
                print(adb.screencap(cfg.path("shots") / f"screen_{stamp()}.png"))
            else:
                from . import ui

                xml = adb.dump_ui()
                if not xml:
                    print("UI 덤프 실패 (보안화면이거나 앱이 접근을 막음)", file=sys.stderr)
                    return 1
                out = cfg.path("shots") / f"ui_dump_{stamp()}.xml"
                out.parent.mkdir(parents=True, exist_ok=True)
                out.write_text(xml, encoding="utf-8")
                print(adb.current_focus())
                _print_nodes(ui.parse(xml), a.all)
                print(f"저장: {out}")
        elif a.cmd == "inject":
            res = App(cfg).process_text(a.text, execute=not a.no_exec)
            print("결과:", res or "차단/무시/실패 (로그 확인)")
        elif a.cmd == "run":
            App(cfg).run()
    except ParseError as e:
        print(f"파싱 실패: {e}", file=sys.stderr)
        return 1
    except AdbError as e:
        print(f"ADB 오류: {e}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\n종료")
    return 0
