"""다른 앱의 알림을 신호로 수신 (Termux:API 의 termux-notification-list 폴링)."""
from __future__ import annotations

import hashlib
import json
import re
import subprocess


class NotificationListener:
    def __init__(self, cfg, state, log, runner=None):
        self.cfg, self.state, self.log = cfg, state, log
        self._run = runner or subprocess.run
        s = cfg["signal"]
        self.packages = set(s["source_packages"])
        self.title_re = re.compile(s["title_regex"]) if s["title_regex"] else None

    def fetch(self) -> list[dict]:
        try:
            p = self._run(["termux-notification-list"], capture_output=True, timeout=20)
        except FileNotFoundError:
            raise RuntimeError("termux-notification-list 없음 → pkg install termux-api + Termux:API 앱 설치") from None
        if p.returncode != 0:
            raise RuntimeError(f"termux-notification-list 실패: {(p.stderr or b'').decode()[:200]}")
        out = (p.stdout or b"").decode("utf-8", "replace").strip()
        return json.loads(out) if out else []

    @staticmethod
    def _key(n: dict) -> str:
        raw = "|".join(str(n.get(k, "")) for k in ("packageName", "title", "content", "when", "key"))
        return hashlib.sha1(raw.encode("utf-8")).hexdigest()

    def _mine(self, n: dict) -> bool:
        if n.get("packageName") not in self.packages:
            return False
        return not (self.title_re and not self.title_re.search(n.get("title") or ""))

    def prime(self) -> int:
        """시작 시점에 이미 떠 있는 알림은 '이미 본 것'으로 처리 (과거 신호 재실행 방지)."""
        n = 0
        for item in self.fetch():
            if self._mine(item):
                self.state.mark_seen(self._key(item))
                n += 1
        return n

    def poll(self) -> list[str]:
        """새로 도착한 신호 텍스트 목록."""
        texts = []
        for item in self.fetch():
            if not self._mine(item):
                continue
            key = self._key(item)
            if self.state.seen(key):
                continue
            self.state.mark_seen(key)
            texts.append(f"{item.get('title') or ''} {item.get('content') or ''}".strip())
        return texts
