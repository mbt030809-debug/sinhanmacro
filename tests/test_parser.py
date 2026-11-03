import unittest

from helpers import make_cfg
from sinhanmacro.parser import ParseError, parse_signal


class ParserTest(unittest.TestCase):
    def setUp(self):
        self.cfg = make_cfg()

    def test_name_buy_with_qty(self):
        s = parse_signal("삼성전자 매수 10주", self.cfg)
        self.assertEqual((s.side, s.code, s.qty, s.price), ("buy", "005930", 10, None))

    def test_code_sell_with_price(self):
        s = parse_signal("005930 SELL 5주 @70,000원", self.cfg)
        self.assertEqual((s.side, s.code, s.qty, s.price), ("sell", "005930", 5, 70000))

    def test_default_qty(self):
        self.assertEqual(parse_signal("삼성전자 매수", self.cfg).qty, 2)

    def test_six_digit_price_not_confused_with_code(self):
        s = parse_signal("삼성전자 매수 1주 100000원", self.cfg)
        self.assertEqual((s.qty, s.price), (1, 100000))

    def test_code_digits_not_used_as_qty(self):
        s = parse_signal("005930 주가 상승 매수", self.cfg)
        self.assertEqual(s.qty, 2)

    def test_rejects(self):
        for text in [
            "",
            "삼성전자 관망",  # 방향 없음
            "삼성전자 매수 후 매도",  # 방향 모호
            "카카오 매수 1주",  # 화이트리스트 밖
            "삼성전자 SK하이닉스 매수",  # 종목 2개
            "삼성전자우 매수 1주",  # 우선주 오인 방지
            "999999 매수",
        ]:
            with self.subTest(text=text), self.assertRaises(ParseError):
                parse_signal(text, self.cfg)


if __name__ == "__main__":
    unittest.main()
