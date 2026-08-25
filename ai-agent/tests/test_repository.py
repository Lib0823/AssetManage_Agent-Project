"""Unit tests for DatabaseRepository (database/repository.py).

두 가지 대역(test double)을 쓴다.

1. **in-memory SQLite** — ORM 기반 메서드(`StockFilterScore`/`MarketDailySummary`/
   `ProphetForecast`)와 Postgres 방언에 의존하지 않는 raw SQL 메서드에 사용한다.
   실제 INSERT/SELECT 가 돌아가므로 "DataFrame 컬럼명 → DB 컬럼명" 매핑
   (`volume_ratio`→`vol_avg_multiple`, `final_score`→`scaler_score`,
    `institution_net_buy`→`institutional_net_buy` 등)이 어긋나면 테스트가 깨진다.

2. **RecordingSession / RecordingEngine** — `CAST(... AS JSONB)`, `now()` 처럼
   SQLite 로 재현할 수 없는 Postgres 전용 SQL 을 쓰는 메서드에 사용한다.
   실행된 SQL 문자열과 바인딩 파라미터를 그대로 캡처해 검증한다.
"""
import json
import math
from datetime import date, datetime
from decimal import Decimal

import numpy as np
import pandas as pd
import pytest
from sqlalchemy import BigInteger, create_engine, text
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.compiler import compiles
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool


@compiles(BigInteger, 'sqlite')
def _bigint_as_integer_on_sqlite(type_, compiler, **kw):
    """SQLite 는 정확히 ``INTEGER PRIMARY KEY`` 컬럼만 ROWID 별칭(자동증가)으로 인식한다.

    운영 스키마의 ``id`` 컬럼은 Postgres BIGSERIAL 이라 모델에서 ``BigInteger`` 를 쓰지만,
    SQLAlchemy 는 이를 SQLite ``BIGINT`` 로 컴파일해 autoincrement 가 동작하지 않는다
    (INSERT 시 ``NOT NULL constraint failed: <table>.id``). 테스트 대역(SQLite)에서만
    ``INTEGER`` 로 강제해 PK 자동증가를 재현한다 — 운영 DDL(Postgres)에는 영향 없음.
    """
    return 'INTEGER'

from config.constants import STOCK_NAMES
from database.models import Base
from database.repository import DatabaseRepository


TRADE_DATE = date(2026, 7, 15)

# STOCK_NAMES 에 실제로 존재하는 코드 / 존재하지 않는 코드.
# (005930 삼성전자는 KOSPI_100 상수에서 의도적으로 제외되어 있다 — constants.py:111)
KNOWN_CODE = '000660'
KNOWN_NAME = 'SK하이닉스'
UNKNOWN_CODE = '005930'


# ---------------------------------------------------------------------------
# SQLite 대역: models.py 가 만들지 않는 나머지 테이블의 DDL
# ---------------------------------------------------------------------------

EXTRA_TABLES_DDL = [
    # UNIQUE(stock_code, analysis_date) — Postgres 와 마찬가지로 SQLite 도
    # NULL 은 서로 distinct 하므로 시장 전반(stock_code IS NULL) 행은
    # 제약만으로 중복이 막히지 않는다. repository 의 DELETE-then-INSERT 전제.
    """
    CREATE TABLE news_analysis (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        stock_code VARCHAR(10),
        analysis_date DATE NOT NULL,
        sentiment_score NUMERIC,
        news_count INTEGER
    )
    """,
    "CREATE UNIQUE INDEX uk_news_analysis ON news_analysis (stock_code, analysis_date)",
    """
    CREATE TABLE user_trade_config (
        user_id INTEGER PRIMARY KEY,
        order_amount BIGINT NOT NULL
    )
    """,
    """
    CREATE TABLE feature_threshold_config (
        feature_name VARCHAR(50) PRIMARY KEY,
        buy_enabled BOOLEAN,
        buy_operator VARCHAR(5),
        buy_threshold NUMERIC,
        sell_enabled BOOLEAN,
        sell_operator VARCHAR(5),
        sell_threshold NUMERIC,
        is_active BOOLEAN
    )
    """,
    # api-server changelog v1.1 의 ai_trade_decision 과 동일한 컬럼 집합.
    # save_ai_decisions 가 to_sql(if_exists='append') 로 쓰므로,
    # 컬럼명이 하나라도 어긋나면 append 가 실패한다.
    """
    CREATE TABLE ai_trade_decision (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        stock_code VARCHAR(10) NOT NULL,
        stock_name VARCHAR(50) NOT NULL,
        decision_date DATE NOT NULL,
        decision VARCHAR(10) NOT NULL,
        "rank" INT,
        reason TEXT,
        prompt_summary TEXT,
        created_at TIMESTAMP,
        UNIQUE (stock_code, decision_date, decision)
    )
    """,
]


@pytest.fixture
def sqlite_engine():
    """단일 커넥션을 공유하는 in-memory SQLite 엔진."""
    engine = create_engine(
        'sqlite://',
        connect_args={'check_same_thread': False},
        poolclass=StaticPool,
    )
    Base.metadata.create_all(engine)
    with engine.begin() as conn:
        for ddl in EXTRA_TABLES_DDL:
            conn.execute(text(ddl))
    yield engine
    engine.dispose()


@pytest.fixture
def repo(sqlite_engine):
    """실 Postgres 엔진 대신 SQLite 를 물린 DatabaseRepository.

    `__init__` 이 모듈 전역 `SessionLocal`/`engine`(운영 Postgres)을 잡으므로
    `__new__` 로 우회 생성한 뒤 두 속성만 주입한다 (test_safety_filter.py 와 동일 패턴).
    """
    instance = DatabaseRepository.__new__(DatabaseRepository)
    instance.session_factory = sessionmaker(bind=sqlite_engine)
    instance.engine = sqlite_engine
    return instance


def fetch_all(engine, sql, **params):
    with engine.connect() as conn:
        return conn.execute(text(sql), params).mappings().all()


# ---------------------------------------------------------------------------
# Recording 대역: Postgres 전용 SQL 을 쓰는 메서드용
# ---------------------------------------------------------------------------

class RecordingSession:
    """SQLAlchemy Session 대역 — 실행된 SQL/params 를 기록한다."""

    def __init__(self, fail_on=None, rows=None, rowcount=1):
        self.executions = []          # [(sql_text, params)]
        self.committed = False
        self.rolled_back = False
        self.closed = False
        self.fail_on = fail_on        # 이 문자열이 SQL 에 있으면 SQLAlchemyError
        self._rows = rows if rows is not None else []
        self._rowcount = rowcount     # UPDATE/DELETE 결과의 rowcount 대역

    def execute(self, statement, params=None):
        sql = str(statement)
        self.executions.append((sql, params))
        if self.fail_on and self.fail_on in sql:
            raise SQLAlchemyError(f'injected failure on {self.fail_on}')
        return _RecordingResult(self._rows, self._rowcount)

    def commit(self):
        self.committed = True

    def rollback(self):
        self.rolled_back = True

    def close(self):
        self.closed = True

    def __enter__(self):
        return self

    def __exit__(self, *exc_info):
        self.close()
        return False

    def sql_at(self, index):
        return ' '.join(self.executions[index][0].split())

    def params_at(self, index):
        return self.executions[index][1]


class _RecordingResult:
    def __init__(self, rows, rowcount=1):
        self._rows = rows
        self.rowcount = rowcount

    def mappings(self):
        return self

    def all(self):
        return self._rows

    def fetchone(self):
        return self._rows[0] if self._rows else None


class RecordingEngine:
    """SQLAlchemy Engine 대역 — `begin()` 트랜잭션과 실행 기록을 흉내낸다."""

    def __init__(self, fail_bulk=False, fail_stock_codes=(), fail_delete_after=None):
        self.executions = []
        self.fail_bulk = fail_bulk
        self.fail_stock_codes = set(fail_stock_codes)
        self.fail_delete_after = fail_delete_after   # 이 횟수 이후의 DELETE 부터 실패
        self.deletes = 0
        self.commits = 0
        self.rollbacks = 0

    def begin(self):
        return _RecordingTransaction(self)

    @property
    def insert_executions(self):
        """DELETE 등 부수 문장을 뺀 INSERT 실행만."""
        return [(sql, params) for sql, params in self.executions if 'INSERT' in sql]

    @property
    def inserted_rows(self):
        """기록된 실행에서 실제로 삽입된 파라미터 행만 평탄화."""
        rows = []
        for _, params in self.insert_executions:
            rows.extend(params if isinstance(params, list) else [params])
        return rows


class _RecordingTransaction:
    def __init__(self, engine):
        self.engine = engine

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        if exc_type is None:
            self.engine.commits += 1
        else:
            self.engine.rollbacks += 1
        return False

    def execute(self, statement, params):
        if 'DELETE' in str(statement):
            self.engine.deletes += 1
            if (self.engine.fail_delete_after is not None
                    and self.engine.deletes > self.engine.fail_delete_after):
                raise SQLAlchemyError('injected delete failure')

        is_bulk = isinstance(params, list)
        if is_bulk and self.engine.fail_bulk:
            raise SQLAlchemyError('injected bulk insert failure')
        for row in (params if is_bulk else [params]):
            if row.get('stock_code') in self.engine.fail_stock_codes:
                raise SQLAlchemyError(f"injected row failure: {row['stock_code']}")
        self.engine.executions.append((str(statement), params))


def make_repo(session=None, engine=None):
    """세션/엔진 대역만 물린 DatabaseRepository."""
    instance = DatabaseRepository.__new__(DatabaseRepository)
    instance.session_factory = (lambda: session) if session is not None else None
    instance.engine = engine
    return instance


# ---------------------------------------------------------------------------
# 샘플 데이터 빌더
# ---------------------------------------------------------------------------

