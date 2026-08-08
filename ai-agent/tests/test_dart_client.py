"""Unit tests for collectors.dart_client (DARTAPIClient).

DART Open API 는 requests(동기) 로 호출하므로 collectors.dart_client.requests 를
패치해 네트워크를 차단하고, SQLAlchemy 엔진은 create_engine 을 MagicMock 으로
대체해 실제 DB 연결 없이 검증한다.
"""
import io
import zipfile
from datetime import date
from unittest.mock import MagicMock, patch

import pandas as pd
import pytest
import requests

from collectors import dart_client as dart_module
from collectors.dart_client import DARTAPIClient, DARTClient


@pytest.fixture
def client():
    """실제 DB 엔진 없이 초기화된 DARTAPIClient."""
    with patch.object(dart_module, 'create_engine', MagicMock()):
        return DARTAPIClient(api_key='test-dart-key')


def corp_code_zip(entries):
    """CORPCODE.xml 을 담은 ZIP 바이트 생성. entries: [(corp_code, stock_code, corp_name)]"""
    rows = ''.join(
        f'<list><corp_code>{c}</corp_code><corp_name>{n}</corp_name>'
        f'<stock_code>{s}</stock_code></list>'
        for c, s, n in entries
    )
    xml = f'<?xml version="1.0" encoding="UTF-8"?><result>{rows}</result>'

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w') as z:
        z.writestr('CORPCODE.xml', xml)
    return buffer.getvalue()


def fake_requests_get(content=None, json_data=None, exc=None, raise_for_status_exc=None):
    """collectors.dart_client.requests.get 대체용 MagicMock 생성."""
    if exc is not None:
        return MagicMock(side_effect=exc)

    response = MagicMock()
    response.content = content
    response.json.return_value = json_data
    if raise_for_status_exc is not None:
        response.raise_for_status.side_effect = raise_for_status_exc
    return MagicMock(return_value=response)


class TestInit:
    def test_uses_explicit_api_key(self, client):
        assert client.api_key == 'test-dart-key'
        assert client.corp_code_map == {}

    def test_missing_api_key_raises(self):
        settings = MagicMock()
        settings.dart_api_key = None
        with patch.object(dart_module, 'get_settings', return_value=settings), \
             patch.object(dart_module, 'create_engine', MagicMock()):
            with pytest.raises(ValueError, match='DART API key not configured'):
                DARTAPIClient(api_key=None)

    def test_falls_back_to_settings_key(self):
        settings = MagicMock()
        settings.dart_api_key = 'settings-key'
        with patch.object(dart_module, 'get_settings', return_value=settings), \
             patch.object(dart_module, 'create_engine', MagicMock()):
            assert DARTAPIClient().api_key == 'settings-key'

    def test_dartclient_alias_is_subclass(self):
        assert issubclass(DARTClient, DARTAPIClient)


class TestDownloadCorpCodeList:
    def test_builds_stock_code_to_corp_code_mapping(self, client):
        payload = corp_code_zip([
            ('00126380', '005930', '삼성전자'),
            ('00164779', '000660', 'SK하이닉스'),
        ])
        with patch.object(dart_module.requests, 'get', fake_requests_get(content=payload)) as mock_get:
            mapping = client.download_corp_code_list()

        assert mapping == {'005930': '00126380', '000660': '00164779'}
        assert client.corp_code_map == mapping
        assert mock_get.call_args.kwargs['params'] == {'crtfc_key': 'test-dart-key'}

    def test_unlisted_companies_are_filtered_out(self, client):
        payload = corp_code_zip([
            ('00126380', '005930', '삼성전자'),
            ('00999999', '', '비상장회사'),
            ('00888888', '   ', '공백코드'),
        ])
        with patch.object(dart_module.requests, 'get', fake_requests_get(content=payload)):
            mapping = client.download_corp_code_list()

        assert mapping == {'005930': '00126380'}

    def test_stock_code_is_trimmed(self, client):
        payload = corp_code_zip([('00126380 ', ' 005930 ', '삼성전자')])
        with patch.object(dart_module.requests, 'get', fake_requests_get(content=payload)):
            mapping = client.download_corp_code_list()

        assert mapping == {'005930': '00126380'}

    def test_request_exception_propagates(self, client):
        with patch.object(dart_module.requests, 'get',
                          fake_requests_get(exc=requests.exceptions.ConnectionError('down'))):
            with pytest.raises(requests.exceptions.ConnectionError):
                client.download_corp_code_list()

    def test_http_error_propagates(self, client):
        with patch.object(dart_module.requests, 'get', fake_requests_get(
                content=b'', raise_for_status_exc=requests.exceptions.HTTPError('500'))):
            with pytest.raises(requests.exceptions.HTTPError):
                client.download_corp_code_list()

    def test_corrupt_zip_propagates(self, client):
        with patch.object(dart_module.requests, 'get', fake_requests_get(content=b'not-a-zip')):
            with pytest.raises(Exception):
                client.download_corp_code_list()


