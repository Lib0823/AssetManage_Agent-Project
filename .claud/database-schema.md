# Database Schema

> 상세 SQL: `database/database-erd.sql`
> ERD 다이어그램: `database/database-erd-diagram.md`

## 스키마 구성 (10개 스키마, 37개 테이블)

| 스키마 | 테이블 수 | 설명 |
|--------|-----------|------|
| auth | 4 | 인증/계정 |
| bot | 3 | 투자봇 |
| asset | 6 | 자산 (일별 스냅샷) |
| notification | 1 | 알림 |
| market | 4 | 시장 정보 (지수, 환율) |
| news | 3 | 뉴스 |
| stock | 5 | 종목 |
| trading | 3 | 거래 |
| settings | 2 | 설정 |
| company | 6 | 기업 상세 |

## 테이블 목록

### auth (인증/계정)
| 테이블 | 설명 | 관계 |
|--------|------|------|
| users | 사용자 기본 정보 | - |
| user_status | 사용자 상태 | users 1:1 |
| user_stock_accounts | 주식 계좌 (한투) | users 1:0..1 |
| user_coin_accounts | 코인 계좌 (업비트) | users 1:0..1 |

### bot (투자봇)
| 테이블 | 설명 | 관계 |
|--------|------|------|
| trading_bots | 투자봇 설정 | users 1:N |
| bot_positions | 매매 포지션 | trading_bots 1:N |
| bot_analyses | 분석 정보 | trading_bots 1:N |

### asset (자산)
| 테이블 | 설명 | 관계 |
|--------|------|------|
| asset_info | 자산 마스터 | users 1:1 |
| total_assets | 총 자산 (일별) | asset_info 1:N |
| cash_assets | 현금 (일별) | asset_info 1:N |
| stock_assets | 주식 (일별) | asset_info 1:N |
| bond_assets | 채권 (일별) | asset_info 1:N |
| coin_assets | 코인 (일별) | asset_info 1:N |

### notification (알림)
| 테이블 | 설명 | 관계 |
|--------|------|------|
| notifications | 통합 알림 (NEWS/TRADE/NOTICE) | users 1:N |

### market (시장 정보)
| 테이블 | 설명 | 비고 |
|--------|------|------|
| domestic_indices | 국내 지수 (일별) | KOSPI, KOSDAQ |
| foreign_indices | 해외 지수 (일별) | NASDAQ, S&P500, DOW |
| crypto_indices | 코인 지수 (일별) | CMI10, BDI |
| exchange_rates | 환율 (일별) | USD, JPY, CNY, EUR |

### news (뉴스)
| 테이블 | 설명 | 관계 |
|--------|------|------|
| news | 뉴스 목록 | - |
| news_details | 뉴스 상세 | news 1:1 |
| breaking_news | 속보 TOP5 | - |

### stock (종목)
| 테이블 | 설명 | 관계 |
|--------|------|------|
| stock_master | 종목 마스터 | - |
| holdings | 보유 종목 | users 1:N |
| watchlist | 관심 종목 | users 1:N |
| ai_recommendations | AI 추천 (일별) | - |
| stock_charts | 차트 데이터 (일별) | - |

### trading (거래)
| 테이블 | 설명 | 관계 |
|--------|------|------|
| pending_orders | 미채결 내역 | users 1:N |
| scheduled_orders | 예약 내역 | users 1:N |
| trade_history | 거래 내역 (체결) | users 1:N |

### settings (설정)
| 테이블 | 설명 | 관계 |
|--------|------|------|
| user_settings | 기본 설정 | users 1:1 |
| notification_settings | 알림 설정 | users 1:1 |

### company (기업 상세)
| 테이블 | 설명 | 관계 |
|--------|------|------|
| company_info | 기본 정보 | stock_master 1:0..1 |
| ai_analyses | AI 분석 | stock_master 1:0..1 |
| financial_statements | 재무제표 | stock_master 1:0..1 |
| dividend_disclosures | 배당 공시 | stock_master 1:N |
| stock_consolidations | 주식병합 공시 | stock_master 1:N |
| stock_splits | 주식분할 공시 | stock_master 1:N |

## 주요 Enum 값

| 필드 | 값 |
|------|-----|
| bot.type | STOCK, COIN |
| notification.type | NEWS, TRADE, NOTICE |
| notification.category | STOCK, COIN |
| news.category | DOMESTIC, FOREIGN, COIN |
| stock.market | DOMESTIC, FOREIGN |
| trading.order_type | BUY, SELL |
| trading.price_type | LIMIT, MARKET |
| trading.transaction_type | BUY, SELL, DIVIDEND |

## 데이터 타입 가이드

| 용도 | 타입 |
|------|------|
| 일반 금액 | DECIMAL(15,2) |
| 시가총액 | DECIMAL(20,2) |
| 환율 | DECIMAL(10,4) |
| 퍼센트 | DECIMAL(5,2) |
| 지수 | DECIMAL(10,2) |
| 코드 | VARCHAR(20) |
| 이름 | VARCHAR(100) |
| URL | VARCHAR(500) |
| 키워드 | VARCHAR(100)[] |

## 시계열 테이블 (파티셔닝 고려)

- asset.total_assets, cash_assets, stock_assets, bond_assets, coin_assets
- stock.stock_charts
- market.domestic_indices, foreign_indices, crypto_indices
