---
name: ai-pipeline-conventions
description: ai-agent(FastAPI ML 파이프라인) 코드를 작성/수정할 때 반드시 사용. Stage 0-6 일일 파이프라인 순서·데이터 의존성, StandardScaler 스코어링, KR-FinBERT 두 트랙 감성분석, Prophet 시계열, Gemini 프롬프트/파싱, 안전망 필터 임계값, venv 실행 요구사항을 다룬다. "파이프라인 수정", "필터링 로직", "감성분석", "Prophet", "Gemini 판단", "안전망 필터" 같은 요청에서 트리거된다.
---

# ai-pipeline 컨벤션

## 왜 이 규칙들이 존재하는가
파이프라인은 6개 Stage가 순서와 데이터 의존성을 가지고 하루 한 번(평일 08:50 KST) 실행된다. 한 Stage의 출력 컬럼명이나 가정을 바꾸면 다음 Stage가 조용히 잘못된 값을 만들거나 NULL을 저장한다 — 실행이 매일 1회뿐이라 문제 발견이 다음날로 늦어지는 것이 이 파이프라인 특유의 위험이다.

## venv는 선택이 아니다
시스템 python3로 직접 실행하면 Prophet이 깨져 `prophet_forecast`가 NULL로 저장된다. 반드시 `./run_dev.sh`(venv 자동 생성) 경유로 실행하거나, 이미 만들어진 venv를 활성화한 뒤 실행한다. 코드 리뷰나 실행 안내 시 이 조건을 빠뜨리지 않는다.

## Stage 순서와 컬럼 계약
```
Stage0 휴장체크 → Stage1 필터링(Top30) → Stage2 수집 → Stage2-1/2-2/2-3(병렬, 3-way) → Stage4 Gemini → Stage5 안전망 → Stage6 실행
```
- Stage1 출력 DataFrame 컬럼: `stock_code, stock_name, foreign_net_buy, institutional_net_buy, volume_ratio, price_volatility, final_score, is_selected`. 내부 컬럼명(`final_score`, `volume_ratio`)과 DB 컬럼명(`scaler_score`, `vol_avg_multiple`)이 다르다 — `DatabaseRepository.save_filter_scores`의 매핑을 거치지 않고 직접 컬럼명을 맞춰 쓰지 않는다.
- 보유 종목은 트리거 파라미터와 무관하게 최종 Top30에 **무조건 포함**된다(매도 분석을 보장하기 위해). 이 강제 포함 로직을 "최적화"랍시고 제거하지 않는다.
- Stage2-1/2-2/2-3은 서로 독립이며 Stage4에서 `stock_code` 기준 left join으로 병합된다. 하나의 분석기가 특정 종목에 대해 값을 못 냈다면 그 종목만 결측(None/NaN)으로 두고 전체를 실패시키지 않는다.

## StandardScaler 스코어링 (Stage 1)
```
score = |foreign_net_buy|*0.3 + |institutional_net_buy|*0.3 + vol_avg_multiple*0.3 + price_volatility*0.1
```
- 수급 지표(외국인/기관 순매수)는 절대값으로 정규화한다 — 순매수든 순매도든 "강한 움직임"으로 취급하기 위해서다. 부호를 그대로 쓰면 매도세가 강한 종목이 스코어에서 부당하게 낮게 나온다.
- StandardScaler는 **매일 당일 100종목 기준으로 새로 fit**한다(전일 기준 재사용 금지) — 그래야 일자 간 상대 비교가 의미를 가진다.

## 감성 분석 — 두 트랙을 혼동하지 않는다
| 트랙 | 수집 | 집계 | 저장처 |
|------|------|------|--------|
| 1 (시장 전반) | RSS(한국경제/매일경제/연합뉴스) | 전체 기사 단순 평균 | `market_daily_summary` |
| 2 (종목별) | 네이버 금융 뉴스 API, 종목당 최신 5건 | 시간 가중 평균(최신순 5,4,3,2,1) | `news_analysis` (Gemini 입력 `sentiment_score`) |

종목 뉴스 수집 실패 시 시장 감성으로 fallback하며 이때 `news_count=0`이 된다 — 이 fallback을 "버그"로 오인해 제거하지 않는다.

## Prophet 시계열 (Stage 2-3)
- 종목별 **독립 학습, 매일 새로 fit**, 모델 객체는 저장하지 않는다(DB에는 예측 결과만 저장, 원천 학습 데이터는 버림) — 매일 대상 종목이 바뀌어 누적 학습의 실익이 낮기 때문이다.
- `Prophet(daily_seasonality=False, weekly_seasonality=True, yearly_seasonality=False)`, `stan_backend=CMDSTANPY`. 120거래일 입력으로는 연간 계절성 학습이 불가능하다(250거래일 필요) — 이 설정을 "버그"로 보고 yearly를 켜지 않는다.
- 학습 가능 최소 조건: ≥10 포인트, 서로 다른 값 ≥2개. 이 조건 미달 시 실패 처리하며 임의 기본값으로 채우지 않는다.

## Gemini 의사결정 (Stage 4)
- 출력은 반드시 `buy_top3`/`sell_top3` 각 정확히 3개. `validate_decision`이 구조·개수·중복·매수매도 겹침을 검증한다 — 이 검증을 우회하는 파싱 코드를 추가하지 않는다.
- `GEMINI_API_KEY` 미설정 시 `_get_mock_decision()`으로 폴백하는 기존 동작을 유지한다(무료 티어 1일 1회 제한 때문에 개발 중 mock 경로가 필수).

## 안전망 필터 (Stage 5)
- 매수 승인은 11개 규칙을 **모두** 통과해야 한다(가격 불확실성, 수급 방향, 감성, 추세, PER/ROE/영업이익률, 모멘텀, 종가 위치). 이 임계값들은 `feature_threshold_config` DB 시드값과 반드시 일치시킨다 — 코드 기본값만 바꾸고 DB 시드를 안 바꾸면 실제 실행 시 다른 값이 적용된다.
- `user_trade_config.is_active=false`면 Stage6은 분석까지만 하고 매매를 실행하지 않는다(`status:'skipped'`) — 이 스킵을 에러로 취급하지 않는다.

## 테스트
- pytest, venv 내에서 실행. 변경 후 관련 테스트 스위트(`tests/`)를 실행하고 결과를 그대로 보고한다.
