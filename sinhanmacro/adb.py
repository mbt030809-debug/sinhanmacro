"""ADB 래퍼. 폰 안의 Termux 에서 자기 자신(localhost)에 무선 ADB 로 접속해 화면을 제어."""
from __future__ import annotations

import re
import subprocess
from pathlib import Path

REMOTE_DUMP = "/sdcard/sinhanmacro_ui.xml"
_SAFE_TEXT = re.compile(r"^[A-Za-z0-9._@-]*$")


class AdbError(RuntimeError):
    pass


class Adb:
    def __init__(self, serial: str, binary: str = "adb", runner=None, timeout: int = 30):
        self.serial = serial
        self.bin = binary
        self._run = runner or subprocess.run
        self.timeout = timeout

    def _exec(self, *args: str, serial: bool = True, check: bool = True,
              timeout: int | None = None) -> bytes:
        cmd = [self.bin]
        if serial and self.serial:
            cmd += ["-s", self.serial]
        cmd += list(args)
        try:
            p = self._run(cmd, capture_output=True, timeout=timeout or self.timeout)
        except FileNotFoundError:
            raise AdbError("adb 없음 → pkg install android-tools") from None
        except subprocess.TimeoutExpired:
            raise AdbError(f"adb 시간초과: {' '.join(args)}") from None
        if check and p.returncode != 0:
            err = (p.stderr or b"").decode("utf-8", "replace").strip()
            raise AdbError(f"adb 실패({p.returncode}): {' '.join(args)} {err}")
        return p.stdout or b""

    def shell(self, *args: str, check: bool = True) -> str:
        return self._exec("shell", *args, check=check).decode("utf-8", "replace")

    # --- 연결 ---
    def connect(self) -> str:
        out = self._exec("connect", self.serial, serial=False).decode("utf-8", "replace").strip()
        if "connected" not in out:
            raise AdbError(out or "adb connect 실패")
        return out

    def pair(self, hostport: str, code: str) -> str:
        return self._exec("pair", hostport, code, serial=False).decode("utf-8", "replace").strip()

    # --- 입력 ---
    def tap(self, x: int, y: int) -> None:
        self.shell("input", "tap", str(x), str(y))

    def swipe(self, x1: int, y1: int, x2: int, y2: int, ms: int = 300) -> None:
        self.shell("input", "swipe", *(str(v) for v in (x1, y1, x2, y2, ms)))

    def keyevent(self, *codes: str) -> None:
        self.shell("input", "keyevent", *codes)

    def input_text(self, text: str) -> None:
        # adb input text 는 한글 불가 → 숫자/영문만 허용 (종목코드·수량·가격 용도)
        if not _SAFE_TEXT.match(text):
            raise AdbError(f"입력 불가 문자 포함: {text!r}")
        if text:
            self.shell("input", "text", text)

    # --- 앱 / 화면 ---
    def wake(self) -> None:
        self.shell("input", "keyevent", "KEYCODE_WAKEUP", check=False)
        self.shell("wm", "dismiss-keyguard", check=False)

    def start_app(self, package: str) -> None:
        self.shell("monkey", "-p", package, "-c", "android.intent.category.LAUNCHER", "1")

    def list_packages(self, keyword: str = "") -> list[str]:
        out = self.shell("pm", "list", "packages")
        pkgs = [ln.split(":", 1)[1].strip() for ln in out.splitlines() if ln.startswith("package:")]
        return [p for p in pkgs if keyword.lower() in p.lower()]

    def current_focus(self) -> str:
        out = self.shell("dumpsys", "window", check=False)
        for ln in out.splitlines():
            if "mCurrentFocus" in ln:
                return ln.strip()
        return ""

    def screencap(self, path: Path) -> Path:
        data = self._exec("exec-out", "screencap", "-p", timeout=60)
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def dump_ui(self) -> str:
        out = self.shell("uiautomator", "dump", REMOTE_DUMP, check=False)
        if "ERROR" in out:
            return ""
        return self._exec("exec-out", "cat", REMOTE_DUMP).decode("utf-8", "replace")
