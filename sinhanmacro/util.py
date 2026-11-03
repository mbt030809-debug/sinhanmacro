from __future__ import annotations

import logging
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

KST = timezone(timedelta(hours=9))


def now_kst() -> datetime:
    return datetime.now(KST)


def stamp(dt: datetime | None = None) -> str:
    """파일명 접미사용 날짜_시각 (예: 20261103_091500)."""
    return (dt or now_kst()).strftime("%Y%m%d_%H%M%S")


class DatedFileHandler(logging.Handler):
    """logs/sinhanmacro_YYYYMMDD.log — 날짜가 바뀌면 새 파일로 전환."""

    def __init__(self, directory: Path, prefix: str = "sinhanmacro"):
        super().__init__()
        self.dir = Path(directory)
        self.prefix = prefix
        self._date: str | None = None
        self._fh = None

    def emit(self, record: logging.LogRecord) -> None:
        try:
            day = now_kst().strftime("%Y%m%d")
            if day != self._date:
                if self._fh:
                    self._fh.close()
                self.dir.mkdir(parents=True, exist_ok=True)
                self._fh = open(self.dir / f"{self.prefix}_{day}.log", "a", encoding="utf-8")
                self._date = day
            self._fh.write(self.format(record) + "\n")
            self._fh.flush()
        except Exception:
            self.handleError(record)


def setup_logging(log_dir: Path) -> logging.Logger:
    log = logging.getLogger("sinhanmacro")
    if log.handlers:
        return log
    log.setLevel(logging.INFO)
    fmt = logging.Formatter("%(asctime)s %(levelname)s %(message)s")
    for h in (DatedFileHandler(log_dir), logging.StreamHandler(sys.stdout)):
        h.setFormatter(fmt)
        log.addHandler(h)
    return log
