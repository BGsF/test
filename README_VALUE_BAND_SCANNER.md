# Value Band Scanner Android v1.0.3

Native Android implementation.

## Scan order
1. OHLCV only: find large lower volume-profile supports.
2. Filter stocks close to support and meaningfully down from recent highs.
3. Human/AI follow-up: determine whether the decline is due to fundamental impairment or external/flow factors.

## CSV columns
Required aliases:
- date / 일자 / 날짜
- open / 시가
- high / 고가
- low / 저가
- close / 종가 / 현재가
- volume / 거래량

Optional:
- code / 종목코드
- name / 종목명

If no code column exists, the filename is used as the symbol.
