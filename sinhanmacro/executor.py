"""config.flows 의 단계(step)를 순서대로 실행해 신한앱에서 주문을 낸다.

step 종류 (do):
  tap      {"sel": {...}, "timeout": 8, "optional": false}  셀렉터가 나타나면 중앙 탭
  tap_xy   {"xy": [x, y]}                                     좌표 탭 (WebView 등 노드가 안 잡힐 때)
  type     {"sel": {...}?, "text": "{qty}", "clear": true}    (선택) 탭해서 포커스 → 지우기 → 입력
  wait     {"sec": 1}
  wait_for {"sel": {...}, "timeout": 10}
  key      {"code": "BACK"}                                   KEYCODE_ 접두사 생략 가능
  swipe    {"from": [x,y], "to": [x,y], "ms": 300}
  shot     {"name": "label"}                                  스크린샷 저장
공통 옵션:
  "when": "market" | "limit"  해당 가격모드일 때만 실행 (기본 always)
  "final": true               실제 주문 제출 단계. dry_run 이면 이 직전에 멈춤
템플릿 변수: {code} {name} {qty} {price} {side} (text, sel 값에 사용 가능)
"""
from __future__ import annotations

import time
from dataclasses import dataclass

from . import ui
from .parser import Signal
from .util import stamp


class StepError(RuntimeError):
    pass


class NotLoggedIn(RuntimeError):
    pass


@dataclass
class Result:
    status: str  # ok | unconfirmed | dry_run
    submitted: bool
    shot: str = ""


class Executor:
    def __init__(self, cfg, adb, log, sleep=time.sleep):
        self.cfg, self.adb, self.log, self.sleep = cfg, adb, log, sleep

    # --- 도우미 ---
    def _wait_node(self, sel: dict, timeout: float):
        deadline = time.monotonic() + timeout
        while True:
            node = ui.find(ui.parse(self.adb.dump_ui()), sel)
            if node is not None:
                return node
            if time.monotonic() >= deadline:
                return None
            self.sleep(0.8)

    @staticmethod
    def _fmt(value, vals):
        return value.format_map(vals) if isinstance(value, str) else value

    def _fmt_sel(self, sel: dict, vals: dict) -> dict:
        return {k: self._fmt(v, vals) for k, v in sel.items()}

    def _shot(self, label: str, sig: Signal) -> str:
        path = self.cfg.path("shots") / f"{label}_{sig.side}_{sig.code}_{stamp()}.png"
        try:
            self.adb.screencap(path)
            return str(path)
        except Exception as e:  # 캡처 실패가 주문 흐름을 막지 않도록
            self.log.warning("스크린샷 실패: %s", e)
            return ""

    def _need(self, step: dict, vals: dict):
        sel = self._fmt_sel(step["sel"], vals)
        node = self._wait_node(sel, float(step.get("timeout", 8)))
        if node is None and not step.get("optional"):
            raise StepError(f"요소를 찾지 못함: {sel}")
        return node

    def _do(self, step: dict, vals: dict) -> None:
        kind = step["do"]
        if kind == "tap":
            node = self._need(step, vals)
            if node:
                self.adb.tap(*node.center)
        elif kind == "tap_xy":
            self.adb.tap(*step["xy"])
        elif kind == "type":
            if "sel" in step:
                node = self._need(step, vals)
                if node is None:
                    return
                self.adb.tap(*node.center)
                self.sleep(0.3)
            if step.get("clear", True):
                n = int(step.get("clear_len", 12))
                self.adb.keyevent("KEYCODE_MOVE_END", *["KEYCODE_DEL"] * n)
            self.adb.input_text(self._fmt(step["text"], vals))
        elif kind == "wait":
            self.sleep(float(step.get("sec", 1)))
        elif kind == "wait_for":
            if self._wait_node(self._fmt_sel(step["sel"], vals), float(step.get("timeout", 10))) is None:
                raise StepError(f"화면 대기 실패: {step['sel']}")
        elif kind == "key":
            code = step["code"]
            self.adb.keyevent(code if code.startswith("KEYCODE_") else f"KEYCODE_{code}")
        elif kind == "swipe":
            self.adb.swipe(*step["from"], *step["to"], int(step.get("ms", 300)))
        elif kind == "shot":
            pass  # run() 에서 처리
        else:
            raise StepError(f"알 수 없는 step: {kind}")

    # --- 메인 ---
    def execute(self, sig: Signal) -> Result:
        flow = self.cfg["flows"].get(sig.side)
        if not flow or not flow.get("steps"):
            raise StepError(f"config.flows.{sig.side}.steps 가 비어 있음")
        steps = flow["steps"]
        if not any(s.get("final") for s in steps):
            raise StepError("flow 에 final:true 단계가 없음 (주문 제출 버튼 지정 필요)")

        dry = bool(self.cfg["dry_run"])
        mode = "limit" if self.cfg["order"]["price_mode"] == "limit_from_signal" else "market"
        sym = self.cfg["symbols"][sig.code]
        vals = {
            "code": sig.code, "name": sym.get("name", ""), "qty": str(sig.qty),
            "price": str(sig.price or ""), "side": sig.side,
        }

        pkg = self.cfg["app"]["package"]
        if not pkg:
            raise StepError("app.package 미설정 (`find-package` 로 확인)")
        self.adb.wake()
        self.adb.start_app(pkg)
        self.sleep(float(self.cfg["app"]["launch_wait_sec"]))

        check = self.cfg["app"]["login_check"]
        if check and self._wait_node(check, 10) is None:
            raise NotLoggedIn("로그인 화면/미로그인 상태로 보임 (login_check 요소 없음)")

        for i, step in enumerate(steps):
            when = step.get("when", "always")
            if when not in ("always", mode):
                continue
            if step.get("final"):
                before = self._shot("before_confirm", sig)
                if dry:
                    self.log.info("[DRY RUN] 최종 제출 직전 중단: %s", before)
                    return Result("dry_run", False, before)
                self._do(step, vals)
                return self._after_submit(flow, sig)
            self._do(step, vals)
            if step["do"] == "shot":
                self._shot(step.get("name", f"step{i}"), sig)
        raise StepError("final 단계에 도달하지 못함")  # 도달 불가 (위에서 검사)

    def _after_submit(self, flow: dict, sig: Signal) -> Result:
        """제출 이후 오류는 '주문이 나갔을 수 있음' → submitted=True 로 반환(중복 주문 방지)."""
        try:
            success = flow.get("success")
            ok = True
            if success:
                ok = self._wait_node(success, 10) is not None
            return Result("ok" if ok else "unconfirmed", True, self._shot("after_submit", sig))
        except Exception as e:
            self.log.error("제출 후 확인 중 오류: %s", e)
            return Result("unconfirmed", True, "")