def filter_row(stock_code=KNOWN_CODE, **overrides):
    """save_filter_scores 가 기대하는 내부 DataFrame 컬럼명 그대로의 한 행."""
    row = {
        'stock_code': stock_code,
        'stock_name': STOCK_NAMES.get(stock_code, ''),
        'foreign_net_buy': 1_500_000,
        'institutional_net_buy': -800_000,
        'volume_ratio': 2.35,          # → DB: vol_avg_multiple
        'price_volatility': 1.2345,
        'final_score': 3.4567,         # → DB: scaler_score
        'is_selected': True,
    }
    row.update(overrides)
    return row


def filter_df(*rows):
    return pd.DataFrame(list(rows) or [filter_row()])


def safety_result(stock_code=KNOWN_CODE, decision='BUY', **overrides):
    result = {
        'stock_code': stock_code,
        'stock_name': STOCK_NAMES.get(stock_code, ''),
        'decision': decision,
        'passed': True,
        'failure_reason': None,
        'max_quantity': 12,
        'current_price': 81_500,
        'filter_checks': {'per_check': {'passed': True, 'value': 12.0, 'threshold': 30.0}},
    }
    result.update(overrides)
    return result


# ===========================================================================
# 1. get_feature_thresholds  (우선순위 1)
# ===========================================================================

class TestGetFeatureThresholds:
    """Stage 5 임계값의 DB 단일 출처 조회. 실패 시 반드시 빈 dict(폴백 신호)."""

    def _seed(self, engine, **row):
        defaults = {
            'feature_name': 'per',
            'buy_enabled': True,
            'buy_operator': '<=',
            'buy_threshold': 30.0,
            'sell_enabled': False,
            'sell_operator': None,
            'sell_threshold': None,
            'is_active': True,
        }
        defaults.update(row)
        with engine.begin() as conn:
            conn.execute(
                text(
                    "INSERT INTO feature_threshold_config "
                    "(feature_name, buy_enabled, buy_operator, buy_threshold, "
                    " sell_enabled, sell_operator, sell_threshold, is_active) "
                    "VALUES (:feature_name, :buy_enabled, :buy_operator, :buy_threshold, "
                    " :sell_enabled, :sell_operator, :sell_threshold, :is_active)"
                ),
                defaults,
            )

    def test_returns_rows_keyed_by_feature_name(self, repo, sqlite_engine):
        self._seed(sqlite_engine, feature_name='per')
        self._seed(sqlite_engine, feature_name='roe', buy_operator='>=',
                   buy_threshold=10.0)

        result = repo.get_feature_thresholds()

        assert set(result) == {'per', 'roe'}
        assert result['per']['buy_operator'] == '<='
        assert result['per']['buy_threshold'] == 30.0
        assert result['roe']['buy_threshold'] == 10.0

    def test_all_seven_keys_are_present(self, repo, sqlite_engine):
        """SafetyFilter 가 소비하는 키 집합이 정확히 유지되는지."""
        self._seed(sqlite_engine)

        row = repo.get_feature_thresholds()['per']

        assert set(row) == {
            'buy_enabled', 'buy_operator', 'buy_threshold',
            'sell_enabled', 'sell_operator', 'sell_threshold',
            'is_active',
        }

    def test_numeric_is_coerced_to_float_and_nulls_preserved(self, repo, sqlite_engine):
        self._seed(sqlite_engine, sell_threshold=None, sell_operator=None)

        row = repo.get_feature_thresholds()['per']

        assert isinstance(row['buy_threshold'], float)
        assert row['sell_threshold'] is None
        assert row['sell_operator'] is None
        assert row['sell_enabled'] is False

    def test_active_only_true_excludes_inactive_rows(self, repo, sqlite_engine):
        self._seed(sqlite_engine, feature_name='per', is_active=True)
        self._seed(sqlite_engine, feature_name='roe', is_active=False)

        assert set(repo.get_feature_thresholds(active_only=True)) == {'per'}

    def test_active_only_false_includes_inactive_rows(self, repo, sqlite_engine):
        self._seed(sqlite_engine, feature_name='per', is_active=True)
        self._seed(sqlite_engine, feature_name='roe', is_active=False)

        result = repo.get_feature_thresholds(active_only=False)

        assert set(result) == {'per', 'roe'}
        assert result['roe']['is_active'] is False

    def test_empty_table_returns_empty_dict(self, repo):
        assert repo.get_feature_thresholds() == {}

    def test_db_failure_returns_empty_dict(self):
        """테이블 미존재/DB 다운 — 파이프라인을 죽이지 않고 폴백 신호를 준다."""
        session = RecordingSession(fail_on='feature_threshold_config')
        result = make_repo(session=session).get_feature_thresholds()

        assert result == {}
        assert session.closed is True

    def test_non_sqlalchemy_exception_is_also_swallowed(self):
        """except 절이 Exception 이므로 RuntimeError 도 폴백된다."""
        class _Broken:
            closed = False

            def execute(self, *a, **kw):
                raise RuntimeError('connection refused')

            def close(self):
                self.closed = True

        broken = _Broken()
        assert make_repo(session=broken).get_feature_thresholds() == {}
        assert broken.closed is True

    def test_decimal_and_none_booleans_are_normalized(self):
        """Postgres 는 NUMERIC 을 Decimal 로 준다. bool 이 NULL 이면 True 로 본다."""
        rows = [{
            'feature_name': 'sentiment_score',
            'buy_enabled': None,
            'buy_operator': '>=',
            'buy_threshold': Decimal('0.30000000'),
            'sell_enabled': None,
            'sell_operator': '<=',
            'sell_threshold': Decimal('-0.30000000'),
            'is_active': None,
        }]
        result = make_repo(session=RecordingSession(rows=rows)).get_feature_thresholds()

        row = result['sentiment_score']
        assert row['buy_threshold'] == pytest.approx(0.3)
        assert row['sell_threshold'] == pytest.approx(-0.3)
        assert row['buy_enabled'] is True
        assert row['sell_enabled'] is True
        assert row['is_active'] is True

    def test_unparseable_threshold_becomes_none(self):
        rows = [{
            'feature_name': 'per',
            'buy_enabled': True, 'buy_operator': '<=', 'buy_threshold': 'N/A',
            'sell_enabled': True, 'sell_operator': None, 'sell_threshold': None,
            'is_active': True,
        }]
        result = make_repo(session=RecordingSession(rows=rows)).get_feature_thresholds()

        assert result['per']['buy_threshold'] is None

    def test_active_only_toggles_where_clause(self):
        session = RecordingSession()
        make_repo(session=session).get_feature_thresholds(active_only=True)
        assert 'WHERE is_active = TRUE' in session.sql_at(0)

        session = RecordingSession()
        make_repo(session=session).get_feature_thresholds(active_only=False)
        assert 'WHERE' not in session.sql_at(0)


# ===========================================================================
# 2. save_prophet_forecast_detailed  (우선순위 2)
# ===========================================================================

def forecast_payload(stock_code=KNOWN_CODE, **overrides):
    payload = {
        'stock_code': stock_code,
        'prophet_price_trend': 0.0123,
        'prophet_volume_trend': -0.0456,
        'prophet_price_uncertainty': 1234.5,
    }
    for i in range(1, 6):
        payload[f'yhat_price_d{i}'] = 80_000 + i * 100
        payload[f'yhat_price_lower_d{i}'] = 79_000 + i * 100
        payload[f'yhat_price_upper_d{i}'] = 81_000 + i * 100
    payload.update(overrides)
    return payload