class TestGetCorpCode:
    def test_returns_cached_value_without_download(self, client):
        client.corp_code_map = {'005930': '00126380'}
        with patch.object(client, 'download_corp_code_list') as mock_download:
            assert client.get_corp_code('005930') == '00126380'
        mock_download.assert_not_called()

    def test_triggers_download_when_cache_empty(self, client):
        def populate():
            client.corp_code_map = {'005930': '00126380'}
            return client.corp_code_map

        with patch.object(client, 'download_corp_code_list', side_effect=populate) as mock_download:
            assert client.get_corp_code('005930') == '00126380'
        mock_download.assert_called_once()

    def test_unknown_stock_code_returns_none(self, client):
        client.corp_code_map = {'005930': '00126380'}
        assert client.get_corp_code('999999') is None


class TestGetFinancialStatements:
    def test_success_returns_payload(self, client):
        payload = {'status': '000', 'message': '정상', 'list': [{'account_nm': '매출액'}]}
        with patch.object(dart_module.requests, 'get',
                          fake_requests_get(json_data=payload)) as mock_get:
            result = client.get_financial_statements('00126380', '2026', '11013', 'CFS')

        assert result == payload
        params = mock_get.call_args.kwargs['params']
        assert params == {
            'crtfc_key': 'test-dart-key',
            'corp_code': '00126380',
            'bsns_year': '2026',
            'reprt_code': '11013',
            'fs_div': 'CFS',
        }

    def test_default_params_are_11011_and_cfs(self, client):
        with patch.object(dart_module.requests, 'get',
                          fake_requests_get(json_data={'status': '000'})) as mock_get:
            client.get_financial_statements('00126380', '2026')

        params = mock_get.call_args.kwargs['params']
        assert params['reprt_code'] == '11011'
        assert params['fs_div'] == 'CFS'

    def test_status_013_no_data_returns_none(self, client):
        with patch.object(dart_module.requests, 'get',
                          fake_requests_get(json_data={'status': '013', 'message': '조회된 데이터가 없습니다'})):
            assert client.get_financial_statements('00126380', '2026') is None

    @pytest.mark.parametrize('status', ['010', '011', '020', '100', '800', '900'])
    def test_other_error_status_returns_none(self, client, status):
        with patch.object(dart_module.requests, 'get',
                          fake_requests_get(json_data={'status': status, 'message': 'error'})):
            assert client.get_financial_statements('00126380', '2026') is None

    def test_request_exception_returns_none(self, client):
        with patch.object(dart_module.requests, 'get',
                          fake_requests_get(exc=requests.exceptions.Timeout('timeout'))):
            assert client.get_financial_statements('00126380', '2026') is None

    def test_http_error_returns_none(self, client):
        with patch.object(dart_module.requests, 'get', fake_requests_get(
                raise_for_status_exc=requests.exceptions.HTTPError('500 Server Error'))):
            assert client.get_financial_statements('00126380', '2026') is None

    def test_invalid_json_returns_none(self, client):
        response = MagicMock()
        response.json.side_effect = ValueError('no json')
        with patch.object(dart_module.requests, 'get', MagicMock(return_value=response)):
            assert client.get_financial_statements('00126380', '2026') is None


