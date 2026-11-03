"""로그 + (선택) 텔레그램 알림. 알림 실패는 매매 흐름에 영향 주지 않음."""
from __future__ import annotations

import json
import urllib.parse
import urllib.request


class Notifier:
    def __init__(self, tg_cfg: dict, log):
        self.token = tg_cfg.get("token", "")
        self.chat_id = tg_cfg.get("chat_id", "")
        self.log = log

    def send(self, msg: str) -> None:
        self.log.info("[알림] %s", msg)
        if not (self.token and self.chat_id):
            return
        try:
            data = urllib.parse.urlencode({"chat_id": self.chat_id, "text": msg}).encode()
            req = urllib.request.Request(
                f"https://api.telegram.org/bot{self.token}/sendMessage", data=data
            )
            with urllib.request.urlopen(req, timeout=10) as r:
                json.load(r)
        except Exception as e:
            self.log.warning("텔레그램 전송 실패: %s", e)