class TestSaveProphetForecastDetailed:

    def test_stock_name_falls_back_to_stock_names_constant(self, repo, sqlite_engine):
        """ts_df 에 stock_name 이 없어도 종목코드가 아니라 실제 종목명이 저장된다."""
        assert repo.save_prophet_forecast_detailed(
            forecast_payload(KNOWN_CODE), TRADE_DATE) is True

        rows = fetch_all(sqlite_engine, "SELECT stock_name FROM prophet_forecast")
        assert rows[0]['stock_name'] == KNOWN_NAME

    def test_explicit_stock_name_wins_over_constant(self, repo, sqlite_engine):
        repo.save_prophet_forecast_detailed(
            forecast_payload(KNOWN_CODE, stock_name='직접지정'), TRADE_DATE)

        rows = fetch_all(sqlite_engine, "SELECT stock_name FROM prophet_forecast")
        assert rows[0]['stock_name'] == '직접지정'

    @pytest.mark.parametrize('empty', ['', None])
    def test_falsy_stock_name_falls_back(self, repo, sqlite_engine, empty):
        """`or` 폴백이므로 빈 문자열도 상수 조회로 대체된다."""
        repo.save_prophet_forecast_detailed(
            forecast_payload(KNOWN_CODE, stock_name=empty), TRADE_DATE)

        rows = fetch_all(sqlite_engine, "SELECT stock_name FROM prophet_forecast")
        assert rows[0]['stock_name'] == KNOWN_NAME

    def test_unknown_code_falls_back_to_the_code_itself(self, repo, sqlite_engine):
        assert UNKNOWN_CODE not in STOCK_NAMES
        repo.save_prophet_forecast_detailed(
            forecast_payload(UNKNOWN_CODE), TRADE_DATE)

        rows = fetch_all(sqlite_engine, "SELECT stock_name FROM prophet_forecast")
        assert rows[0]['stock_name'] == UNKNOWN_CODE

    def test_trade_date_is_written_to_forecast_date_column(self, repo, sqlite_engine):
        repo.save_prophet_forecast_detailed(forecast_payload(), TRADE_DATE)

        rows = fetch_all(sqlite_engine, "SELECT forecast_date FROM prophet_forecast")
        assert str(rows[0]['forecast_date']) == '2026-07-15'

    def test_all_fifteen_yhat_columns_are_mapped(self, repo, sqlite_engine):
        repo.save_prophet_forecast_detailed(forecast_payload(), TRADE_DATE)

        row = fetch_all(sqlite_engine, "SELECT * FROM prophet_forecast")[0]
        for i in range(1, 6):
            assert float(row[f'yhat_d{i}']) == 80_000 + i * 100
            assert float(row[f'yhat_lower_d{i}']) == 79_000 + i * 100
            assert float(row[f'yhat_upper_d{i}']) == 81_000 + i * 100

    def test_aggregated_trends_are_renamed_to_db_columns(self, repo, sqlite_engine):
        """prophet_price_trend → price_trend 등 접두어 제거 매핑."""
        repo.save_prophet_forecast_detailed(forecast_payload(), TRADE_DATE)

        row = fetch_all(sqlite_engine, "SELECT * FROM prophet_forecast")[0]
        assert float(row['price_trend']) == pytest.approx(0.0123)
        assert float(row['volume_trend']) == pytest.approx(-0.0456)
        assert float(row['price_uncertainty']) == pytest.approx(1234.5)

    def test_missing_trend_keys_default_to_zero(self, repo, sqlite_engine):
        payload = forecast_payload()
        for key in ('prophet_price_trend', 'prophet_volume_trend',
                    'prophet_price_uncertainty'):
            payload.pop(key)

        repo.save_prophet_forecast_detailed(payload, TRADE_DATE)

        row = fetch_all(sqlite_engine, "SELECT * FROM prophet_forecast")[0]
        assert float(row['price_trend']) == 0.0
        assert float(row['volume_trend']) == 0.0
        assert float(row['price_uncertainty']) == 0.0

    def test_missing_yhat_keys_stay_null(self, repo, sqlite_engine):
        payload = {'stock_code': KNOWN_CODE}
        repo.save_prophet_forecast_detailed(payload, TRADE_DATE)

        row = fetch_all(sqlite_engine, "SELECT * FROM prophet_forecast")[0]
        assert row['yhat_d1'] is None
        assert row['yhat_upper_d5'] is None

    @pytest.mark.parametrize('raw,expected', [
        (1e12, 9999.0),      # NUMERIC(10,6) 상한
        (-1e12, -9999.0),
        (12.5, 12.5),
    ])
    def test_price_trend_is_clamped(self, repo, sqlite_engine, raw, expected):
        repo.save_prophet_forecast_detailed(
            forecast_payload(prophet_price_trend=raw), TRADE_DATE)

        row = fetch_all(sqlite_engine, "SELECT price_trend FROM prophet_forecast")[0]
        assert float(row['price_trend']) == expected

    @pytest.mark.parametrize('raw,expected', [
        (1e9, 999999.0),     # NUMERIC(10,4) 상한
        (-5.0, 0.0),         # 하한 0 — 불확실성은 음수일 수 없다
    ])
    def test_uncertainty_is_clamped(self, repo, sqlite_engine, raw, expected):
        repo.save_prophet_forecast_detailed(
            forecast_payload(prophet_price_uncertainty=raw), TRADE_DATE)

        row = fetch_all(sqlite_engine, "SELECT price_uncertainty FROM prophet_forecast")[0]
        assert float(row['price_uncertainty']) == expected

    def test_yhat_is_clamped_to_numeric_12_2(self, repo, sqlite_engine):
        repo.save_prophet_forecast_detailed(
            forecast_payload(yhat_price_d1=1e15, yhat_price_lower_d1=-42.0), TRADE_DATE)

        row = fetch_all(sqlite_engine, "SELECT * FROM prophet_forecast")[0]
        assert float(row['yhat_d1']) == 9999999999.99
        assert float(row['yhat_lower_d1']) == 0.0

    def test_rerun_replaces_the_same_stock_and_date(self, repo, sqlite_engine):
        """DELETE-then-INSERT 멱등성 — 같은 날 두 번 돌려도 1행."""
        repo.save_prophet_forecast_detailed(
            forecast_payload(yhat_price_d1=80_100), TRADE_DATE)
        repo.save_prophet_forecast_detailed(
            forecast_payload(yhat_price_d1=99_900), TRADE_DATE)

        rows = fetch_all(sqlite_engine, "SELECT yhat_d1 FROM prophet_forecast")
        assert len(rows) == 1
        assert float(rows[0]['yhat_d1']) == 99_900

    def test_other_dates_are_not_deleted(self, repo, sqlite_engine):
        repo.save_prophet_forecast_detailed(forecast_payload(), date(2026, 7, 14))
        repo.save_prophet_forecast_detailed(forecast_payload(), TRADE_DATE)

        rows = fetch_all(sqlite_engine, "SELECT forecast_date FROM prophet_forecast")
        assert len(rows) == 2

    def test_missing_stock_code_raises(self, repo):
        """stock_code 는 필수 — KeyError 는 SQLAlchemyError 가 아니라 그대로 전파된다."""
        with pytest.raises(KeyError):
            repo.save_prophet_forecast_detailed({'prophet_price_trend': 0.1}, TRADE_DATE)

    def test_db_error_rolls_back_and_returns_false(self):
        class _Session(RecordingSession):
            def query(self, *a, **kw):
                raise SQLAlchemyError('db down')

        session = _Session()
        repo = make_repo(session=session)

        assert repo.save_prophet_forecast_detailed(forecast_payload(), TRADE_DATE) is False
        assert session.rolled_back is True
        assert session.closed is True


# ===========================================================================
# 3. save_safety_filter_results  (우선순위 3)
# ===========================================================================

class TestSaveSafetyFilterResults:

    def test_decision_column_is_written(self, ):
        engine = RecordingEngine()
        assert make_repo(engine=engine).save_safety_filter_results(
            [safety_result(decision='BUY')], TRADE_DATE) is True

        sql = ' '.join(engine.insert_executions[0][0].split())
        assert 'decision' in sql
        assert engine.inserted_rows[0]['decision'] == 'BUY'

    def test_delete_precedes_insert_for_idempotency(self):
        """같은 날 재실행 시 낡은 행이 남지 않도록 filter_date 기준으로 먼저 지운다."""
        engine = RecordingEngine()
        make_repo(engine=engine).save_safety_filter_results([safety_result()], TRADE_DATE)

        first_sql, first_params = engine.executions[0]
        assert 'DELETE FROM safety_filter_result' in ' '.join(first_sql.split())
        assert first_params == {'filter_date': TRADE_DATE}
        assert 'INSERT INTO safety_filter_result' in engine.executions[1][0]

    def test_insert_column_list_matches_schema(self):
        engine = RecordingEngine()
        make_repo(engine=engine).save_safety_filter_results([safety_result()], TRADE_DATE)

        sql = ' '.join(engine.insert_executions[0][0].split())
        assert 'INSERT INTO safety_filter_result' in sql
        for column in ('stock_code', 'stock_name', 'filter_date', 'decision', 'passed',
                       'failure_reason', 'max_quantity', 'current_price', 'filter_checks'):
            assert column in sql
        # filter_checks 는 JSONB 컬럼이라 CAST 가 필요하다
        assert 'CAST(:filter_checks AS JSONB)' in sql

    def test_same_stock_as_buy_and_sell_produces_two_distinct_rows(self):
        """v1.18 이후 UNIQUE 키가 (filter_date, stock_code, decision) 이므로
        같은 종목이 매수·매도 후보에 동시에 올라도 두 행이 모두 저장된다."""
        engine = RecordingEngine()
        results = [
            safety_result(decision='BUY', passed=True),
            safety_result(decision='SELL', passed=False,
                          failure_reason='Neither negative sentiment nor negative trend'),
        ]
        assert make_repo(engine=engine).save_safety_filter_results(results, TRADE_DATE) is True

        rows = engine.inserted_rows
        assert len(rows) == 2
        assert {r['decision'] for r in rows} == {'BUY', 'SELL'}
        # UNIQUE 키 3요소가 서로 달라야 제약에 걸리지 않는다
        keys = {(r['filter_date'], r['stock_code'], r['decision']) for r in rows}
        assert len(keys) == 2

    def test_bulk_insert_uses_a_single_transaction(self):
        engine = RecordingEngine()
        make_repo(engine=engine).save_safety_filter_results(
            [safety_result('000660'), safety_result('051910')], TRADE_DATE)

        assert len(engine.insert_executions) == 1       # 한 번의 executemany
        assert isinstance(engine.insert_executions[0][1], list)
        assert engine.commits == 1                     # DELETE 와 같은 트랜잭션

    def test_empty_list_returns_false(self):
        engine = RecordingEngine()
        assert make_repo(engine=engine).save_safety_filter_results([], TRADE_DATE) is False
        assert engine.executions == []

    def test_missing_required_key_skips_only_that_row(self):
        """stock_code/decision/passed 가 없는 행만 버리고 나머지는 저장한다."""
        engine = RecordingEngine()
        bad = safety_result('051910')
        del bad['decision']

        assert make_repo(engine=engine).save_safety_filter_results(
            [safety_result('000660'), bad], TRADE_DATE) is True

        rows = engine.inserted_rows
        assert [r['stock_code'] for r in rows] == ['000660']

    def test_all_rows_unsanitizable_returns_false(self):
        engine = RecordingEngine()
        bad = {'stock_name': 'x'}   # stock_code 없음

        assert make_repo(engine=engine).save_safety_filter_results([bad], TRADE_DATE) is False
        assert engine.executions == []

    def test_bulk_failure_falls_back_to_per_row_insert(self):
        engine = RecordingEngine(fail_bulk=True)

        assert make_repo(engine=engine).save_safety_filter_results(
            [safety_result('000660'), safety_result('051910')], TRADE_DATE) is True

        # bulk 1회 실패(rollback) 후 DELETE 재수행 + 행 단위 2회 성공
        assert engine.rollbacks == 1
        assert engine.commits == 3
        assert [r['stock_code'] for r in engine.inserted_rows] == ['000660', '051910']

    def test_delete_is_reissued_before_the_per_row_fallback(self):
        """bulk 트랜잭션이 롤백되면 DELETE 도 함께 되돌아가므로 다시 실행해야 한다."""
        engine = RecordingEngine(fail_bulk=True)
        make_repo(engine=engine).save_safety_filter_results([safety_result()], TRADE_DATE)

        deletes = [sql for sql, _ in engine.executions if 'DELETE' in sql]
        assert len(deletes) == 2

    def test_returns_false_when_the_fallback_delete_fails(self):
        """낡은 행을 못 지운 채 새 행만 넣으면 뒤섞이므로, 아예 저장하지 않는다."""
        engine = RecordingEngine(fail_bulk=True, fail_delete_after=1)

        assert make_repo(engine=engine).save_safety_filter_results(
            [safety_result()], TRADE_DATE) is False
        assert engine.inserted_rows == []

    def test_per_row_fallback_preserves_good_rows(self):
        """한 행이 DB에서 거부돼도 당일의 정상 행은 남는다."""
        engine = RecordingEngine(fail_bulk=True, fail_stock_codes=['051910'])

        assert make_repo(engine=engine).save_safety_filter_results(
            [safety_result('000660'), safety_result('051910')], TRADE_DATE) is True

        assert [r['stock_code'] for r in engine.inserted_rows] == ['000660']

    def test_returns_false_when_every_row_fails(self):
        engine = RecordingEngine(fail_bulk=True, fail_stock_codes=['000660'])

        assert make_repo(engine=engine).save_safety_filter_results(
            [safety_result('000660')], TRADE_DATE) is False

    def test_explicit_conn_overrides_engine(self):
        engine = RecordingEngine()
        conn = RecordingEngine()

        make_repo(engine=engine).save_safety_filter_results(
            [safety_result()], TRADE_DATE, conn=conn)

        assert conn.executions and engine.executions == []