def row(account_id='', account_nm='', sj_div='', amount=''):
    return {
        'account_id': account_id,
        'account_nm': account_nm,
        'sj_div': sj_div,
        'thstrm_amount': amount,
    }


class TestExtractFinancialMetrics:
    @pytest.mark.parametrize('payload', [None, {}, {'status': '000'}])
    def test_missing_list_returns_none(self, client, payload):
        assert client.extract_financial_metrics(payload) is None

    def test_computes_roe_and_operating_margin_from_account_ids(self, client):
        data = {'list': [
            row('ifrs-full_ProfitLoss', '당기순이익', 'IS', '1,000,000'),
            row('ifrs-full_Equity', '자본총계', 'BS', '10,000,000'),
            row('dart_OperatingIncomeLoss', '영업이익', 'IS', '2,000,000'),
            row('ifrs-full_Revenue', '매출액', 'IS', '20,000,000'),
        ]}

        metrics = client.extract_financial_metrics(data)

        assert metrics['roe'] == 10.0
        assert metrics['operating_margin'] == 10.0
        # PER 은 재무제표만으로 산출 불가 → 항상 None
        assert metrics['per'] is None

    def test_controlling_interest_values_take_priority(self, client):
        data = {'list': [
            row('ifrs-full_ProfitLoss', '당기순이익', 'IS', '1,000,000'),
            row('ifrs-full_ProfitLossAttributableToOwnersOfParent', '지배주주순이익', 'IS', '800,000'),
            row('ifrs-full_Equity', '자본총계', 'BS', '10,000,000'),
            row('ifrs-full_EquityAttributableToOwnersOfParent', '지배주주지분', 'BS', '8,000,000'),
        ]}

        metrics = client.extract_financial_metrics(data)

        # 800,000 / 8,000,000 = 10% (전체 값이 아닌 지배지분 기준)
        assert metrics['roe'] == 10.0

    def test_equity_outside_balance_sheet_is_ignored(self, client):
        data = {'list': [
            row('ifrs-full_ProfitLoss', '당기순이익', 'IS', '1,000,000'),
            # 자본변동표(SCE) 의 자본총계는 매칭되지 않아야 한다
            row('ifrs-full_Equity', '자본총계', 'SCE', '99,000,000'),
            row('ifrs-full_Equity', '자본총계', 'BS', '10,000,000'),
        ]}

        metrics = client.extract_financial_metrics(data)

        assert metrics['roe'] == 10.0

    def test_first_matching_row_wins(self, client):
        data = {'list': [
            row('ifrs-full_Revenue', '매출액', 'IS', '20,000,000'),
            row('ifrs-full_Revenue', '매출액', 'IS', '99,000,000'),
            row('dart_OperatingIncomeLoss', '영업이익', 'IS', '2,000,000'),
        ]}

        assert client.extract_financial_metrics(data)['operating_margin'] == 10.0

    def test_korean_name_fallback_when_account_id_missing(self, client):
        data = {'list': [
            row('', '당기순이익', 'IS', '1,000,000'),
            row('', '자본총계', 'BS', '10,000,000'),
            row('', '영업이익', 'IS', '3,000,000'),
            row('', '매출액', 'IS', '30,000,000'),
        ]}

        metrics = client.extract_financial_metrics(data)

        assert metrics['roe'] == 10.0
        assert metrics['operating_margin'] == 10.0

    def test_operating_margin_ratio_row_not_mistaken_for_income(self, client):
        data = {'list': [
            row('', '영업이익률', 'IS', '15'),
            row('', '영업이익', 'IS', '3,000,000'),
            row('', '매출액', 'IS', '30,000,000'),
        ]}

        assert client.extract_financial_metrics(data)['operating_margin'] == 10.0

    def test_total_capital_alias_accepted(self, client):
        data = {'list': [
            row('', '당기순이익', 'IS', '500,000'),
            row('', '총자본', 'BS', '5,000,000'),
        ]}

        assert client.extract_financial_metrics(data)['roe'] == 10.0

    @pytest.mark.parametrize('equity', ['0', '-'])
    def test_zero_or_missing_equity_yields_none_roe(self, client, equity):
        data = {'list': [
            row('ifrs-full_ProfitLoss', '당기순이익', 'IS', '1,000,000'),
            row('ifrs-full_Equity', '자본총계', 'BS', equity),
        ]}

        assert client.extract_financial_metrics(data)['roe'] is None

    def test_zero_revenue_yields_none_operating_margin(self, client):
        data = {'list': [
            row('dart_OperatingIncomeLoss', '영업이익', 'IS', '2,000,000'),
            row('ifrs-full_Revenue', '매출액', 'IS', '0'),
        ]}

        assert client.extract_financial_metrics(data)['operating_margin'] is None

    def test_empty_list_yields_all_none(self, client):
        metrics = client.extract_financial_metrics({'list': []})

        assert metrics == {'per': None, 'roe': None, 'operating_margin': None}

    def test_negative_net_income_produces_negative_roe(self, client):
        data = {'list': [
            row('ifrs-full_ProfitLoss', '당기순이익', 'IS', '-500,000'),
            row('ifrs-full_Equity', '자본총계', 'BS', '10,000,000'),
        ]}

        assert client.extract_financial_metrics(data)['roe'] == -5.0

    def test_results_rounded_to_two_decimals(self, client):
        data = {'list': [
            row('ifrs-full_ProfitLoss', '당기순이익', 'IS', '1'),
            row('ifrs-full_Equity', '자본총계', 'BS', '3'),
        ]}

        assert client.extract_financial_metrics(data)['roe'] == 33.33


