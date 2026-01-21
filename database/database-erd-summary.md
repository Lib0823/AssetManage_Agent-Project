# Asset Manage Agent - ERD 설계서

## 스키마 구성 (총 10개)

### 1. AUTH (인증/계정) - 4 테이블
```
auth.users                      # 사용자 기본 정보
auth.user_status                # 사용자 상태
auth.user_stock_accounts        # 주식 계좌
auth.user_coin_accounts         # 코인 계좌
```

**설계 포인트:**
- 계정 정보를 역할별로 분리 (정규화)
- 주식/코인 계좌는 선택적 (0..1 관계)
- 사용자 상태는 1:1 필수

### 2. BOT (투자봇) - 3 테이블
```
bot.trading_bots                # 투자봇 설정
bot.bot_positions               # 매매 포지션
bot.bot_analyses                # 분석 정보
```

**설계 포인트:**
- 1명의 사용자가 여러 봇 운영 가능
- 봇 타입으로 주식/코인 구분
- 포지션과 분석은 봇별로 관리

### 3. ASSET (자산) - 6 테이블
```
asset.asset_info                # 자산 마스터
asset.total_assets              # 총 자산 (일별)
asset.cash_assets               # 현금 (일별)
asset.stock_assets              # 주식 (일별)
asset.bond_assets               # 채권 (일별)
asset.coin_assets               # 코인 (일별)
```

**설계 포인트:**
- 자산 정보는 일별 스냅샷 형태
- 시계열 데이터로 추세 분석 가능
- 모든 자산은 asset_code로 연결

### 4. NOTIFICATION (알림) - 1 테이블
```
notification.notifications      # 알림 (통합)
```

**설계 포인트:**
- 뉴스/매매/공지 알림을 하나로 통합
- type 컬럼으로 구분 (NEWS, TRADE, NOTICE)
- 매매 알림은 거래 내역과 연결

### 5. MARKET (시장 정보) - 4 테이블
```
market.domestic_indices         # 국내 지수 (일별)
market.foreign_indices          # 해외 지수 (일별)
market.crypto_indices           # 코인 지수 (일별)
market.exchange_rates           # 환율 (일별)
```

**설계 포인트:**
- 모든 데이터는 일별 기준
- 날짜를 UNIQUE로 중복 방지
- 환율은 통화별로 구분

### 6. NEWS (뉴스) - 3 테이블
```
news.news                       # 뉴스 목록
news.news_details               # 뉴스 상세
news.breaking_news              # 속보 TOP5
```

**설계 포인트:**
- 목록과 상세를 분리 (성능 최적화)
- keywords는 배열 타입
- news_code는 날짜 기반 (YYMMDD-SEQ)

### 7. STOCK (종목) - 5 테이블
```
stock.ai_recommendations        # AI 추천 종목
stock.holdings                  # 보유 종목
stock.watchlist                 # 관심 종목
stock.stock_master              # 종목 마스터
stock.stock_charts              # 차트 데이터 (일별)
```

**설계 포인트:**
- stock_master가 종목의 기준 테이블
- 보유/관심 종목은 사용자별로 관리
- 차트는 시계열 데이터

### 8. TRADING (거래) - 3 테이블
```
trading.pending_orders          # 미채결 내역
trading.scheduled_orders        # 예약 내역
trading.trade_history           # 거래 내역
```

**설계 포인트:**
- 미채결 = 주문했지만 체결 안 됨
- 예약 = 예약 시간에 자동 실행
- 거래 내역 = 실제 체결된 기록

### 9. SETTINGS (설정) - 2 테이블
```
settings.user_settings          # 기본 설정
settings.notification_settings  # 알림 설정
```

**설계 포인트:**
- 사용자별 1:1 관계
- 설정 유형별로 테이블 분리
- favorite_asset_order는 배열

### 10. COMPANY (기업 상세) - 6 테이블
```
company.company_info            # 기본 정보
company.ai_analyses             # AI 분석
company.financial_statements    # 재무제표
company.dividend_disclosures    # 배당 공시
company.stock_consolidations    # 주식병합 공시
company.stock_splits            # 주식분할 공시
```

**설계 포인트:**
- stock_code 기준으로 연결
- 기본/분석/재무는 1:1
- 공시 정보는 1:N (여러 건 가능)

---