class TestSafetyFilterSanitization:
    """_sanitize / _sanitize_scalar_int 의 경계값 처리 (INSERT 파라미터로 검증)."""

    def _row(self, **overrides):
        engine = RecordingEngine()
        make_repo(engine=engine).save_safety_filter_results(
            [safety_result(**overrides)], TRADE_DATE)
        return engine.inserted_rows[0]

    def test_current_price_is_an_int_not_a_float(self):
        """current_price 는 bigint 컬럼 — 실수로 넘어와도 int 로 내려간다."""
        row = self._row(current_price=81_500.0)
        assert row['current_price'] == 81_500
        assert isinstance(row['current_price'], int)

    def test_current_price_truncates_toward_zero(self):
        assert self._row(current_price=81_500.99)['current_price'] == 81_500

    @pytest.mark.parametrize('value', [None, float('nan'), float('inf'), float('-inf')])
    def test_non_finite_current_price_becomes_none(self, value):
        assert self._row(current_price=value)['current_price'] is None

    def test_numpy_int_current_price_is_converted(self):
        row = self._row(current_price=np.int64(81_500))
        assert row['current_price'] == 81_500
        assert type(row['current_price']) is int

    def test_unconvertible_current_price_becomes_none(self):
        assert self._row(current_price='비어있음')['current_price'] is None

    @pytest.mark.parametrize('value,expected', [
        (None, None),
        (float('nan'), None),
        (12.9, 12),
        (np.int32(7), 7),
    ])
    def test_max_quantity_uses_the_same_int_sanitizer(self, value, expected):
        assert self._row(max_quantity=value)['max_quantity'] == expected

    def test_missing_optional_fields_default_safely(self):
        engine = RecordingEngine()
        minimal = {'stock_code': KNOWN_CODE, 'decision': 'BUY', 'passed': True}
        make_repo(engine=engine).save_safety_filter_results([minimal], TRADE_DATE)

        row = engine.inserted_rows[0]
        assert row['stock_name'] == ''
        assert row['failure_reason'] is None
        assert row['max_quantity'] is None
        assert row['current_price'] is None
        assert json.loads(row['filter_checks']) == {}

    def test_nan_inside_filter_checks_becomes_json_null(self):
        """Postgres JSONB 는 NaN/Infinity 리터럴을 거부하므로 None 으로 치환된다."""
        checks = {'per_check': {'value': float('nan'), 'threshold': float('inf'),
                                'passed': False}}
        payload = json.loads(self._row(filter_checks=checks)['filter_checks'])

        assert payload['per_check']['value'] is None
        assert payload['per_check']['threshold'] is None
        assert payload['per_check']['passed'] is False

    def test_numpy_scalars_inside_filter_checks_are_converted(self):
        checks = {
            'foreign_check': {
                'value': np.int64(1_500_000),
                'threshold': np.float64(0.0),
                'passed': np.bool_(True),
            }
        }
        payload = json.loads(self._row(filter_checks=checks)['filter_checks'])

        assert payload['foreign_check'] == {
            'value': 1_500_000, 'threshold': 0.0, 'passed': True}

    def test_numpy_bool_is_not_coerced_to_int(self):
        """bool 체크가 int 보다 먼저 와야 True 가 1 로 새지 않는다."""
        checks = {'flag': np.bool_(False)}
        payload = json.loads(self._row(filter_checks=checks)['filter_checks'])

        assert payload['flag'] is False
        assert payload['flag'] is not 0  # noqa: F632 — 타입까지 확인

    def test_nested_lists_are_sanitized_recursively(self):
        checks = {'series': [np.float64(1.5), float('nan'), [np.int64(3), np.bool_(True)]]}
        payload = json.loads(self._row(filter_checks=checks)['filter_checks'])

        assert payload['series'] == [1.5, None, [3, True]]

    def test_korean_text_is_not_escaped(self):
        checks = {'reason': '거래량 급증'}
        raw = self._row(filter_checks=checks)['filter_checks']

        assert '거래량 급증' in raw   # ensure_ascii=False

    def test_scalar_fields_are_stringified(self):
        row = self._row(stock_code=660, stock_name=123, decision='BUY',
                        failure_reason=ValueError('PER too high'))

        assert row['stock_code'] == '660'
        assert row['stock_name'] == '123'
        assert row['failure_reason'] == 'PER too high'

    def test_passed_is_coerced_to_bool(self):
        assert self._row(passed=np.bool_(False))['passed'] is False
        assert self._row(passed=1)['passed'] is True


# ===========================================================================
# 4. save_filter_scores / get_filter_scores  (DataFrame ↔ DB 컬럼 매핑)
# ===========================================================================

class TestSaveFilterScores:

    def test_dataframe_columns_map_to_db_columns(self, repo, sqlite_engine):
        assert repo.save_filter_scores(filter_df(), TRADE_DATE) is True

        row = fetch_all(sqlite_engine, "SELECT * FROM stock_filter_score")[0]
        assert row['stock_code'] == KNOWN_CODE
        assert row['stock_name'] == KNOWN_NAME
        assert str(row['score_date']) == '2026-07-15'
        assert row['foreign_net_buy'] == 1_500_000
        assert row['institutional_net_buy'] == -800_000
        assert float(row['vol_avg_multiple']) == pytest.approx(2.35)   # ← volume_ratio
        assert float(row['price_volatility']) == pytest.approx(1.2345)
        assert float(row['scaler_score']) == pytest.approx(3.4567)     # ← final_score
        assert bool(row['is_selected']) is True

    def test_stock_name_is_optional(self, repo, sqlite_engine):
        df = pd.DataFrame([{k: v for k, v in filter_row().items() if k != 'stock_name'}])
        assert repo.save_filter_scores(df, TRADE_DATE) is True

        row = fetch_all(sqlite_engine, "SELECT stock_name FROM stock_filter_score")[0]
        assert row['stock_name'] == ''

    def test_numeric_strings_are_coerced(self, repo, sqlite_engine):
        """KIS 응답에서 온 문자열 수치도 int()/float() 로 변환된다."""
        df = filter_df(filter_row(foreign_net_buy='1500000', volume_ratio='2.5'))
        assert repo.save_filter_scores(df, TRADE_DATE) is True

        row = fetch_all(sqlite_engine, "SELECT * FROM stock_filter_score")[0]
        assert row['foreign_net_buy'] == 1_500_000
        assert float(row['vol_avg_multiple']) == 2.5

    def test_float_net_buy_is_truncated_to_int(self, repo, sqlite_engine):
        df = filter_df(filter_row(foreign_net_buy=1_500_000.9))
        repo.save_filter_scores(df, TRADE_DATE)

        row = fetch_all(sqlite_engine, "SELECT foreign_net_buy FROM stock_filter_score")[0]
        assert row['foreign_net_buy'] == 1_500_000

    def test_rerun_replaces_the_whole_date(self, repo, sqlite_engine):
        repo.save_filter_scores(
            filter_df(filter_row('000660'), filter_row('051910')), TRADE_DATE)
        repo.save_filter_scores(filter_df(filter_row('005490')), TRADE_DATE)

        rows = fetch_all(sqlite_engine, "SELECT stock_code FROM stock_filter_score")
        assert [r['stock_code'] for r in rows] == ['005490']

    def test_other_dates_survive_the_delete(self, repo, sqlite_engine):
        repo.save_filter_scores(filter_df(), date(2026, 7, 14))
        repo.save_filter_scores(filter_df(), TRADE_DATE)

        assert len(fetch_all(sqlite_engine, "SELECT id FROM stock_filter_score")) == 2

    def test_empty_dataframe_saves_nothing_but_succeeds(self, repo, sqlite_engine):
        empty = pd.DataFrame(columns=list(filter_row()))
        assert repo.save_filter_scores(empty, TRADE_DATE) is True
        assert fetch_all(sqlite_engine, "SELECT id FROM stock_filter_score") == []

    def test_missing_required_column_raises_key_error(self, repo):
        """컬럼명이 바뀌면 조용히 넘어가지 않고 KeyError 로 터진다."""
        df = pd.DataFrame([{k: v for k, v in filter_row().items() if k != 'final_score'}])
        with pytest.raises(KeyError):
            repo.save_filter_scores(df, TRADE_DATE)

    def test_nan_net_buy_raises_value_error(self, repo):
        """int(NaN) 은 ValueError — SQLAlchemyError 가 아니라 그대로 전파된다."""
        df = filter_df(filter_row(foreign_net_buy=float('nan')))
        with pytest.raises(ValueError):
            repo.save_filter_scores(df, TRADE_DATE)

    def test_db_error_rolls_back_and_returns_false(self):
        class _Session(RecordingSession):
            def query(self, *a, **kw):
                raise SQLAlchemyError('db down')

        session = _Session()
        assert make_repo(session=session).save_filter_scores(filter_df(), TRADE_DATE) is False
        assert session.rolled_back is True
        assert session.closed is True


