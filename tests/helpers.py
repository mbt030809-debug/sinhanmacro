import json
import logging
import tempfile
from pathlib import Path

from sinhanmacro import config as config_mod

BASE_CFG = {
    "dry_run": True,
    "app": {"package": "com.test.shinhan", "launch_wait_sec": 0},
    "signal": {"source_packages": ["com.test.signal"]},
    "symbols": {
        "005930": {"name": "삼성전자", "qty": 2, "max_qty": 5},
        "000660": {"name": "SK하이닉스", "qty": 1, "max_qty": 2},
    },
    "risk": {"enforce_trading_hours": False},
    "flows": {
        "buy": {
            "steps": [
                {"do": "tap", "sel": {"text": "주식주문"}},
                {"do": "type", "sel": {"id_contains": "qty"}, "text": "{qty}"},
                {"do": "type", "when": "limit", "sel": {"id_contains": "price"}, "text": "{price}"},
                {"do": "tap", "sel": {"text": "매수주문"}, "final": True},
            ],
            "success": {"text_contains": "접수"},
        }
    },
}


def make_cfg(**over):
    tmp = Path(tempfile.mkdtemp())
    data = json.loads(json.dumps(BASE_CFG))
    for k, v in over.items():
        if isinstance(v, dict) and isinstance(data.get(k), dict):
            data[k].update(v)
        else:
            data[k] = v
    p = tmp / "config.json"
    p.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    return config_mod.load(p)


def quiet_log():
    log = logging.getLogger("sinhanmacro.test")
    log.handlers[:] = [logging.NullHandler()]
    log.propagate = False
    return log


def node_xml(*nodes):
    """nodes: (text, rid, bounds_tuple)"""
    inner = "".join(
        f'<node index="{i}" text="{t}" resource-id="{rid}" class="android.widget.Button" '
        f'package="x" content-desc="" clickable="true" enabled="true" '
        f'bounds="[{b[0]},{b[1]}][{b[2]},{b[3]}]" />'
        for i, (t, rid, b) in enumerate(nodes)
    )
    return f"<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">{inner}</hierarchy>"