class TestQuarterMapping:
    @pytest.mark.parametrize('base_date,expected', [
        (date(2026, 3, 31), '11013'),   # Q1 1분기보고서
        (date(2026, 1, 15), '11013'),
        (date(2026, 6, 30), '11012'),   # Q2 반기보고서
        (date(2026, 9, 30), '11014'),   # Q3 3분기보고서
        (date(2026, 12, 31), '11011'),  # Q4 사업보고서
        (date(2026, 10, 1), '11011'),
    ])
    def test_base_date_to_report_code(self, base_date, expected):
        assert DARTAPIClient._base_date_to_report_code(base_date) == expected

    @pytest.mark.parametrize('base_date,expected', [
        (date(2026, 3, 31), date(2025, 12, 31)),
        (date(2026, 6, 30), date(2026, 3, 31)),
        (date(2026, 9, 30), date(2026, 6, 30)),
        (date(2026, 12, 31), date(2026, 9, 30)),
    ])
    def test_previous_quarter_end(self, base_date, expected):
        assert DARTAPIClient._previous_quarter_end(base_date) == expected

    def test_walking_back_four_quarters_returns_previous_year(self):
        current = date(2026, 3, 31)
        for _ in range(4):
            current = DARTAPIClient._previous_quarter_end(current)
        assert current == date(2025, 3, 31)