class TestGetFilterScores:

    def test_db_columns_map_back_to_dataframe_columns(self, repo):
        repo.save_filter_scores(filter_df(), TRADE_DATE)

        df = repo.get_filter_scores(TRADE_DATE)

        assert list(df.columns) == [
            'stock_code', 'stock_name', 'score_date', 'foreign_net_buy',
            'institutional_net_buy', 'volume_ratio', 'price_volatility',
            'final_score', 'is_selected',
        ]
        assert df.iloc[0]['volume_ratio'] == pytest.approx(2.35)
        assert df.iloc[0]['final_score'] == pytest.approx(3.4567)

    def test_roundtrip_preserves_values(self, repo):
        repo.save_filter_scores(filter_df(), TRADE_DATE)

        row = repo.get_filter_scores(TRADE_DATE).iloc[0]
        assert row['stock_code'] == KNOWN_CODE
        assert row['foreign_net_buy'] == 1_500_000
        assert row['institutional_net_buy'] == -800_000
        assert bool(row['is_selected']) is True

    def test_missing_date_returns_none(self, repo):
        assert repo.get_filter_scores(TRADE_DATE) is None

    def test_db_error_returns_none(self):
        class _Session(RecordingSession):
            def query(self, *a, **kw):
                raise SQLAlchemyError('db down')

        session = _Session()
        assert make_repo(session=session).get_filter_scores(TRADE_DATE) is None
        assert session.closed is True


class TestGetSelectedStocks:

    def test_returns_only_selected_codes(self, repo):
        repo.save_filter_scores(filter_df(
            filter_row('000660', is_selected=True),
            filter_row('051910', is_selected=False),
            filter_row('005490', is_selected=True),
        ), TRADE_DATE)

        assert repo.get_selected_stocks(TRADE_DATE) == ['000660', '005490']

    def test_other_dates_are_excluded(self, repo):
        repo.save_filter_scores(filter_df(filter_row('000660')), date(2026, 7, 14))
        repo.save_filter_scores(filter_df(filter_row('051910')), TRADE_DATE)

        assert repo.get_selected_stocks(TRADE_DATE) == ['051910']

    def test_missing_date_returns_empty_list(self, repo):
        assert repo.get_selected_stocks(TRADE_DATE) == []

    def test_db_error_returns_empty_list(self):
        class _Session(RecordingSession):
            def query(self, *a, **kw):
                raise SQLAlchemyError('db down')

        assert make_repo(session=_Session()).get_selected_stocks(TRADE_DATE) == []


class TestGetLatestFilterDate:

    def test_returns_the_most_recent_score_date(self, repo):
        for d in (date(2026, 7, 13), date(2026, 7, 15), date(2026, 7, 14)):
            repo.save_filter_scores(filter_df(), d)

        assert repo.get_latest_filter_date() == date(2026, 7, 15)

    def test_empty_table_returns_none(self, repo):
        assert repo.get_latest_filter_date() is None

    def test_db_error_returns_none(self):
        class _Session(RecordingSession):
            def query(self, *a, **kw):
                raise SQLAlchemyError('db down')

        assert make_repo(session=_Session()).get_latest_filter_date() is None


# ===========================================================================
# 5. save_quantitative_features
# ===========================================================================

class TestSaveQuantitativeFeatures:

    def test_updates_the_existing_filter_row(self, repo, sqlite_engine):
        repo.save_filter_scores(filter_df(), TRADE_DATE)

        assert repo.save_quantitative_features(
            KNOWN_CODE, TRADE_DATE, morning_return=1.23, close_position=0.85) is True

        row = fetch_all(sqlite_engine, "SELECT * FROM stock_filter_score")[0]
        assert float(row['morning_return']) == pytest.approx(1.23)
        assert float(row['close_position']) == pytest.approx(0.85)

    def test_returns_false_when_no_filter_row_exists(self, repo):
        """Stage 1 이 먼저 돌아야 한다 — 행이 없으면 조용히 False."""
        assert repo.save_quantitative_features(KNOWN_CODE, TRADE_DATE, 1.0, 0.5) is False

    def test_only_the_matching_stock_and_date_is_updated(self, repo, sqlite_engine):
        repo.save_filter_scores(
            filter_df(filter_row('000660'), filter_row('051910')), TRADE_DATE)

        repo.save_quantitative_features('000660', TRADE_DATE, 1.0, 0.5)

        rows = {r['stock_code']: r for r in
                fetch_all(sqlite_engine, "SELECT * FROM stock_filter_score")}
        assert float(rows['000660']['morning_return']) == 1.0
        assert rows['051910']['morning_return'] is None

    def test_db_error_rolls_back_and_returns_false(self):
        class _Session(RecordingSession):
            def query(self, *a, **kw):
                raise SQLAlchemyError('db down')

        session = _Session()
        assert make_repo(session=session).save_quantitative_features(
            KNOWN_CODE, TRADE_DATE, 1.0, 0.5) is False
        assert session.rolled_back is True


# ===========================================================================
# 6. save_market_summary / update_market_sentiment
# ===========================================================================

def market_payload(**overrides):
    payload = {
        'kospi_index': 2678.45,
        'kospi_change_rate': 0.87,
        'kospi_volume': 512_340_000,
        'total_stocks': 100,
        'rising_stocks': 61,
        'falling_stocks': 33,
        'unchanged_stocks': 6,
        'total_foreign_net_buy': 120_000_000_000,
        'total_institutional_net_buy': -45_000_000_000,
        'market_sentiment_score': 0.1234,
    }
    payload.update(overrides)
    return payload


class TestSaveMarketSummary:

    def test_all_fields_are_persisted(self, repo, sqlite_engine):
        assert repo.save_market_summary(market_payload(), TRADE_DATE) is True

        row = fetch_all(sqlite_engine, "SELECT * FROM market_daily_summary")[0]
        assert float(row['kospi_index']) == pytest.approx(2678.45)
        assert float(row['kospi_change_rate']) == pytest.approx(0.87)
        assert row['kospi_volume'] == 512_340_000
        assert row['rising_stocks'] == 61
        assert row['total_institutional_net_buy'] == -45_000_000_000
        assert float(row['market_sentiment_score']) == pytest.approx(0.1234)

    def test_partial_payload_leaves_the_rest_null(self, repo, sqlite_engine):
        assert repo.save_market_summary({'kospi_index': 2500.0}, TRADE_DATE) is True

        row = fetch_all(sqlite_engine, "SELECT * FROM market_daily_summary")[0]
        assert float(row['kospi_index']) == 2500.0
        assert row['rising_stocks'] is None
        assert row['market_sentiment_score'] is None

    def test_rerun_replaces_the_row_for_that_date(self, repo, sqlite_engine):
        repo.save_market_summary(market_payload(kospi_index=2600.0), TRADE_DATE)
        repo.save_market_summary(market_payload(kospi_index=2700.0), TRADE_DATE)

        rows = fetch_all(sqlite_engine, "SELECT kospi_index FROM market_daily_summary")
        assert len(rows) == 1
        assert float(rows[0]['kospi_index']) == 2700.0

    def test_missing_kospi_index_raises_type_error_after_commit(self, repo, sqlite_engine):
        """알려진 결함: 성공 로그가 `kospi_index:.2f` 를 무조건 포맷한다.

        kospi_index 가 None 이면 커밋은 끝난 뒤 TypeError 가 터지고,
        `except SQLAlchemyError` 가 잡지 못해 호출측으로 전파된다.
        현재 동작을 고정해 두는 회귀 테스트.
        """
        with pytest.raises(TypeError):
            repo.save_market_summary({'rising_stocks': 10}, TRADE_DATE)

        # 예외 이전에 커밋은 이미 끝나 있다
        assert len(fetch_all(sqlite_engine, "SELECT id FROM market_daily_summary")) == 1

    def test_db_error_rolls_back_and_returns_false(self):
        class _Session(RecordingSession):
            def query(self, *a, **kw):
                raise SQLAlchemyError('db down')

        session = _Session()
        assert make_repo(session=session).save_market_summary(
            market_payload(), TRADE_DATE) is False
        assert session.rolled_back is True

    def test_save_kospi_index_delegates_to_save_market_summary(self, repo, sqlite_engine):
        assert repo.save_kospi_index(market_payload(), TRADE_DATE) is True
        assert len(fetch_all(sqlite_engine, "SELECT id FROM market_daily_summary")) == 1


class TestUpdateMarketSentiment:

    def test_updates_only_the_sentiment_column(self, repo, sqlite_engine):
        repo.save_market_summary(market_payload(market_sentiment_score=None), TRADE_DATE)

        assert repo.update_market_sentiment(TRADE_DATE, 0.4567) is True

        row = fetch_all(sqlite_engine, "SELECT * FROM market_daily_summary")[0]
        assert float(row['market_sentiment_score']) == pytest.approx(0.4567)
        assert float(row['kospi_index']) == pytest.approx(2678.45)   # 그대로

    def test_returns_false_when_the_row_is_missing(self, repo):
        """Stage 1 이 안 돌았어도 파이프라인을 중단시키지 않는다."""
        assert repo.update_market_sentiment(TRADE_DATE, 0.1) is False

    def test_db_error_rolls_back_and_returns_false(self):
        class _Session(RecordingSession):
            def query(self, *a, **kw):
                raise SQLAlchemyError('db down')

        session = _Session()
        assert make_repo(session=session).update_market_sentiment(TRADE_DATE, 0.1) is False
        assert session.rolled_back is True


# ===========================================================================
# 7. save_sentiment_analysis  (2 트랙 + 멱등성)
# ===========================================================================

