-- ============================================================
-- Asset Manage Agent - Database Schema
-- PostgreSQL 16
-- ============================================================

-- ============================================================
-- SCHEMA 생성
-- ============================================================

CREATE SCHEMA IF NOT EXISTS auth;          -- 인증/계정
CREATE SCHEMA IF NOT EXISTS bot;           -- 투자봇
CREATE SCHEMA IF NOT EXISTS asset;         -- 자산
CREATE SCHEMA IF NOT EXISTS notification;  -- 알림
CREATE SCHEMA IF NOT EXISTS market;        -- 시장 정보
CREATE SCHEMA IF NOT EXISTS news;          -- 뉴스
CREATE SCHEMA IF NOT EXISTS stock;         -- 종목
CREATE SCHEMA IF NOT EXISTS trading;       -- 거래
CREATE SCHEMA IF NOT EXISTS settings;      -- 설정
CREATE SCHEMA IF NOT EXISTS company;       -- 기업 상세

-- ============================================================
-- AUTH SCHEMA - 인증/계정
-- ============================================================

-- 계정 (기본)
CREATE TABLE auth.users (
  id BIGSERIAL PRIMARY KEY,
  email VARCHAR(100) NOT NULL UNIQUE,
  password VARCHAR(255) NOT NULL,
  name VARCHAR(50) NOT NULL,
  phone VARCHAR(20),
  birth_date DATE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_auth_users_email ON auth.users(email);

COMMENT ON TABLE auth.users IS '사용자 기본 정보';
COMMENT ON COLUMN auth.users.email IS '이메일 (로그인 ID)';
COMMENT ON COLUMN auth.users.password IS '암호화된 비밀번호';

-- 계정 - 상태
CREATE TABLE auth.user_status (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  password_changed_at TIMESTAMP,
  withdrawal_requested_at TIMESTAMP,
  stock_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  coin_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id)
);

CREATE INDEX idx_auth_user_status_user ON auth.user_status(user_id);

COMMENT ON TABLE auth.user_status IS '사용자 상태 정보';
COMMENT ON COLUMN auth.user_status.withdrawal_requested_at IS '회원 탈퇴 요청일';
COMMENT ON COLUMN auth.user_status.stock_enabled IS '주식 투자 여부';
COMMENT ON COLUMN auth.user_status.coin_enabled IS '코인 투자 여부';

-- 계정 - 주식
CREATE TABLE auth.user_stock_accounts (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  account_number VARCHAR(50) NOT NULL UNIQUE,
  app_key VARCHAR(255) NOT NULL,
  app_secret VARCHAR(255) NOT NULL,
  is_verified BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id)
);

CREATE INDEX idx_auth_user_stock_user ON auth.user_stock_accounts(user_id);
CREATE UNIQUE INDEX idx_auth_user_stock_account ON auth.user_stock_accounts(account_number);

COMMENT ON TABLE auth.user_stock_accounts IS '사용자 주식 계좌 정보';
COMMENT ON COLUMN auth.user_stock_accounts.app_key IS '한국투자증권 APP Key';
COMMENT ON COLUMN auth.user_stock_accounts.app_secret IS '한국투자증권 APP Secret';
COMMENT ON COLUMN auth.user_stock_accounts.is_verified IS '계좌 검증 여부';

-- 계정 - 코인
CREATE TABLE auth.user_coin_accounts (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  access_key VARCHAR(255),
  secret_key VARCHAR(255),
  is_verified BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id)
);

CREATE INDEX idx_auth_user_coin_user ON auth.user_coin_accounts(user_id);

COMMENT ON TABLE auth.user_coin_accounts IS '사용자 코인 계좌 정보';

-- ============================================================
-- BOT SCHEMA - 투자봇
-- ============================================================