class TestCollectFinancialsForStocks:
    def _metrics(self, roe=10.0, margin=5.0):
        return {'per': None, 'roe': roe, 'operating_margin': margin}

    def test_collects_rows_for_each_stock(self, client):
        client.corp_code_map = {'005930': '00126380', '000660': '00164779'}
        with patch.object(client, 'get_financial_statements', return_value={'list': []}) as mock_fs, \
             patch.object(client, 'extract_financial_metrics', return_value=self._metrics()):
            df = client.collect_financials_for_stocks(['005930', '000660'], base_date=date(2026, 3, 31))

        assert len(df) == 2
        assert list(df.columns) == ['stock_code', 'stock_name', 'base_date', 'per', 'roe', 'operating_margin']
        assert df.iloc[0]['roe'] == 10.0
        assert df.iloc[0]['base_date'] == date(2026, 3, 31)
        # Q1 → 11013 보고서 코드로 조회
        assert mock_fs.call_args_list[0].args[2] == '11013'

    def test_falls_back_to_ofs_when_cfs_missing(self, client):
        client.corp_code_map = {'005930': '00126380'}
        with patch.object(client, 'get_financial_statements',
                          side_effect=[None, {'list': []}]) as mock_fs, \
             patch.object(client, 'extract_financial_metrics', return_value=self._metrics()):
            df = client.collect_financials_for_stocks(['005930'], base_date=date(2026, 3, 31))

        assert len(df) == 1
        assert mock_fs.call_args_list[0].kwargs['fs_div'] == 'CFS'
        assert mock_fs.call_args_list[1].kwargs['fs_div'] == 'OFS'

    def test_skips_stock_when_both_cfs_and_ofs_missing(self, client):
        client.corp_code_map = {'005930': '00126380'}
        with patch.object(client, 'get_financial_statements', return_value=None):
            df = client.collect_financials_for_stocks(['005930'], base_date=date(2026, 3, 31))

        assert df.empty

    def test_skips_stock_without_corp_code(self, client):
        client.corp_code_map = {'005930': '00126380'}
        with patch.object(client, 'get_financial_statements', return_value={'list': []}), \
             patch.object(client, 'extract_financial_metrics', return_value=self._metrics()):
            df = client.collect_financials_for_stocks(['005930', '999999'], base_date=date(2026, 3, 31))

        assert list(df['stock_code']) == ['005930']

    def test_skips_stock_when_metrics_none(self, client):
        client.corp_code_map = {'005930': '00126380'}
        with patch.object(client, 'get_financial_statements', return_value={'list': []}), \
             patch.object(client, 'extract_financial_metrics', return_value=None):
            df = client.collect_financials_for_stocks(['005930'], base_date=date(2026, 3, 31))

        assert df.empty

    def test_exception_on_one_stock_does_not_stop_others(self, client):
        client.corp_code_map = {'005930': '00126380', '000660': '00164779'}
        with patch.object(client, 'get_financial_statements',
                          side_effect=[RuntimeError('boom'), {'list': []}]), \
             patch.object(client, 'extract_financial_metrics', return_value=self._metrics()):
            df = client.collect_financials_for_stocks(['005930', '000660'], base_date=date(2026, 3, 31))

        assert list(df['stock_code']) == ['000660']

    def test_downloads_corp_codes_when_cache_empty(self, client):
        def populate(*args, **kwargs):
            client.corp_code_map = {'005930': '00126380'}
            return client.corp_code_map

        with patch.object(client, 'download_corp_code_list', side_effect=populate) as mock_download, \
             patch.object(client, 'get_financial_statements', return_value={'list': []}), \
             patch.object(client, 'extract_financial_metrics', return_value=self._metrics()):
            client.collect_financials_for_stocks(['005930'], base_date=date(2026, 3, 31))

        mock_download.assert_called_once()

    def test_empty_stock_list_returns_empty_dataframe(self, client):
        client.corp_code_map = {'005930': '00126380'}
        df = client.collect_financials_for_stocks([], base_date=date(2026, 3, 31))

        assert df.empty