class TestSaveSentimentAnalysis:

    def test_per_stock_track_inserts_a_row(self, repo, sqlite_engine):
        assert repo.save_sentiment_analysis(KNOWN_CODE, TRADE_DATE, 0.42, 5) is True

        row = fetch_all(sqlite_engine, "SELECT * FROM news_analysis")[0]
        assert row['stock_code'] == KNOWN_CODE
        assert float(row['sentiment_score']) == pytest.approx(0.42)
        assert row['news_count'] == 5

    def test_per_stock_rerun_upserts_instead_of_duplicating(self, repo, sqlite_engine):
        """UNIQUE(stock_code, analysis_date) — ON CONFLICT DO UPDATE."""
        repo.save_sentiment_analysis(KNOWN_CODE, TRADE_DATE, 0.42, 5)
        assert repo.save_sentiment_analysis(KNOWN_CODE, TRADE_DATE, -0.11, 9) is True

        rows = fetch_all(sqlite_engine, "SELECT * FROM news_analysis")
        assert len(rows) == 1
        assert float(rows[0]['sentiment_score']) == pytest.approx(-0.11)
        assert rows[0]['news_count'] == 9

    def test_market_track_uses_null_stock_code(self, repo, sqlite_engine):
        assert repo.save_sentiment_analysis(None, TRADE_DATE, 0.25, 40) is True

        row = fetch_all(sqlite_engine, "SELECT * FROM news_analysis")[0]
        assert row['stock_code'] is None
        assert row['news_count'] == 40

    def test_market_track_rerun_deletes_before_insert(self, repo, sqlite_engine):
        """NULL 은 UNIQUE 로 중복이 막히지 않으므로 명시적 DELETE 가 필요하다."""
        repo.save_sentiment_analysis(None, TRADE_DATE, 0.25, 40)
        repo.save_sentiment_analysis(None, TRADE_DATE, -0.05, 38)

        rows = fetch_all(sqlite_engine, "SELECT * FROM news_analysis")
        assert len(rows) == 1
        assert float(rows[0]['sentiment_score']) == pytest.approx(-0.05)

    def test_market_and_stock_tracks_coexist(self, repo, sqlite_engine):
        repo.save_sentiment_analysis(None, TRADE_DATE, 0.25, 40)
        repo.save_sentiment_analysis(KNOWN_CODE, TRADE_DATE, 0.42, 5)

        rows = fetch_all(sqlite_engine, "SELECT stock_code FROM news_analysis "
                                        "ORDER BY id")
        assert [r['stock_code'] for r in rows] == [None, KNOWN_CODE]

    def test_market_track_delete_does_not_touch_stock_rows(self, repo, sqlite_engine):
        repo.save_sentiment_analysis(KNOWN_CODE, TRADE_DATE, 0.42, 5)
        repo.save_sentiment_analysis(None, TRADE_DATE, 0.25, 40)
        repo.save_sentiment_analysis(None, TRADE_DATE, 0.30, 41)

        rows = fetch_all(sqlite_engine, "SELECT stock_code FROM news_analysis")
        assert sorted(r['stock_code'] or '' for r in rows) == ['', KNOWN_CODE]

    def test_other_dates_are_untouched(self, repo, sqlite_engine):
        repo.save_sentiment_analysis(KNOWN_CODE, date(2026, 7, 14), 0.1, 2)
        repo.save_sentiment_analysis(KNOWN_CODE, TRADE_DATE, 0.42, 5)

        assert len(fetch_all(sqlite_engine, "SELECT id FROM news_analysis")) == 2

    def test_db_error_rolls_back_and_returns_false(self):
        session = RecordingSession(fail_on='INSERT INTO news_analysis')

        assert make_repo(session=session).save_sentiment_analysis(
            KNOWN_CODE, TRADE_DATE, 0.42, 5) is False
        assert session.rolled_back is True
        assert session.closed is True

    def test_none_score_raises_type_error_after_commit(self, repo, sqlite_engine):
        """알려진 결함: 성공 로그가 `sentiment_score:.4f` 를 무조건 포맷한다.

        커밋 후 TypeError 가 나며 `except SQLAlchemyError` 로는 잡히지 않는다.
        """
        with pytest.raises(TypeError):
            repo.save_sentiment_analysis(KNOWN_CODE, TRADE_DATE, None, 0)

        assert len(fetch_all(sqlite_engine, "SELECT id FROM news_analysis")) == 1


# ===========================================================================
# 8. save_stock_news
# ===========================================================================

def article(**overrides):
    payload = {
        'title': 'SK하이닉스, HBM 수요 급증',
        'summary': '고대역폭 메모리 수요가 늘고 있다.',
        'url': 'https://news.example.com/1',
        'source': '연합뉴스',
        'published_at': datetime(2026, 7, 15, 9, 30),
        'sentiment_score': 0.72,
        'sentiment_label': 'positive',
        'keywords': ['HBM', '메모리'],
    }
    payload.update(overrides)
    return payload


class TestSaveStockNews:

    def _rows(self, session):
        """INSERT 실행의 파라미터 리스트."""
        for sql, params in session.executions:
            if 'INSERT INTO stock_news' in sql:
                return params
        return []

    def test_returns_inserted_row_count(self):
        session = RecordingSession()
        n = make_repo(session=session).save_stock_news(
            KNOWN_CODE, KNOWN_NAME, TRADE_DATE, [article(), article(url='u2')])

        assert n == 2
        assert session.committed is True
        assert session.closed is True

    def test_delete_precedes_insert(self):
        session = RecordingSession()
        make_repo(session=session).save_stock_news(
            KNOWN_CODE, KNOWN_NAME, TRADE_DATE, [article()])

        assert 'DELETE FROM stock_news' in session.sql_at(0)
        assert 'INSERT INTO stock_news' in session.sql_at(1)

    def test_tags_are_stock_name_then_label_then_keywords(self):
        session = RecordingSession()
        make_repo(session=session).save_stock_news(
            KNOWN_CODE, KNOWN_NAME, TRADE_DATE, [article()])

        tags = json.loads(self._rows(session)[0]['tags'])
        assert tags == [KNOWN_NAME, '긍정', 'HBM', '메모리']

    @pytest.mark.parametrize('label,expected', [
        ('positive', '긍정'),
        ('negative', '부정'),
        ('neutral', '중립'),
        ('unknown_label', '중립'),
        (None, '중립'),
    ])
    def test_sentiment_label_maps_to_korean_tag(self, label, expected):
        session = RecordingSession()
        make_repo(session=session).save_stock_news(
            KNOWN_CODE, None, TRADE_DATE, [article(sentiment_label=label)])

        assert json.loads(self._rows(session)[0]['tags'])[0] == expected

    def test_none_label_is_stored_as_neutral(self):
        session = RecordingSession()
        make_repo(session=session).save_stock_news(
            KNOWN_CODE, None, TRADE_DATE, [article(sentiment_label=None)])

        assert self._rows(session)[0]['sentiment_label'] == 'neutral'

    def test_none_stock_name_is_omitted_from_tags(self):
        session = RecordingSession()
        make_repo(session=session).save_stock_news(
            KNOWN_CODE, None, TRADE_DATE, [article()])

        assert json.loads(self._rows(session)[0]['tags']) == ['긍정', 'HBM', '메모리']

    def test_missing_keywords_default_to_empty(self):
        session = RecordingSession()
        make_repo(session=session).save_stock_news(
            KNOWN_CODE, KNOWN_NAME, TRADE_DATE, [article(keywords=None)])

        assert json.loads(self._rows(session)[0]['tags']) == [KNOWN_NAME, '긍정']

    def test_title_and_url_are_truncated(self):
        session = RecordingSession()
        make_repo(session=session).save_stock_news(
            KNOWN_CODE, KNOWN_NAME, TRADE_DATE,
            [article(title='가' * 900, url='https://x/' + 'y' * 2000)])

        row = self._rows(session)[0]
        assert len(row['title']) == 500
        assert len(row['url']) == 1000

    def test_blank_source_becomes_null(self):
        session = RecordingSession()
        make_repo(session=session).save_stock_news(
            KNOWN_CODE, KNOWN_NAME, TRADE_DATE, [article(source='')])

        assert self._rows(session)[0]['source'] is None

    def test_source_is_truncated_to_100_chars(self):
        session = RecordingSession()
        make_repo(session=session).save_stock_news(
            KNOWN_CODE, KNOWN_NAME, TRADE_DATE, [article(source='S' * 300)])

        assert len(self._rows(session)[0]['source']) == 100

    def test_missing_title_and_summary_become_empty_strings(self):
        session = RecordingSession()
        make_repo(session=session).save_stock_news(
            KNOWN_CODE, KNOWN_NAME, TRADE_DATE, [{'sentiment_label': 'neutral'}])

        row = self._rows(session)[0]
        assert row['title'] == ''
        assert row['summary'] == ''
        assert row['url'] == ''

    def test_empty_article_list_clears_existing_rows_and_returns_zero(self):
        session = RecordingSession()
        n = make_repo(session=session).save_stock_news(
            KNOWN_CODE, KNOWN_NAME, TRADE_DATE, [])

        assert n == 0
        assert len(session.executions) == 1
        assert 'DELETE FROM stock_news' in session.sql_at(0)
        assert session.committed is True

    def test_empty_list_delete_failure_is_swallowed(self):
        session = RecordingSession(fail_on='DELETE FROM stock_news')
        assert make_repo(session=session).save_stock_news(
            KNOWN_CODE, KNOWN_NAME, TRADE_DATE, []) == 0
        assert session.rolled_back is True

    def test_db_error_rolls_back_and_returns_zero(self):
        session = RecordingSession(fail_on='INSERT INTO stock_news')

        assert make_repo(session=session).save_stock_news(
            KNOWN_CODE, KNOWN_NAME, TRADE_DATE, [article()]) == 0
        assert session.rolled_back is True
        assert session.closed is True


# ===========================================================================
# 9. save_ai_decisions
# ===========================================================================

