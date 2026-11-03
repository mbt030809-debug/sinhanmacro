"""uiautomator dump XML 파싱 + 셀렉터 매칭."""
from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass

_BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


@dataclass(frozen=True)
class Node:
    text: str
    rid: str
    desc: str
    cls: str
    pkg: str
    bounds: tuple
    clickable: bool
    enabled: bool

    @property
    def center(self) -> tuple[int, int]:
        left, top, right, bottom = self.bounds
        return (left + right) // 2, (top + bottom) // 2


def parse(xml: str) -> list[Node]:
    if not xml or not xml.strip():
        return []
    try:
        root = ET.fromstring(xml.strip())
    except ET.ParseError:
        return []
    nodes = []
    for el in root.iter("node"):
        m = _BOUNDS.fullmatch(el.get("bounds", ""))
        if not m:
            continue
        nodes.append(
            Node(
                text=el.get("text", ""),
                rid=el.get("resource-id", ""),
                desc=el.get("content-desc", ""),
                cls=el.get("class", ""),
                pkg=el.get("package", ""),
                bounds=tuple(int(x) for x in m.groups()),
                clickable=el.get("clickable") == "true",
                enabled=el.get("enabled") == "true",
            )
        )
    return nodes


def matches(n: Node, sel: dict) -> bool:
    for k, v in sel.items():
        if k == "index" or k.startswith("_"):
            continue
        if k == "text":
            ok = n.text == v
        elif k == "text_contains":
            ok = v in n.text
        elif k == "text_regex":
            ok = re.search(v, n.text) is not None
        elif k == "id":
            ok = n.rid == v or n.rid.endswith("/" + v)
        elif k == "id_contains":
            ok = v in n.rid
        elif k == "desc":
            ok = n.desc == v
        elif k == "desc_contains":
            ok = v in n.desc
        elif k == "class":
            ok = n.cls == v or n.cls.endswith("." + v)
        elif k == "clickable":
            ok = n.clickable == bool(v)
        else:
            raise ValueError(f"알 수 없는 셀렉터 키: {k}")
        if not ok:
            return False
    return True


def find(nodes: list[Node], sel: dict) -> Node | None:
    if not any(not k.startswith("_") and k != "index" for k in sel):
        raise ValueError("빈 셀렉터")
    hits = [n for n in nodes if matches(n, sel)]
    idx = int(sel.get("index", 0))
    return hits[idx] if idx < len(hits) else None
