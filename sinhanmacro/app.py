"""신호 수신 → 파싱 → 리스크 검사 → 주문 실행 파이프라인."""
from __future__ import annotations

import time

from .adb import Adb
from .executor import Executor, NotLoggedIn
from .listener import NotificationListener
from .notify import Notifier
from .parser import ParseError, Signal, parse_signal
from .risk import Risk, RiskError, State
from .util import now_kst, setup_logging


class App:
    def __init__(self, cfg, adb=None, executor=None, notifier=None, log=None):
        self.cfg = cfg
        self.log = log or setup_logging(cfg.path("logs"))
        self.state = State(cfg.path("state"))
        self.risk = Risk(cfg, self.state)
        self.adb = adb or Adb(cfg["adb"]["serial"], cfg["adb"]["bin"])
        self.executor = executor or Executor(cfg, self.adb, self.log)
        self.notifier = notifier or Notifier(cfg["telegram"], self.log)

    def process_text(self, text: str, execute: bool = True) -> Signal | None:
        """신호 텍스트 1건 처리. 거부/실패 시 None."""
        try:
            sig = parse_signal(text, self.cfg)
        except ParseError as e:
            self.log.info("무시(파싱): %s | %r", e, text[:80])
            return None

        now = now_kst()
        try:
            self.risk.check(sig, now)
        except RiskError as e:
            self.notifier.send(f"⛔ 주문 차단: {e} | {sig}")
            return None

        if not execute:
            self.log.info("검사 통과(실행 안 함): %s", sig)
            return sig

        dry = bool(self.cfg["dry_run"])
        self.notifier.send(f"{'[DRY] ' if dry else ''}주문 시작: {sig}")
        try:
            res = self.executor.execute(sig)
        except NotLoggedIn as e:
            self.notifier.send(f"🔒 로그인 필요 — 주문 중단: {e} | {sig}")
            return None
        except Exception as e:  # 제출 이전 실패 → 주문 안 나감
            self.notifier.send(f"❌ 주문 실패(제출 전): {e} | {sig}")
            return None

        if res.submitted:  # 제출했거나 제출 여부 불명 → 중복 방지 위해 기록
            self.state.add_order(sig, now)
        icon = {"ok": "✅", "unconfirmed": "⚠️ 체결/접수 확인 필요", "dry_run": "🧪"}[res.status]
        self.notifier.send(f"{icon} {res.status}: {sig} {res.shot}")
        return sig

    def run(self) -> None:
        sig_cfg = self.cfg["signal"]
        if not sig_cfg["source_packages"]:
            raise SystemExit("signal.source_packages 가 비어 있음 (신호 앱 패키지명 필요)")
        listener = NotificationListener(self.cfg, self.state, self.log)
        if sig_cfg["skip_existing_on_start"]:
            self.log.info("기존 알림 %d건 무시 처리", listener.prime())
        self.notifier.send(
            f"자동매매 시작 (dry_run={self.cfg['dry_run']}, 감시앱={sig_cfg['source_packages']})"
        )
        while True:
            try:
                for text in listener.poll():
                    self.process_text(text)
            except Exception as e:
                self.log.error("루프 오류: %s", e)
            time.sleep(float(sig_cfg["poll_sec"]))