class TestSaveAiDecisions:

    def test_buy_and_sell_are_ranked_independently(self, repo, sqlite_engine):
        decisions = {
            'buy_top3': [{'stock_code': '000660', 'reason': '외인 순매수'},
                         {'stock_code': '051910', 'reason': '실적 개선'}],
            'sell_top3': [{'stock_code': '005490', 'reason': '추세 하락'}],
        }
        assert repo.save_ai_decisions(decisions, TRADE_DATE) is True

        rows = fetch_all(sqlite_engine,
                         'SELECT stock_code, decision, "rank", reason '
                         'FROM ai_trade_decision ORDER BY decision, "rank"')
        assert [(r['stock_code'], r['decision'], r['rank']) for r in rows] == [
            ('000660', 'BUY', 1),
            ('051910', 'BUY', 2),
            ('005490', 'SELL', 1),
        ]

    def test_stock_name_is_resolved_from_constants(self, repo, sqlite_engine):
        repo.save_ai_decisions({'buy_top3': [{'stock_code': KNOWN_CODE}]}, TRADE_DATE)

        row = fetch_all(sqlite_engine, "SELECT stock_name FROM ai_trade_decision")[0]
        assert row['stock_name'] == KNOWN_NAME

    def test_unknown_stock_code_becomes_unknown(self, repo, sqlite_engine):
        repo.save_ai_decisions({'sell_top3': [{'stock_code': UNKNOWN_CODE}]}, TRADE_DATE)

        row = fetch_all(sqlite_engine, "SELECT stock_name FROM ai_trade_decision")[0]
        assert row['stock_name'] == 'Unknown'

    def test_trade_date_is_written_to_decision_date(self, repo, sqlite_engine):
        repo.save_ai_decisions({'buy_top3': [{'stock_code': KNOWN_CODE}]}, TRADE_DATE)

        row = fetch_all(sqlite_engine, "SELECT decision_date FROM ai_trade_decision")[0]
        assert str(row['decision_date']).startswith('2026-07-15')

    def test_missing_reason_defaults_to_empty_string(self, repo, sqlite_engine):
        repo.save_ai_decisions({'buy_top3': [{'stock_code': KNOWN_CODE}]}, TRADE_DATE)

        row = fetch_all(sqlite_engine, "SELECT reason FROM ai_trade_decision")[0]
        assert row['reason'] == ''

    def test_empty_decisions_return_false(self, repo, sqlite_engine):
        assert repo.save_ai_decisions({'buy_top3': [], 'sell_top3': []}, TRADE_DATE) is False
        assert fetch_all(sqlite_engine, "SELECT id FROM ai_trade_decision") == []

    def test_missing_keys_return_false(self, repo):
        assert repo.save_ai_decisions({}, TRADE_DATE) is False

    def test_missing_stock_code_returns_false(self, repo):
        """KeyError 도 generic except 에 잡혀 False 가 된다."""
        assert repo.save_ai_decisions({'buy_top3': [{'reason': 'x'}]}, TRADE_DATE) is False

    def test_rerun_replaces_the_same_days_decisions(self, repo, sqlite_engine):
        """UNIQUE(stock_code, decision_date, decision) 아래에서도 재실행이 멱등해야 한다.

        순수 append 였을 때는 겹치는 한 종목 때문에 배치 전체가 롤백돼,
        2회차에 새로 나온 결정까지 통째로 유실됐다.
        """
        assert repo.save_ai_decisions(
            {'buy_top3': [{'stock_code': KNOWN_CODE, 'reason': 'run1'}]}, TRADE_DATE) is True
        assert repo.save_ai_decisions(
            {'buy_top3': [{'stock_code': KNOWN_CODE, 'reason': 'run2'},
                          {'stock_code': '051910', 'reason': 'new'}]}, TRADE_DATE) is True

        rows = fetch_all(sqlite_engine,
                         'SELECT stock_code, reason FROM ai_trade_decision ORDER BY "rank"')
        assert [(r['stock_code'], r['reason']) for r in rows] == [
            (KNOWN_CODE, 'run2'), ('051910', 'new')]

    def test_rerun_does_not_touch_other_dates(self, repo, sqlite_engine):
        other_date = date(2026, 7, 14)
        repo.save_ai_decisions({'buy_top3': [{'stock_code': KNOWN_CODE}]}, other_date)
        repo.save_ai_decisions({'buy_top3': [{'stock_code': KNOWN_CODE}]}, TRADE_DATE)
        repo.save_ai_decisions({'buy_top3': [{'stock_code': KNOWN_CODE}]}, TRADE_DATE)

        rows = fetch_all(sqlite_engine, "SELECT decision_date FROM ai_trade_decision")
        assert len(rows) == 2

    def test_explicit_conn_is_used(self, repo, sqlite_engine):
        with sqlite_engine.begin() as conn:
            assert repo.save_ai_decisions(
                {'buy_top3': [{'stock_code': KNOWN_CODE}]}, TRADE_DATE, conn=conn) is True

        assert len(fetch_all(sqlite_engine, "SELECT id FROM ai_trade_decision")) == 1


# ===========================================================================
# 10. save_prophet_forecast (deprecated)
# ===========================================================================

class TestSaveProphetForecastDeprecated:

    def test_appends_a_row_with_db_column_names(self, repo, sqlite_engine):
        with sqlite_engine.begin() as conn:
            assert repo.save_prophet_forecast(
                KNOWN_CODE, TRADE_DATE, 0.01, -0.02, 300.0, conn) is True

        row = fetch_all(sqlite_engine, "SELECT * FROM prophet_forecast")[0]
        assert row['stock_code'] == KNOWN_CODE
        assert float(row['price_trend']) == pytest.approx(0.01)
        assert float(row['volume_trend']) == pytest.approx(-0.02)
        assert float(row['price_uncertainty']) == pytest.approx(300.0)
        assert row['stock_name'] is None   # 이 경로는 종목명을 채우지 않는다

    def test_bad_connection_returns_false(self, repo):
        assert repo.save_prophet_forecast(
            KNOWN_CODE, TRADE_DATE, 0.01, -0.02, 300.0, conn=None) is False


# ===========================================================================
# 11. get_user_order_amount
# ===========================================================================

class TestGetUserOrderAmount:

    def test_returns_configured_amount(self, repo, sqlite_engine):
        with sqlite_engine.begin() as conn:
            conn.execute(text("INSERT INTO user_trade_config (user_id, order_amount) "
                              "VALUES (1, 3000000)"))

        assert repo.get_user_order_amount() == 3_000_000

    def test_respects_the_user_id_argument(self, repo, sqlite_engine):
        with sqlite_engine.begin() as conn:
            conn.execute(text("INSERT INTO user_trade_config (user_id, order_amount) "
                              "VALUES (1, 3000000), (2, 500000)"))

        assert repo.get_user_order_amount(user_id=2) == 500_000

    def test_missing_config_falls_back_to_one_million(self, repo):
        assert repo.get_user_order_amount(user_id=99) == 1_000_000

    def test_db_error_falls_back_to_one_million(self):
        session = RecordingSession(fail_on='user_trade_config')
        assert make_repo(session=session).get_user_order_amount() == 1_000_000
        assert session.closed is True


# ===========================================================================
# 12. save_trade_execution_plan
# ===========================================================================

def plan_record(**overrides):
    record = {
        'stock_code': KNOWN_CODE,
        'stock_name': KNOWN_NAME,
        'trade_type': 'BUY',
        'planned_quantity': 12,
        'reference_price': 81_500,
        'estimated_amount': 978_000,
        'gemini_reason': '외국인 순매수 지속',
        'gemini_rank': 1,
        'safety_filter_passed': True,
        'execution_status': 'SUCCESS',
        'order_no': 'KRX-0001',
        'execution_result': {'rt_cd': '0', 'msg1': '정상처리'},
    }
    record.update(overrides)
    return record