## 주요 관계 정리

### 1:1 관계
```
users ←→ user_status
users ←→ asset_info
users ←→ user_settings
users ←→ notification_settings
news ←→ news_details
```

### 1:0..1 관계 (선택적)
```
users ←→ user_stock_accounts
users ←→ user_coin_accounts
stock_master ←→ company_info
```

### 1:N 관계
```
users → trading_bots
users → holdings
users → watchlist
users → pending_orders
users → scheduled_orders
users → trade_history
users → notifications

trading_bots → bot_positions
trading_bots → bot_analyses

asset_info → total_assets
asset_info → cash_assets
asset_info → stock_assets

news → stock_code (간접)
```

---

## 데이터 타입 가이드

### 금액 관련
```sql
DECIMAL(15,2)  -- 일반 금액 (천억 단위까지)
DECIMAL(20,2)  -- 시가총액 등 큰 금액
DECIMAL(10,4)  -- 환율 (소수점 4자리)
```

### 비율/지수
```sql
DECIMAL(5,2)   -- 퍼센트 (-999.99 ~ 999.99)
DECIMAL(10,2)  -- 지수 (코스피 등)
DECIMAL(3,2)   -- 0~1 사이 값 (신뢰도 등)
```

### 텍스트
```sql
VARCHAR(20)    -- 코드 (종목코드, 통화 등)
VARCHAR(50)    -- ID, 번호 (주문번호 등)
VARCHAR(100)   -- 이름 (종목명, 회사명 등)
VARCHAR(500)   -- 제목, URL
TEXT           -- 긴 내용 (뉴스, 분석 등)
```

### 배열
```sql
VARCHAR(100)[]  -- 키워드 배열
```

---

## 인덱싱 전략

### 1. Primary Key
```sql
-- 모든 테이블
id BIGSERIAL PRIMARY KEY
```

### 2. Unique Index
```sql
-- 비즈니스 키
email, account_number, bot_code, news_code, order_number 등
```

### 3. Foreign Key Index
```sql
-- 조인 성능
user_id, bot_id, asset_code 등
```

### 4. 검색용 Index
```sql
-- 자주 검색하는 컬럼
stock_code, date, type, status, published_at 등
```

### 5. 복합 Index
```sql
-- 날짜 + 코드
CREATE INDEX idx_xxx ON table(date DESC, code);

-- 사용자 + 상태
CREATE INDEX idx_xxx ON table(user_id, status);
```

---

## 파티셔닝 고려 대상

**시계열 데이터 (대용량 예상):**
```sql
-- 월별 파티션
asset.total_assets
asset.cash_assets
asset.stock_assets
stock.stock_charts
market.domestic_indices
market.foreign_indices

-- 예시
CREATE TABLE asset.total_assets_2026_01 PARTITION OF asset.total_assets
  FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
```

---

## 특별한 설계

### 1. 날짜 형식 통일
```sql
DATE           -- 년-월-일 (2026-01-18)
TIMESTAMP      -- 년-월-일 시:분:초
```

### 2. 배열 타입 활용
```sql
-- PostgreSQL Array
keywords VARCHAR(100)[]
favorite_asset_order VARCHAR(100)[]
```

### 3. 외래키 전략
```sql
-- 계단식 삭제
ON DELETE CASCADE   -- 사용자 삭제 시 관련 데이터도 삭제

-- 참조 무결성만
ON DELETE RESTRICT  -- 참조 중일 때 삭제 불가
```

### 4. 코드 컬럼 네이밍
```sql
-- 비즈니스 키
bot_code, asset_code, news_code, notification_code

-- 외래키
user_id, bot_id, stock_code
```

---

## 테이블 총 개수

| 스키마 | 테이블 수 |
|--------|-----------|
| auth | 4 |
| bot | 3 |
| asset | 6 |
| notification | 1 |
| market | 4 |
| news | 3 |
| stock | 5 |
| trading | 3 |
| settings | 2 |
| company | 6 |
| **총계** | **37** |

---

## 다음 단계

1. ✅ ERD 설계 완료
2. ⏰ 외래키 점검
3. ⏰ 정규화 검토
4. ⏰ 성능 최적화 (파티셔닝, 인덱싱)
5. ⏰ 초기 데이터 준비
6. ⏰ Migration 스크립트 작성