class TestCollectFinancialsWithFallback:
    def _df(self, codes):
        return pd.DataFrame([{'stock_code': c, 'roe': 10.0} for c in codes])

    def test_returns_first_quarter_that_meets_threshold(self, client):
        client.corp_code_map = {'x': 'y'}
        codes = ['005930', '000660', '051910', '035420']
        with patch.object(client, 'collect_financials_for_stocks',
                          return_value=self._df(codes)) as mock_collect:
            df, base_date = client.collect_financials_with_fallback(
                codes, start_base_date=date(2026, 3, 31))

        assert len(df) == 4
        assert base_date == date(2026, 3, 31)
        assert mock_collect.call_count == 1

    def test_steps_back_quarters_until_data_found(self, client):
        client.corp_code_map = {'x': 'y'}
        codes = ['005930', '000660']
        with patch.object(client, 'collect_financials_for_stocks', side_effect=[
            pd.DataFrame(), pd.DataFrame(), self._df(codes),
        ]) as mock_collect:
            df, base_date = client.collect_financials_with_fallback(
                codes, start_base_date=date(2026, 3, 31))

        assert len(df) == 2
        # Q1 2026 → Q4 2025 → Q3 2025
        assert base_date == date(2025, 9, 30)
        assert [c.kwargs['base_date'] for c in mock_collect.call_args_list] == [
            date(2026, 3, 31), date(2025, 12, 31), date(2025, 9, 30),
        ]

    def test_single_stock_threshold_is_one(self, client):
        client.corp_code_map = {'x': 'y'}
        with patch.object(client, 'collect_financials_for_stocks',
                          return_value=self._df(['005930'])):
            df, base_date = client.collect_financials_with_fallback(
                ['005930'], start_base_date=date(2026, 3, 31))

        assert base_date == date(2026, 3, 31)

    def test_below_half_threshold_keeps_walking_back(self, client):
        client.corp_code_map = {'x': 'y'}
        codes = ['1', '2', '3', '4']  # threshold = 2
        with patch.object(client, 'collect_financials_for_stocks', side_effect=[
            self._df(['1']), self._df(['1', '2']),
        ]) as mock_collect:
            df, base_date = client.collect_financials_with_fallback(
                codes, start_base_date=date(2026, 3, 31))

        assert mock_collect.call_count == 2
        assert base_date == date(2025, 12, 31)

    def test_all_quarters_failing_returns_empty_and_none(self, client):
        client.corp_code_map = {'x': 'y'}
        with patch.object(client, 'collect_financials_for_stocks',
                          return_value=pd.DataFrame()) as mock_collect:
            df, base_date = client.collect_financials_with_fallback(
                ['005930'], start_base_date=date(2026, 3, 31), max_lookback_quarters=3)

        assert df.empty
        assert base_date is None
        assert mock_collect.call_count == 3

    def test_corp_codes_downloaded_once_before_loop(self, client):
        with patch.object(client, 'download_corp_code_list') as mock_download, \
             patch.object(client, 'collect_financials_for_stocks',
                          return_value=self._df(['005930'])):
            client.collect_financials_with_fallback(['005930'], start_base_date=date(2026, 3, 31))

        mock_download.assert_called_once()


class TestSaveToDatabase:
    def test_empty_dataframe_returns_false(self, client):
        assert client.save_to_database(pd.DataFrame()) is False

    def test_deletes_then_inserts(self, client):
        df = pd.DataFrame([{'stock_code': '005930', 'base_date': date(2026, 3, 31), 'roe': 10.0}])

        conn = MagicMock()
        conn.execute.return_value.rowcount = 3
        client.engine.connect.return_value.__enter__.return_value = conn

        with patch.object(pd.DataFrame, 'to_sql') as mock_to_sql:
            assert client.save_to_database(df) is True

        conn.execute.assert_called_once()
        assert conn.execute.call_args.args[1] == {'base_date': date(2026, 3, 31)}
        conn.commit.assert_called_once()
        assert mock_to_sql.call_args.args[0] == 'stock_financial'
        assert mock_to_sql.call_args.kwargs['if_exists'] == 'append'

    def test_database_error_returns_false(self, client):
        df = pd.DataFrame([{'stock_code': '005930', 'base_date': date(2026, 3, 31), 'roe': 10.0}])
        client.engine.connect.side_effect = RuntimeError('connection refused')

        assert client.save_to_database(df) is False


class TestGetLatestFinancials:
    def test_reads_latest_quarter_per_stock(self, client):
        expected = pd.DataFrame([
            {'stock_code': '005930', 'per': 12.5, 'roe': 15.2, 'operating_margin': 18.5},
        ])
        with patch.object(dart_module.pd, 'read_sql', return_value=expected) as mock_read:
            df = client.get_latest_financials(['005930', '000660'])

        assert list(df['stock_code']) == ['005930']
        assert mock_read.call_args.kwargs['params'] == {'codes': ['005930', '000660']}

    def test_empty_result_returns_empty_dataframe(self, client):
        with patch.object(dart_module.pd, 'read_sql', return_value=pd.DataFrame()):
            assert client.get_latest_financials(['999999']).empty