class TestSaveTradeExecutionPlan:

    def _rows(self, session):
        for sql, params in session.executions:
            if 'INSERT INTO trade_execution_plan' in sql:
                return params
        return []

    def test_returns_inserted_row_count(self):
        session = RecordingSession()
        n = make_repo(session=session).save_trade_execution_plan(
            1, TRADE_DATE, [plan_record(), plan_record(stock_code='051910')])

        assert n == 2
        assert session.committed is True
        assert session.closed is True

    def test_delete_precedes_insert_for_idempotency(self):
        session = RecordingSession()
        make_repo(session=session).save_trade_execution_plan(
            7, TRADE_DATE, [plan_record()])

        assert 'DELETE FROM trade_execution_plan' in session.sql_at(0)
        assert session.params_at(0) == {'u': 7, 'd': TRADE_DATE}
        assert 'INSERT INTO trade_execution_plan' in session.sql_at(1)

    def test_delete_spares_finalized_rows(self):
        """확정된 주문(EXECUTED/FAILED)은 재실행이 지우지 않는다 — order_no 가 유실된다."""
        session = RecordingSession()
        make_repo(session=session).save_trade_execution_plan(1, TRADE_DATE, [plan_record()])

        delete_sql = session.sql_at(0)
        assert "execution_status NOT IN ('EXECUTED', 'FAILED')" in delete_sql
        assert 'execution_status IS NULL' in delete_sql

    def test_insert_does_not_overwrite_finalized_rows(self):
        """DELETE 가 남긴 확정 행과 충돌하면 조용히 건너뛴다(되살리기 금지)."""
        session = RecordingSession()
        make_repo(session=session).save_trade_execution_plan(1, TRADE_DATE, [plan_record()])

        insert_sql = session.sql_at(1)
        assert 'ON CONFLICT (user_id, execution_date, stock_code, trade_type)' in insert_sql
        assert 'DO NOTHING' in insert_sql

    def test_all_fields_are_bound(self):
        session = RecordingSession()
        make_repo(session=session).save_trade_execution_plan(1, TRADE_DATE, [plan_record()])

        row = self._rows(session)[0]
        assert row['user_id'] == 1
        assert row['execution_date'] == TRADE_DATE
        assert row['stock_code'] == KNOWN_CODE
        assert row['trade_type'] == 'BUY'
        assert row['planned_quantity'] == 12
        assert row['reference_price'] == 81_500
        assert row['estimated_amount'] == 978_000
        assert row['gemini_rank'] == 1
        assert row['safety_filter_passed'] is True
        assert row['execution_status'] == 'SUCCESS'
        assert row['order_no'] == 'KRX-0001'
        assert json.loads(row['execution_result'])['rt_cd'] == '0'

    def test_missing_stock_name_falls_back_to_the_code(self):
        session = RecordingSession()
        make_repo(session=session).save_trade_execution_plan(
            1, TRADE_DATE, [plan_record(stock_name=None)])

        assert self._rows(session)[0]['stock_name'] == KNOWN_CODE

    def test_defaults_for_optional_fields(self):
        session = RecordingSession()
        minimal = {'stock_code': KNOWN_CODE, 'trade_type': 'SELL'}
        make_repo(session=session).save_trade_execution_plan(1, TRADE_DATE, [minimal])

        row = self._rows(session)[0]
        assert row['planned_quantity'] == 0
        assert row['gemini_rank'] == 0
        assert row['gemini_reason'] == ''
        assert row['safety_filter_passed'] is True
        assert row['execution_status'] == 'PENDING'
        assert row['order_no'] is None
        assert row['execution_result'] == '{}'
        assert row['reference_price'] is None

    def test_trade_type_is_truncated_to_four_chars(self):
        session = RecordingSession()
        make_repo(session=session).save_trade_execution_plan(
            1, TRADE_DATE, [plan_record(trade_type='SELL_ALL')])

        assert self._rows(session)[0]['trade_type'] == 'SELL'

    def test_long_fields_are_truncated(self):
        session = RecordingSession()
        make_repo(session=session).save_trade_execution_plan(1, TRADE_DATE, [plan_record(
            stock_name='가' * 300, execution_status='S' * 50, order_no='O' * 100)])

        row = self._rows(session)[0]
        assert len(row['stock_name']) == 100
        assert len(row['execution_status']) == 20
        assert len(row['order_no']) == 30

    def test_korean_json_is_not_escaped(self):
        session = RecordingSession()
        make_repo(session=session).save_trade_execution_plan(1, TRADE_DATE, [plan_record()])

        assert '정상처리' in self._rows(session)[0]['execution_result']

    def test_falsy_safety_filter_passed_is_preserved(self):
        session = RecordingSession()
        make_repo(session=session).save_trade_execution_plan(
            1, TRADE_DATE, [plan_record(safety_filter_passed=False)])

        assert self._rows(session)[0]['safety_filter_passed'] is False

    def test_empty_records_short_circuits(self):
        session = RecordingSession()
        assert make_repo(session=session).save_trade_execution_plan(1, TRADE_DATE, []) == 0
        assert session.executions == []

    def test_db_error_rolls_back_and_returns_zero(self):
        session = RecordingSession(fail_on='INSERT INTO trade_execution_plan')

        assert make_repo(session=session).save_trade_execution_plan(
            1, TRADE_DATE, [plan_record()]) == 0
        assert session.rolled_back is True
        assert session.closed is True


# ===========================================================================
# 12-1. update_trade_execution_result (trade.order.result 수신 시 상태 확정)
# ===========================================================================

class TestUpdateTradeExecutionResult:
    """Kafka 결과 메시지 → trade_execution_plan 상태 갱신.

    Postgres 전용 SQL(`jsonb ||`, `now()`, `COALESCE`)이라 RecordingSession 으로
    바인딩 파라미터와 SQL 형태만 검증한다. 실제 Postgres 실행은
    tests/test_kafka_integration.py 의 (d) 시나리오에서 확인한다.
    """

    def _call(self, session, **overrides):
        kwargs = {
            'user_id': 1,
            'execution_date': TRADE_DATE,
            'stock_code': KNOWN_CODE,
            'trade_type': 'BUY',
            'execution_status': 'EXECUTED',
            'order_no': '0000123',
            'error_message': None,
            'raw_result': {'idempotencyKey': f'1:{KNOWN_CODE}:{TRADE_DATE}:BUY', 'status': 'SUCCESS'},
        }
        kwargs.update(overrides)
        return make_repo(session=session).update_trade_execution_result(**kwargs)

    def test_returns_updated_row_count(self):
        session = RecordingSession(rowcount=1)

        assert self._call(session) == 1
        assert session.committed is True
        assert session.closed is True

    def test_matches_on_idempotency_key_components(self):
        """멱등키의 4개 구성요소로 행을 찾는다 (별도 키 컬럼 없이 1:1 매칭)."""
        session = RecordingSession()
        self._call(session)

        sql = session.sql_at(0)
        params = session.params_at(0)
        assert 'UPDATE trade_execution_plan' in sql
        assert 'WHERE user_id = :user_id' in sql
        assert 'AND execution_date = :execution_date' in sql
        assert 'AND stock_code = :stock_code' in sql
        assert 'AND trade_type = :trade_type' in sql
        assert params['user_id'] == 1
        assert params['execution_date'] == TRADE_DATE
        assert params['stock_code'] == KNOWN_CODE
        assert params['trade_type'] == 'BUY'

    def test_status_and_order_no_are_bound(self):
        session = RecordingSession()
        self._call(session)

        params = session.params_at(0)
        assert params['status'] == 'EXECUTED'
        assert params['order_no'] == '0000123'

    def test_order_no_is_preserved_when_absent(self):
        """결과에 주문번호가 없으면 기존 값을 지우지 않는다 (COALESCE)."""
        session = RecordingSession()
        self._call(session, order_no=None)

        assert 'COALESCE(:order_no, order_no)' in session.sql_at(0)
        assert session.params_at(0)['order_no'] is None

    def test_error_message_is_merged_into_execution_result(self):
        session = RecordingSession()
        self._call(session, execution_status='FAILED', error_message='주문가능금액 부족')

        patch = json.loads(session.params_at(0)['patch'])
        assert patch['error_message'] == '주문가능금액 부족'

    def test_raw_message_is_kept_for_traceability(self):
        session = RecordingSession()
        self._call(session)

        patch = json.loads(session.params_at(0)['patch'])
        assert patch['result_message']['status'] == 'SUCCESS'

    def test_existing_execution_result_is_merged_not_replaced(self):
        """QUEUED 시점에 남긴 멱등키가 결과 병합 후에도 남아야 추적이 된다."""
        session = RecordingSession()
        self._call(session)

        assert "COALESCE(execution_result, '{}'::jsonb)" in session.sql_at(0)
        assert '|| CAST(:patch AS JSONB)' in session.sql_at(0)

    def test_no_error_message_omits_the_field(self):
        session = RecordingSession()
        self._call(session, error_message=None)

        assert 'error_message' not in json.loads(session.params_at(0)['patch'])

    def test_korean_error_is_not_escaped(self):
        session = RecordingSession()
        self._call(session, error_message='장 종료')

        assert '장 종료' in session.params_at(0)['patch']

    def test_long_fields_are_truncated(self):
        session = RecordingSession()
        self._call(session, execution_status='S' * 50, order_no='O' * 100)

        params = session.params_at(0)
        assert len(params['status']) == 20
        assert len(params['order_no']) == 30

    def test_trade_type_is_truncated_to_four_chars(self):
        session = RecordingSession()
        self._call(session, trade_type='SELL_ALL')

        assert session.params_at(0)['trade_type'] == 'SELL'

    def test_no_matching_row_returns_zero(self):
        session = RecordingSession(rowcount=0)

        assert self._call(session) == 0

    def test_db_error_rolls_back_and_returns_zero(self):
        session = RecordingSession(fail_on='UPDATE trade_execution_plan')

        assert self._call(session) == 0
        assert session.rolled_back is True
        assert session.closed is True


# ===========================================================================
# 13. Stage 1 → Stage 2-1 → Stage 5 를 잇는 매핑 회귀 방어
# ===========================================================================

class TestColumnMappingRegression:
    """내부 DataFrame 이름과 DB 컬럼 이름의 대응을 한곳에 고정한다."""

    MAPPING = {
        'volume_ratio': 'vol_avg_multiple',
        'final_score': 'scaler_score',
        'institutional_net_buy': 'institutional_net_buy',
        'foreign_net_buy': 'foreign_net_buy',
        'price_volatility': 'price_volatility',
    }

    def test_save_then_read_back_through_both_names(self, repo, sqlite_engine):
        repo.save_filter_scores(filter_df(), TRADE_DATE)

        db_row = fetch_all(sqlite_engine, "SELECT * FROM stock_filter_score")[0]
        df_row = repo.get_filter_scores(TRADE_DATE).iloc[0]

        for df_name, db_name in self.MAPPING.items():
            assert float(df_row[df_name]) == pytest.approx(float(db_row[db_name])), df_name

    def test_score_date_is_the_db_name_for_trade_date(self, repo, sqlite_engine):
        repo.save_filter_scores(filter_df(), TRADE_DATE)

        columns = {c['name'] for c in
                   fetch_all(sqlite_engine, "SELECT name FROM pragma_table_info"
                                            "('stock_filter_score')")}
        assert 'score_date' in columns
        assert 'trade_date' not in columns

    def test_full_pipeline_slice_stage1_to_stage2_1(self, repo, sqlite_engine):
        """Stage 1 저장 → Stage 2-1 업데이트 → Stage 4 입력 조회."""
        repo.save_filter_scores(filter_df(
            filter_row('000660', is_selected=True),
            filter_row('051910', is_selected=False),
        ), TRADE_DATE)

        selected = repo.get_selected_stocks(TRADE_DATE)
        assert selected == ['000660']

        for code in selected:
            assert repo.save_quantitative_features(code, TRADE_DATE, 1.5, 0.72) is True

        row = fetch_all(sqlite_engine,
                        "SELECT * FROM stock_filter_score WHERE stock_code = '000660'")[0]
        assert float(row['morning_return']) == pytest.approx(1.5)
        assert float(row['close_position']) == pytest.approx(0.72)


def test_sanity_math_helpers_are_available():
    """테스트 자체가 쓰는 경계값 상수 확인 (오탈자 방어)."""
    assert math.isnan(float('nan'))
    assert math.isinf(float('inf'))
