"""다른 앱이 보내는 신호 텍스트 → Signal.

예) "삼성전자 매수 10주", "005930 SELL 5주 @70,000원"
- 종목은 config.symbols 화이트리스트에 있는 것만 인정 (코드 또는 이름)
- 매수/매도 키워드가 둘 다 있거나 둘 다 없으면 거부
"""
from __future__ import annotations

import re
from dataclasses import dataclass


class ParseError(Exception):
    pass


@dataclass(frozen=True)
class Signal:
    side: str  # "buy" | "sell"
    code: str
    qty: int
    price: int | None
    raw: str

    def __str__(self) -> str:
        p = f"@{self.price}" if self.price else "시장가"
        return f"{'매수' if self.side == 'buy' else '매도'} {self.code} {self.qty}주 {p}"


# 6자리 숫자 (가격 "100000원", "1,000,000" 과 혼동되지 않도록 제외)
_CODE_RE = re.compile(r"(?<![\d,])\d{6}(?![\d,]|\s*원)")
_QTY_RE = re.compile(r"(\d[\d,]*)\s*(?:주|EA|ea)(?![식문차간])")
_PRICE_RE = re.compile(
    r"(\d[\d,]*)\s*원|@\s*(\d[\d,]*)|(?:가격|단가|PRICE)\s*[:=]?\s*(\d[\d,]*)", re.I
)


def _to_int(s: str) -> int:
    return int(s.replace(",", ""))


def parse_signal(text: str, cfg) -> Signal:
    sig_cfg, symbols = cfg["signal"], cfg["symbols"]
    t = " ".join((text or "").split())
    if not t:
        raise ParseError("빈 텍스트")

    up = t.upper()
    buy = any(w.upper() in up for w in sig_cfg["buy_words"])
    sell = any(w.upper() in up for w in sig_cfg["sell_words"])
    if buy == sell:
        raise ParseError("매수/매도 구분 불가")
    side = "buy" if buy else "sell"

    codes = {c for c in _CODE_RE.findall(t) if c in symbols}
    for code, s in symbols.items():
        name = s.get("name")
        # "삼성전자우" 같은 우선주를 "삼성전자"로 오인하지 않도록 직후 '우'/영숫자 제외
        if name and re.search(re.escape(name) + r"(?!우|[0-9A-Za-z])", t):
            codes.add(code)
    if not codes:
        raise ParseError("허용 종목 없음")
    if len(codes) > 1:
        raise ParseError(f"종목 중복 인식: {sorted(codes)}")
    code = next(iter(codes))

    rest = re.sub(r"(?<![\d,])" + code + r"(?![\d,]|\s*원)", " ", t)

    m = _QTY_RE.search(rest)
    qty = _to_int(m.group(1)) if m else int(symbols[code].get("qty", 1))
    if qty <= 0:
        raise ParseError("수량 오류")

    m = _PRICE_RE.search(rest)
    price = None
    if m:
        price = _to_int(next(g for g in m.groups() if g))
        if price <= 0:
            raise ParseError("가격 오류")

    return Signal(side=side, code=code, qty=qty, price=price, raw=t)