-- 투자봇
CREATE TABLE bot.trading_bots (
  id BIGSERIAL PRIMARY KEY,
  bot_code VARCHAR(50) NOT NULL UNIQUE,
  user_id BIGINT NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  type VARCHAR(20) NOT NULL, -- STOCK, COIN
  is_active BOOLEAN NOT NULL DEFAULT FALSE,
  sell_on_deactivate BOOLEAN NOT NULL DEFAULT FALSE,
  investment_amount DECIMAL(15,2) NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_bot_trading_bots_code ON bot.trading_bots(bot_code);
CREATE INDEX idx_bot_trading_bots_user ON bot.trading_bots(user_id);
CREATE INDEX idx_bot_trading_bots_type ON bot.trading_bots(type);
CREATE INDEX idx_bot_trading_bots_active ON bot.trading_bots(is_active);

COMMENT ON TABLE bot.trading_bots IS '투자봇';
COMMENT ON COLUMN bot.trading_bots.type IS '유형 (STOCK, COIN)';
COMMENT ON COLUMN bot.trading_bots.is_active IS '활성화 여부';
COMMENT ON COLUMN bot.trading_bots.sell_on_deactivate IS '비활성화 시 매도 여부';

-- 투자봇 - 매매정보
CREATE TABLE bot.bot_positions (
  id BIGSERIAL PRIMARY KEY,
  position_code VARCHAR(50) NOT NULL UNIQUE,
  bot_id BIGINT NOT NULL REFERENCES bot.trading_bots(id) ON DELETE CASCADE,
  stock_code VARCHAR(20) NOT NULL,
  current_price DECIMAL(15,2) NOT NULL,
  total_purchase_amount DECIMAL(15,2) NOT NULL,
  profit_loss DECIMAL(15,2) NOT NULL,
  average_price DECIMAL(15,2) NOT NULL,
  quantity INT NOT NULL,
  profit_loss_rate DECIMAL(5,2) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_bot_positions_code ON bot.bot_positions(position_code);
CREATE INDEX idx_bot_positions_bot ON bot.bot_positions(bot_id);
CREATE INDEX idx_bot_positions_stock ON bot.bot_positions(stock_code);

COMMENT ON TABLE bot.bot_positions IS '투자봇 매매 정보';
COMMENT ON COLUMN bot.bot_positions.total_purchase_amount IS '총 매입금';
COMMENT ON COLUMN bot.bot_positions.profit_loss IS '평가손익';
COMMENT ON COLUMN bot.bot_positions.average_price IS '평균단가';

-- 투자봇 - 분석정보
CREATE TABLE bot.bot_analyses (
  id BIGSERIAL PRIMARY KEY,
  analysis_code VARCHAR(50) NOT NULL UNIQUE,
  bot_id BIGINT NOT NULL REFERENCES bot.trading_bots(id) ON DELETE CASCADE,
  stock_code VARCHAR(20) NOT NULL,
  analysis_subject VARCHAR(200) NOT NULL,
  analysis_content TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_bot_analyses_code ON bot.bot_analyses(analysis_code);
CREATE INDEX idx_bot_analyses_bot ON bot.bot_analyses(bot_id);
CREATE INDEX idx_bot_analyses_stock ON bot.bot_analyses(stock_code);

COMMENT ON TABLE bot.bot_analyses IS '투자봇 분석 정보';
COMMENT ON COLUMN bot.bot_analyses.analysis_subject IS '분석 주제';
COMMENT ON COLUMN bot.bot_analyses.analysis_content IS '분석 설명';

-- ============================================================
-- ASSET SCHEMA - 자산
-- ============================================================

-- 자산정보 (마스터)
CREATE TABLE asset.asset_info (
  id BIGSERIAL PRIMARY KEY,
  asset_code VARCHAR(50) NOT NULL UNIQUE,
  user_id BIGINT NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  last_updated_at TIMESTAMP NOT NULL,
  stock_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  bond_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  coin_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id)
);

CREATE UNIQUE INDEX idx_asset_info_code ON asset.asset_info(asset_code);
CREATE INDEX idx_asset_info_user ON asset.asset_info(user_id);

COMMENT ON TABLE asset.asset_info IS '자산 정보 마스터';
COMMENT ON COLUMN asset.asset_info.stock_enabled IS '주식 사용 여부';
COMMENT ON COLUMN asset.asset_info.bond_enabled IS '채권 사용 여부';
COMMENT ON COLUMN asset.asset_info.coin_enabled IS '코인 사용 여부';

-- 총 자산 (일별)
CREATE TABLE asset.total_assets (
  id BIGSERIAL PRIMARY KEY,
  date DATE NOT NULL,
  asset_code VARCHAR(50) NOT NULL REFERENCES asset.asset_info(asset_code) ON DELETE CASCADE,
  total_amount DECIMAL(15,2) NOT NULL,
  average_amount DECIMAL(15,2),
  previous_month_comparison DECIMAL(15,2),
  cash DECIMAL(15,2) NOT NULL DEFAULT 0,
  stock DECIMAL(15,2) NOT NULL DEFAULT 0,
  bond DECIMAL(15,2) NOT NULL DEFAULT 0,
  coin DECIMAL(15,2) NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(date, asset_code)
);

CREATE INDEX idx_asset_total_date ON asset.total_assets(date DESC);
CREATE INDEX idx_asset_total_code ON asset.total_assets(asset_code);

COMMENT ON TABLE asset.total_assets IS '총 자산 (일별)';
COMMENT ON COLUMN asset.total_assets.previous_month_comparison IS '전월 대비';

-- 현금 (일별)
CREATE TABLE asset.cash_assets (
  id BIGSERIAL PRIMARY KEY,
  date DATE NOT NULL,
  asset_code VARCHAR(50) NOT NULL REFERENCES asset.asset_info(asset_code) ON DELETE CASCADE,
  total_amount DECIMAL(15,2) NOT NULL,
  average_amount DECIMAL(15,2),
  previous_month_comparison DECIMAL(15,2),
  cash DECIMAL(15,2) NOT NULL DEFAULT 0,
  debt DECIMAL(15,2) NOT NULL DEFAULT 0,
  deposit DECIMAL(15,2) NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(date, asset_code)
);

CREATE INDEX idx_asset_cash_date ON asset.cash_assets(date DESC);
CREATE INDEX idx_asset_cash_code ON asset.cash_assets(asset_code);

COMMENT ON TABLE asset.cash_assets IS '현금 자산 (일별)';
COMMENT ON COLUMN asset.cash_assets.debt IS '부채';
COMMENT ON COLUMN asset.cash_assets.deposit IS '예수금';

-- 주식 (일별)
CREATE TABLE asset.stock_assets (
  id BIGSERIAL PRIMARY KEY,
  date DATE NOT NULL,
  asset_code VARCHAR(50) NOT NULL REFERENCES asset.asset_info(asset_code) ON DELETE CASCADE,
  total_amount DECIMAL(15,2) NOT NULL,
  average_amount DECIMAL(15,2),
  previous_month_comparison DECIMAL(15,2),
  domestic_total DECIMAL(15,2) NOT NULL DEFAULT 0,
  foreign_total DECIMAL(15,2) NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(date, asset_code)
);

CREATE INDEX idx_asset_stock_date ON asset.stock_assets(date DESC);
CREATE INDEX idx_asset_stock_code ON asset.stock_assets(asset_code);

COMMENT ON TABLE asset.stock_assets IS '주식 자산 (일별)';
COMMENT ON COLUMN asset.stock_assets.domestic_total IS '국내 총 평가금';
COMMENT ON COLUMN asset.stock_assets.foreign_total IS '해외 총 평가금';

-- 채권 (일별)
CREATE TABLE asset.bond_assets (
  id BIGSERIAL PRIMARY KEY,
  date DATE NOT NULL,
  asset_code VARCHAR(50) NOT NULL REFERENCES asset.asset_info(asset_code) ON DELETE CASCADE,
  total_amount DECIMAL(15,2) NOT NULL,
  average_amount DECIMAL(15,2),
  previous_month_comparison DECIMAL(15,2),
  domestic_total DECIMAL(15,2) NOT NULL DEFAULT 0,
  foreign_total DECIMAL(15,2) NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(date, asset_code)
);

CREATE INDEX idx_asset_bond_date ON asset.bond_assets(date DESC);
CREATE INDEX idx_asset_bond_code ON asset.bond_assets(asset_code);

COMMENT ON TABLE asset.bond_assets IS '채권 자산 (일별)';

-- 코인 (일별)
CREATE TABLE asset.coin_assets (
  id BIGSERIAL PRIMARY KEY,
  date DATE NOT NULL,
  asset_code VARCHAR(50) NOT NULL REFERENCES asset.asset_info(asset_code) ON DELETE CASCADE,
  total_amount DECIMAL(15,2) NOT NULL,
  average_amount DECIMAL(15,2),
  previous_month_comparison DECIMAL(15,2),
  domestic_total DECIMAL(15,2) NOT NULL DEFAULT 0,
  foreign_total DECIMAL(15,2) NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(date, asset_code)
);

CREATE INDEX idx_asset_coin_date ON asset.coin_assets(date DESC);
CREATE INDEX idx_asset_coin_code ON asset.coin_assets(asset_code);

COMMENT ON TABLE asset.coin_assets IS '코인 자산 (일별)';

-- ============================================================
-- NOTIFICATION SCHEMA - 알림
-- ============================================================

-- 알림 (통합)
CREATE TABLE notification.notifications (
  id BIGSERIAL PRIMARY KEY,
  notification_code VARCHAR(50) NOT NULL UNIQUE,
  user_id BIGINT REFERENCES auth.users(id) ON DELETE CASCADE,
  type VARCHAR(20) NOT NULL, -- NEWS, TRADE, NOTICE
  category VARCHAR(20), -- STOCK, COIN (NEWS, TRADE만 해당)
  stock_code VARCHAR(20), -- 종목 코드 (없으면 전체)
  trade_history_id BIGINT, -- 거래 내역 코드 (TRADE만 해당)
  title VARCHAR(500) NOT NULL,
  content TEXT NOT NULL,
  sent_at TIMESTAMP NOT NULL,
  is_read BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_notification_code ON notification.notifications(notification_code);
CREATE INDEX idx_notification_user ON notification.notifications(user_id);
CREATE INDEX idx_notification_type ON notification.notifications(type);
CREATE INDEX idx_notification_sent ON notification.notifications(sent_at DESC);
CREATE INDEX idx_notification_read ON notification.notifications(is_read);

COMMENT ON TABLE notification.notifications IS '알림 (통합)';
COMMENT ON COLUMN notification.notifications.type IS '알림 유형 (NEWS, TRADE, NOTICE)';
COMMENT ON COLUMN notification.notifications.category IS '카테고리 (STOCK, COIN)';
COMMENT ON COLUMN notification.notifications.is_read IS '확인 여부';

-- ============================================================
-- MARKET SCHEMA - 시장 정보
-- ============================================================

-- 주요 지수 - 국내
CREATE TABLE market.domestic_indices (
  id BIGSERIAL PRIMARY KEY,
  date DATE NOT NULL UNIQUE,
  kospi DECIMAL(10,2),
  kosdaq DECIMAL(10,2),
  employment_index DECIMAL(10,2),
  inflation_index DECIMAL(10,2),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_market_domestic_date ON market.domestic_indices(date DESC);

COMMENT ON TABLE market.domestic_indices IS '주요 지수 - 국내 (일별)';
COMMENT ON COLUMN market.domestic_indices.employment_index IS '고용 지수';
COMMENT ON COLUMN market.domestic_indices.inflation_index IS '물가 지수';

-- 주요 지수 - 해외
CREATE TABLE market.foreign_indices (
  id BIGSERIAL PRIMARY KEY,
  date DATE NOT NULL UNIQUE,
  nasdaq DECIMAL(10,2),
  sp500 DECIMAL(10,2),
  dow DECIMAL(10,2),
  nikkei DECIMAL(10,2),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_market_foreign_date ON market.foreign_indices(date DESC);

COMMENT ON TABLE market.foreign_indices IS '주요 지수 - 해외 (일별)';

-- 주요 지수 - 코인
CREATE TABLE market.crypto_indices (
  id BIGSERIAL PRIMARY KEY,
  date DATE NOT NULL UNIQUE,
  cmi10 DECIMAL(10,2), -- Crypto Market Index 10
  bdi DECIMAL(10,2), -- Bitcoin Dominance Index
  dpi DECIMAL(10,2), -- DeFi Pulse Index
  fdi DECIMAL(10,2), -- FTX DeFi Index
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_market_crypto_date ON market.crypto_indices(date DESC);

COMMENT ON TABLE market.crypto_indices IS '주요 지수 - 코인 (일별)';

-- 환율
CREATE TABLE market.exchange_rates (
  id BIGSERIAL PRIMARY KEY,
  currency VARCHAR(3) NOT NULL, -- USD, JPY, CNY, EUR
  date DATE NOT NULL,
  rate DECIMAL(10,4) NOT NULL,
  change_from_previous DECIMAL(10,4),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(currency, date)
);

CREATE INDEX idx_market_exchange_currency ON market.exchange_rates(currency);
CREATE INDEX idx_market_exchange_date ON market.exchange_rates(date DESC);

COMMENT ON TABLE market.exchange_rates IS '환율 (일별)';
COMMENT ON COLUMN market.exchange_rates.change_from_previous IS '전일 대비';

-- ============================================================
-- NEWS SCHEMA - 뉴스
-- ============================================================

-- 뉴스
CREATE TABLE news.news (
  id BIGSERIAL PRIMARY KEY,
  news_code VARCHAR(50) NOT NULL UNIQUE, -- YYMMDD-SEQ (241103-1)
  category VARCHAR(20) NOT NULL, -- DOMESTIC, FOREIGN, COIN
  stock_code VARCHAR(20),
  thumbnail_url VARCHAR(500),
  title VARCHAR(500) NOT NULL,
  keywords VARCHAR(100)[], -- 배열
  publisher VARCHAR(100) NOT NULL,
  published_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_news_code ON news.news(news_code);
CREATE INDEX idx_news_category ON news.news(category);
CREATE INDEX idx_news_stock ON news.news(stock_code);
CREATE INDEX idx_news_published ON news.news(published_at DESC);

COMMENT ON TABLE news.news IS '뉴스';
COMMENT ON COLUMN news.news.news_code IS '뉴스 코드 (YYMMDD-SEQ)';
COMMENT ON COLUMN news.news.category IS '구분 (DOMESTIC, FOREIGN, COIN)';
COMMENT ON COLUMN news.news.keywords IS '키워드 (배열)';

-- 뉴스 상세
CREATE TABLE news.news_details (
  id BIGSERIAL PRIMARY KEY,
  news_code VARCHAR(50) NOT NULL REFERENCES news.news(news_code) ON DELETE CASCADE,
  thumbnail_url VARCHAR(500),
  title VARCHAR(500) NOT NULL,
  keywords VARCHAR(100)[], -- 배열
  published_at TIMESTAMP NOT NULL,
  publisher VARCHAR(100) NOT NULL,
  content TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(news_code)
);

CREATE UNIQUE INDEX idx_news_details_code ON news.news_details(news_code);

COMMENT ON TABLE news.news_details IS '뉴스 상세';

-- 속보 TOP5
CREATE TABLE news.breaking_news (
  id BIGSERIAL PRIMARY KEY,
  breaking_code VARCHAR(50) NOT NULL UNIQUE,
  title VARCHAR(500) NOT NULL,
  region VARCHAR(20) NOT NULL, -- DOMESTIC, FOREIGN
  keywords VARCHAR(100)[],
  image_url VARCHAR(500),
  publisher VARCHAR(100) NOT NULL,
  published_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_news_breaking_code ON news.breaking_news(breaking_code);
CREATE INDEX idx_news_breaking_published ON news.breaking_news(published_at DESC);

COMMENT ON TABLE news.breaking_news IS '속보 TOP5';
COMMENT ON COLUMN news.breaking_news.region IS '국내/해외';

-- ============================================================
-- STOCK SCHEMA - 종목
-- ============================================================

-- AI 추천 종목
CREATE TABLE stock.ai_recommendations (
  id BIGSERIAL PRIMARY KEY,
  date DATE NOT NULL,
  stock_code VARCHAR(20) NOT NULL,
  market VARCHAR(20) NOT NULL, -- DOMESTIC, FOREIGN, BOND, COIN
  ranking INT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(date, stock_code)
);

CREATE INDEX idx_stock_ai_date ON stock.ai_recommendations(date DESC);
CREATE INDEX idx_stock_ai_code ON stock.ai_recommendations(stock_code);
CREATE INDEX idx_stock_ai_ranking ON stock.ai_recommendations(ranking);

COMMENT ON TABLE stock.ai_recommendations IS 'AI 추천 종목 (일별)';
COMMENT ON COLUMN stock.ai_recommendations.market IS '장 구분';
COMMENT ON COLUMN stock.ai_recommendations.ranking IS '추천 등급 (순위)';

-- 보유 종목 리스트
CREATE TABLE stock.holdings (
  id BIGSERIAL PRIMARY KEY,
  stock_code VARCHAR(20) NOT NULL,
  user_id BIGINT NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  stock_name VARCHAR(100) NOT NULL,
  icon_url VARCHAR(500),
  keywords VARCHAR(100)[],
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(stock_code, user_id)
);

CREATE INDEX idx_stock_holdings_user ON stock.holdings(user_id);
CREATE INDEX idx_stock_holdings_code ON stock.holdings(stock_code);

COMMENT ON TABLE stock.holdings IS '보유 종목 리스트';

-- 관심 종목 리스트
CREATE TABLE stock.watchlist (
  id BIGSERIAL PRIMARY KEY,
  stock_code VARCHAR(20) NOT NULL,
  user_id BIGINT NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  stock_name VARCHAR(100) NOT NULL,
  icon_url VARCHAR(500),
  keywords VARCHAR(100)[],
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(stock_code, user_id)
);

CREATE INDEX idx_stock_watchlist_user ON stock.watchlist(user_id);
CREATE INDEX idx_stock_watchlist_code ON stock.watchlist(stock_code);

COMMENT ON TABLE stock.watchlist IS '관심 종목 리스트';

-- 종목 검색 (마스터 데이터)
CREATE TABLE stock.stock_master (
  id BIGSERIAL PRIMARY KEY,
  stock_code VARCHAR(20) NOT NULL UNIQUE,
  stock_name VARCHAR(100) NOT NULL,
  market VARCHAR(20) NOT NULL, -- DOMESTIC, FOREIGN
  icon_url VARCHAR(500),
  keywords VARCHAR(100)[],
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_stock_master_code ON stock.stock_master(stock_code);
CREATE INDEX idx_stock_master_market ON stock.stock_master(market);
CREATE INDEX idx_stock_master_name ON stock.stock_master(stock_name);

COMMENT ON TABLE stock.stock_master IS '종목 마스터 (검색용)';

-- 종목 상세 - 차트 (시계열)
CREATE TABLE stock.stock_charts (
  id BIGSERIAL PRIMARY KEY,
  stock_code VARCHAR(20) NOT NULL,
  date DATE NOT NULL,
  price DECIMAL(15,2) NOT NULL,
  volume BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(stock_code, date)
);

CREATE INDEX idx_stock_charts_code ON stock.stock_charts(stock_code);
CREATE INDEX idx_stock_charts_date ON stock.stock_charts(date DESC);

COMMENT ON TABLE stock.stock_charts IS '종목 차트 데이터 (일별)';

-- ============================================================
-- TRADING SCHEMA - 거래
-- ============================================================

-- 미채결 내역
CREATE TABLE trading.pending_orders (
  id BIGSERIAL PRIMARY KEY,
  order_number VARCHAR(50) NOT NULL UNIQUE,
  user_id BIGINT NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  order_type VARCHAR(20) NOT NULL, -- BUY, SELL
  stock_code VARCHAR(20) NOT NULL,
  stock_name VARCHAR(100) NOT NULL,
  total_amount DECIMAL(15,2) NOT NULL,
  ordered_at TIMESTAMP NOT NULL,
  order_price DECIMAL(15,2) NOT NULL,
  order_quantity INT NOT NULL,
  price_type VARCHAR(20) NOT NULL, -- LIMIT, MARKET
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_trading_pending_number ON trading.pending_orders(order_number);
CREATE INDEX idx_trading_pending_user ON trading.pending_orders(user_id);
CREATE INDEX idx_trading_pending_ordered ON trading.pending_orders(ordered_at DESC);

COMMENT ON TABLE trading.pending_orders IS '미채결 내역';
COMMENT ON COLUMN trading.pending_orders.order_type IS '주문 분류 (BUY, SELL)';
COMMENT ON COLUMN trading.pending_orders.price_type IS '주문 유형 (LIMIT 지정가, MARKET 시장가)';

-- 예약 내역
CREATE TABLE trading.scheduled_orders (
  id BIGSERIAL PRIMARY KEY,
  order_number VARCHAR(50) NOT NULL UNIQUE,
  user_id BIGINT NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  order_type VARCHAR(20) NOT NULL, -- BUY, SELL
  stock_code VARCHAR(20) NOT NULL,
  stock_name VARCHAR(100) NOT NULL,
  total_amount DECIMAL(15,2) NOT NULL,
  scheduled_at TIMESTAMP NOT NULL, -- 예약 실행 시간
  order_price DECIMAL(15,2) NOT NULL,
  order_quantity INT NOT NULL,
  price_type VARCHAR(20) NOT NULL, -- LIMIT, MARKET
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_trading_scheduled_number ON trading.scheduled_orders(order_number);
CREATE INDEX idx_trading_scheduled_user ON trading.scheduled_orders(user_id);
CREATE INDEX idx_trading_scheduled_time ON trading.scheduled_orders(scheduled_at);

COMMENT ON TABLE trading.scheduled_orders IS '예약 내역';
COMMENT ON COLUMN trading.scheduled_orders.scheduled_at IS '예약 실행 시간';

-- 주식 거래 내역
CREATE TABLE trading.trade_history (
  id BIGSERIAL PRIMARY KEY,
  transaction_number VARCHAR(50) NOT NULL UNIQUE,
  user_id BIGINT NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  transaction_type VARCHAR(20) NOT NULL, -- BUY, SELL, DIVIDEND
  stock_code VARCHAR(20) NOT NULL,
  stock_name VARCHAR(100) NOT NULL,
  total_amount DECIMAL(15,2) NOT NULL,
  executed_at TIMESTAMP NOT NULL,
  executed_price DECIMAL(15,2) NOT NULL,
  executed_quantity INT NOT NULL,
  price_type VARCHAR(20), -- LIMIT, MARKET (배당은 null)
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_trading_history_number ON trading.trade_history(transaction_number);
CREATE INDEX idx_trading_history_user ON trading.trade_history(user_id);
CREATE INDEX idx_trading_history_executed ON trading.trade_history(executed_at DESC);
CREATE INDEX idx_trading_history_type ON trading.trade_history(transaction_type);

COMMENT ON TABLE trading.trade_history IS '주식 거래 내역';
COMMENT ON COLUMN trading.trade_history.transaction_type IS '주문 분류 (BUY, SELL, DIVIDEND)';
COMMENT ON COLUMN trading.trade_history.executed_at IS '체결 일시';

-- ============================================================
-- SETTINGS SCHEMA - 설정
-- ============================================================

-- 설정 - 기본
CREATE TABLE settings.user_settings (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  favorite_asset_order VARCHAR(100)[], -- 관심 자산 순위 (배열)
  dark_mode BOOLEAN NOT NULL DEFAULT FALSE,
  auto_login BOOLEAN NOT NULL DEFAULT FALSE,
  withdrawal_requested BOOLEAN NOT NULL DEFAULT FALSE,
  withdrawal_date DATE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id)
);

CREATE UNIQUE INDEX idx_settings_user ON settings.user_settings(user_id);

COMMENT ON TABLE settings.user_settings IS '사용자 설정 - 기본';
COMMENT ON COLUMN settings.user_settings.favorite_asset_order IS '관심 자산 순위';

-- 설정 - 알림
CREATE TABLE settings.notification_settings (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  stock_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  stock_news_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  stock_trade_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  coin_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  coin_news_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  coin_trade_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id)
);

CREATE UNIQUE INDEX idx_settings_notification_user ON settings.notification_settings(user_id);

COMMENT ON TABLE settings.notification_settings IS '사용자 설정 - 알림';

-- ============================================================
-- COMPANY SCHEMA - 기업 상세
-- ============================================================

-- 기업 상세 - 기본
CREATE TABLE company.company_info (
  id BIGSERIAL PRIMARY KEY,
  stock_code VARCHAR(20) NOT NULL UNIQUE,
  per DECIMAL(10,2),
  pbr DECIMAL(10,2),
  roe DECIMAL(10,2),
  eps DECIMAL(10,2),
  company_name VARCHAR(200),
  address TEXT,
  website VARCHAR(500),
  sector VARCHAR(100),
  employees INT,
  shares_outstanding BIGINT,
  financial_currency VARCHAR(3),
  market_cap DECIMAL(20,2),
  dividend DECIMAL(10,2),
  overview TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_company_info_code ON company.company_info(stock_code);

COMMENT ON TABLE company.company_info IS '기업 상세 - 기본 정보';
COMMENT ON COLUMN company.company_info.shares_outstanding IS '주식수';
COMMENT ON COLUMN company.company_info.market_cap IS '시가총액';
COMMENT ON COLUMN company.company_info.overview IS '개요';

-- 기업 상세 - AI 분석
CREATE TABLE company.ai_analyses (
  id BIGSERIAL PRIMARY KEY,
  stock_code VARCHAR(20) NOT NULL UNIQUE,
  sector VARCHAR(100),
  sector_profitability DECIMAL(5,2),
  sector_growth DECIMAL(5,2),
  sector_stability DECIMAL(5,2),
  sector_dividend DECIMAL(5,2),
  stock_profitability DECIMAL(5,2),
  stock_growth DECIMAL(5,2),
  stock_stability DECIMAL(5,2),
  stock_dividend DECIMAL(5,2),
  trade_recommendation DECIMAL(5,2),
  technical_analysis TEXT,
  comprehensive_analysis TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_company_ai_code ON company.ai_analyses(stock_code);

COMMENT ON TABLE company.ai_analyses IS '기업 상세 - AI 분석';
COMMENT ON COLUMN company.ai_analyses.trade_recommendation IS '매매 추천 지수';
COMMENT ON COLUMN company.ai_analyses.technical_analysis IS '기술적 분석 내용';
COMMENT ON COLUMN company.ai_analyses.comprehensive_analysis IS '종합 분석 내용';

-- 기업 상세 - 재무제표
CREATE TABLE company.financial_statements (
  id BIGSERIAL PRIMARY KEY,
  stock_code VARCHAR(20) NOT NULL UNIQUE,
  -- 손익계산서
  total_revenue DECIMAL(20,2),
  gross_profit DECIMAL(20,2),
  operating_profit DECIMAL(20,2),
  net_profit DECIMAL(20,2),
  -- 대차대조표
  total_assets DECIMAL(20,2),
  total_liabilities DECIMAL(20,2),
  total_equity DECIMAL(20,2),
  -- 현금흐름표
  operating_cash_flow DECIMAL(20,2),
  investing_cash_flow DECIMAL(20,2),
  financing_cash_flow DECIMAL(20,2),
  net_cash_change DECIMAL(20,2),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_company_financial_code ON company.financial_statements(stock_code);

COMMENT ON TABLE company.financial_statements IS '기업 상세 - 재무제표';

-- 기업 상세 - 공시정보 (현금배당)
CREATE TABLE company.dividend_disclosures (
  id BIGSERIAL PRIMARY KEY,
  stock_code VARCHAR(20) NOT NULL,
  disclosure_date DATE,
  ex_dividend_date DATE, -- 권리락일
  record_date DATE, -- 기준일
  payment_date DATE, -- 지급일
  dividend_amount DECIMAL(10,2),
  settlement_month INT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_company_dividend_code ON company.dividend_disclosures(stock_code);
CREATE INDEX idx_company_dividend_date ON company.dividend_disclosures(disclosure_date DESC);

COMMENT ON TABLE company.dividend_disclosures IS '기업 공시 - 현금배당';
COMMENT ON COLUMN company.dividend_disclosures.settlement_month IS '결산월';

-- 기업 상세 - 공시정보 (주식병합)
CREATE TABLE company.stock_consolidations (
  id BIGSERIAL PRIMARY KEY,
  stock_code VARCHAR(20) NOT NULL,
  record_date DATE,
  listing_change_date DATE,
  consolidation_ratio VARCHAR(50),
  par_value DECIMAL(10,2),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_company_consolidation_code ON company.stock_consolidations(stock_code);
CREATE INDEX idx_company_consolidation_date ON company.stock_consolidations(record_date DESC);

COMMENT ON TABLE company.stock_consolidations IS '기업 공시 - 주식병합';
COMMENT ON COLUMN company.stock_consolidations.consolidation_ratio IS '병합비율';
COMMENT ON COLUMN company.stock_consolidations.par_value IS '액면가';

-- 기업 상세 - 공시정보 (주식분할)
CREATE TABLE company.stock_splits (
  id BIGSERIAL PRIMARY KEY,
  stock_code VARCHAR(20) NOT NULL,
  record_date DATE,
  listing_change_date DATE,
  fractional_payment_date DATE,
  split_ratio VARCHAR(50),
  par_value DECIMAL(10,2),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_company_split_code ON company.stock_splits(stock_code);
CREATE INDEX idx_company_split_date ON company.stock_splits(record_date DESC);

COMMENT ON TABLE company.stock_splits IS '기업 공시 - 주식분할';
COMMENT ON COLUMN company.stock_splits.fractional_payment_date IS '단주대금 지급일';
COMMENT ON COLUMN company.stock_splits.split_ratio IS '분할비율';

-- ============================================================
-- 초기 데이터
-- ============================================================

-- -- 주요 종목 마스터 데이터
-- INSERT INTO stock.stock_master (stock_code, stock_name, market, keywords) VALUES
--   ('005930', '삼성전자', 'DOMESTIC', ARRAY['반도체', '전자', 'IT']),
--   ('000660', 'SK하이닉스', 'DOMESTIC', ARRAY['반도체', 'IT']),
--   ('035720', '카카오', 'DOMESTIC', ARRAY['인터넷', '플랫폼', 'IT']),
--   ('035420', 'NAVER', 'DOMESTIC', ARRAY['인터넷', '플랫폼', 'IT']),
--   ('AAPL', 'Apple Inc.', 'FOREIGN', ARRAY['기술', '전자', 'IT']),
--   ('TSLA', 'Tesla Inc.', 'FOREIGN', ARRAY['자동차', '전기차']),
--   ('GOOGL', 'Alphabet Inc.', 'FOREIGN', ARRAY['인터넷', 'IT']),
--   ('MSFT', 'Microsoft Corporation', 'FOREIGN', ARRAY['소프트웨어', 'IT']);
