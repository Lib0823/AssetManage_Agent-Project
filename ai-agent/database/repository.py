"""Database repository for CRUD operations."""
import json
import logging
from datetime import date
from typing import List, Optional, Dict
import pandas as pd
from sqlalchemy.orm import Session
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy import text

from .models import StockFilterScore, MarketDailySummary, ProphetForecast, SessionLocal, engine
from config.constants import STOCK_NAMES

logger = logging.getLogger(__name__)


class DatabaseRepository:
    """Repository pattern for database operations."""

    def __init__(self):
        self.session_factory = SessionLocal
        self.engine = engine  # Added: Required for pandas to_sql() operations

    def save_filter_scores(self, df: pd.DataFrame, trade_date: date) -> bool:
        """
        Save stock filter scores to database.

        Args:
            df: DataFrame with columns matching StockFilterScore model
            trade_date: Trade date for these scores

        Returns:
            bool: True if successful, False otherwise
        """
        session = self.session_factory()
        try:
            logger.info(f"Saving {len(df)} filter scores for {trade_date}")
            logger.debug(f"DataFrame columns: {df.columns.tolist()}")

            # Delete existing records for this date (if any)
            session.query(StockFilterScore).filter(
                StockFilterScore.score_date == trade_date
            ).delete()

            # Convert DataFrame to model objects
            records = []
            for idx, row in df.iterrows():
                try:
                    record = StockFilterScore(
                        stock_code=row['stock_code'],
                        stock_name=row.get('stock_name', ''),  # Optional, defaults to empty if not present
                        score_date=trade_date,
                        foreign_net_buy=int(row['foreign_net_buy']),
                        institutional_net_buy=int(row['institutional_net_buy']),
                        vol_avg_multiple=float(row['volume_ratio']),
                        price_volatility=float(row['price_volatility']),
                        scaler_score=float(row['final_score']),
                        is_selected=bool(row['is_selected'])
                    )
                    records.append(record)
                except KeyError as e:
                    logger.error(f"Missing column in row {idx}: {e}")
                    logger.error(f"Available columns: {row.index.tolist()}")
                    raise

            # Bulk insert
            session.bulk_save_objects(records)
            session.commit()

            logger.info(f"Successfully saved {len(records)} filter scores")
            return True

        except SQLAlchemyError as e:
            logger.error(f"Database error while saving filter scores: {e}")
            session.rollback()
            return False

        finally:
            session.close()

    def get_filter_scores(self, trade_date: date) -> Optional[pd.DataFrame]:
        """
        Retrieve filter scores for a specific date.

        Args:
            trade_date: Trade date to retrieve

        Returns:
            DataFrame or None if no data found
        """
        session = self.session_factory()
        try:
            records = session.query(StockFilterScore).filter(
                StockFilterScore.score_date == trade_date
            ).all()

            if not records:
                logger.warning(f"No filter scores found for {trade_date}")
                return None

            # Convert to DataFrame
            data = [{
                'stock_code': r.stock_code,
                'stock_name': r.stock_name,
                'score_date': r.score_date,
                'foreign_net_buy': r.foreign_net_buy,
                'institutional_net_buy': r.institutional_net_buy,
                'volume_ratio': float(r.vol_avg_multiple),
                'price_volatility': float(r.price_volatility),
                'final_score': float(r.scaler_score),
                'is_selected': r.is_selected
            } for r in records]

            df = pd.DataFrame(data)
            logger.info(f"Retrieved {len(df)} filter scores for {trade_date}")

            return df

        except SQLAlchemyError as e:
            logger.error(f"Database error while retrieving filter scores: {e}")
            return None

        finally:
            session.close()

    def get_selected_stocks(self, trade_date: date) -> List[str]:
        """
        Get list of selected stock codes for a specific date.

        Args:
            trade_date: Trade date

        Returns:
            List of stock codes (empty list if none found)
        """
        session = self.session_factory()
        try:
            records = session.query(StockFilterScore.stock_code).filter(
                StockFilterScore.score_date == trade_date,
                StockFilterScore.is_selected == True
            ).all()

            stock_codes = [r.stock_code for r in records]
            logger.info(f"Retrieved {len(stock_codes)} selected stocks for {trade_date}")

            return stock_codes

        except SQLAlchemyError as e:
            logger.error(f"Database error while retrieving selected stocks: {e}")
            return []

        finally:
            session.close()

    def get_latest_filter_date(self) -> Optional[date]:
        """
        Get the most recent trade date with filter scores.

        Returns:
            date or None if no data exists
        """
        session = self.session_factory()
        try:
            result = session.query(StockFilterScore.score_date).order_by(
                StockFilterScore.score_date.desc()
            ).first()

            if result:
                latest_date = result[0]
                logger.info(f"Latest filter date: {latest_date}")
                return latest_date
            else:
                logger.warning("No filter scores found in database")
                return None

        except SQLAlchemyError as e:
            logger.error(f"Database error while retrieving latest date: {e}")
            return None

        finally:
            session.close()

    def save_quantitative_features(self,
                                   stock_code: str,
                                   trade_date: date,
                                   morning_return: float,
                                   close_position: float) -> bool:
        """
        Save quantitative features (morning_return, close_position) to stock_filter_score table.

        Args:
            stock_code: 6-digit stock code
            trade_date: Trade date
            morning_return: Morning return percentage (09:00 → 10:00)
            close_position: Close position in day's range (0.0 ~ 1.0)

        Returns:
            True if successful, False otherwise
        """
        session = self.session_factory()
        try:
            # Update existing record (created by filter stage)
            record = session.query(StockFilterScore).filter(
                StockFilterScore.stock_code == stock_code,
                StockFilterScore.score_date == trade_date
            ).first()

            if record:
                record.morning_return = morning_return
                record.close_position = close_position
                session.commit()
                logger.debug(f"Updated quantitative features for {stock_code}")
                return True
            else:
                logger.warning(f"No filter score record found for {stock_code} on {trade_date}")
                return False

        except SQLAlchemyError as e:
            logger.error(f"Error saving quantitative features for {stock_code}: {e}")
            session.rollback()
            return False

        finally:
            session.close()

    def save_sentiment_analysis(self,
                                stock_code: str,
                                analysis_date: date,
                                sentiment_score: float,
                                news_count: int) -> bool:
        """
        Save sentiment analysis results to news_analysis table.

        Args:
            stock_code: 6-digit stock code
            analysis_date: Analysis date
            sentiment_score: Sentiment score (-1.0 ~ 1.0)
            news_count: Number of news articles analyzed

        Returns:
            True if successful, False otherwise

        Note:
            news_analysis 에는 UNIQUE(stock_code, analysis_date) 제약이 있다.
            (단, Postgres 에서 NULL 은 서로 distinct 하므로 stock_code IS NULL 인
             시장 전반 행은 제약만으로는 중복을 막지 못한다.)
            따라서 단순 append 대신 "같은 (stock_code, analysis_date) 행을 먼저
            삭제 후 삽입"하는 upsert 로 동작시켜, 재실행/시장행 중복을 모두 방지한다.
        """
        session = self.session_factory()
        try:
            if stock_code is None:
                # 시장 전반(Track 1) 행: NULL 은 unique 제약으로 중복 차단이 안 되므로
                # IS NULL 매칭으로 명시적으로 기존 행을 제거한 뒤 1건만 삽입한다.
                session.execute(
                    text(
                        "DELETE FROM news_analysis "
                        "WHERE stock_code IS NULL AND analysis_date = :d"
                    ),
                    {'d': analysis_date}
                )
                session.execute(
                    text(
                        "INSERT INTO news_analysis "
                        "(stock_code, analysis_date, sentiment_score, news_count) "
                        "VALUES (NULL, :d, :s, :n)"
                    ),
                    {'d': analysis_date, 's': sentiment_score, 'n': news_count}
                )
            else:
                # 종목별(Track 2) 행: 재실행 시 unique 제약 위반을 피하기 위해
                # 동일 키 행을 갱신하거나(ON CONFLICT) 신규 삽입한다.
                session.execute(
                    text(
                        "INSERT INTO news_analysis "
                        "(stock_code, analysis_date, sentiment_score, news_count) "
                        "VALUES (:c, :d, :s, :n) "
                        "ON CONFLICT (stock_code, analysis_date) DO UPDATE SET "
                        "sentiment_score = EXCLUDED.sentiment_score, "
                        "news_count = EXCLUDED.news_count"
                    ),
                    {'c': stock_code, 'd': analysis_date, 's': sentiment_score, 'n': news_count}
                )

            session.commit()
            logger.debug(f"Saved sentiment analysis for {stock_code}: {sentiment_score:.4f}")
            return True

        except SQLAlchemyError as e:
            session.rollback()
            logger.error(f"Error saving sentiment analysis for {stock_code}: {e}")
            return False
        finally:
            session.close()

    def save_stock_news(
        self,
        stock_code: str,
        stock_name: Optional[str],
        analysis_date: date,
        articles: List[Dict]
    ) -> int:
        """
        Persist per-stock news articles to the stock_news table (idempotent).

        save_sentiment_analysis 와 동일한 DELETE-then-INSERT 멱등 패턴을 따른다:
        (stock_code, analysis_date) 키의 기존 행을 모두 삭제한 뒤 새 기사들을
        삽입하므로, 같은 날 재실행해도 중복이 쌓이지 않는다. 전체를 단일
        트랜잭션으로 처리하며 실패 시 롤백한다.

        각 기사의 tags(JSONB array)는 ``[stock_name, 감성라벨(한글)] + keywords``
        형태로 구성한다. stock_name 이 None 이면 태그에서 제외한다.

        Args:
            stock_code: 6-digit stock code.
            stock_name: 종목명(태그 선두에 포함). None 이면 태그에서 제외.
            analysis_date: 분석 기준일 (stock_news.analysis_date).
            articles: 기사 레코드 리스트. 각 dict 는
                {title, summary, url, source, published_at, sentiment_score,
                 sentiment_label, keywords} 키를 가진다.

        Returns:
            int: 실제로 삽입한 기사 행 수 (실패 시 0).
        """
        if not articles:
            # 기사가 없으면 기존 행만 정리하고 0 반환 (멱등성 유지)
            session = self.session_factory()
            try:
                session.execute(
                    text(
                        "DELETE FROM stock_news "
                        "WHERE stock_code = :c AND analysis_date = :d"
                    ),
                    {'c': stock_code, 'd': analysis_date}
                )
                session.commit()
            except SQLAlchemyError as e:
                session.rollback()
                logger.error(f"Error clearing stock_news for {stock_code}: {e}")
            finally:
                session.close()
            return 0

        # 감성 라벨(영문) → 한글 태그 매핑
        label_ko = {'positive': '긍정', 'negative': '부정', 'neutral': '중립'}

        insert_sql = text(
            "INSERT INTO stock_news "
            "(stock_code, stock_name, analysis_date, title, summary, url, source, "
            " sentiment_score, sentiment_label, tags, published_at) "
            "VALUES "
            "(:stock_code, :stock_name, :analysis_date, :title, :summary, :url, :source, "
            " :sentiment_score, :sentiment_label, CAST(:tags AS jsonb), :published_at)"
        )

        params = []
        for article in articles:
            label = article.get('sentiment_label') or 'neutral'

            # tags = [종목명, 감성라벨(한글)] + keywords (None 종목명은 제외)
            tags: List[str] = []
            if stock_name:
                tags.append(stock_name)
            tags.append(label_ko.get(label, '중립'))
            keywords = article.get('keywords') or []
            tags.extend(str(k) for k in keywords)

            title = (article.get('title') or '')[:500]
            summary = article.get('summary') or ''
            url = (article.get('url') or '')[:1000]
            source = (article.get('source') or '')[:100] or None

            params.append({
                'stock_code': stock_code,
                'stock_name': stock_name,
                'analysis_date': analysis_date,
                'title': title,
                'summary': summary,
                'url': url,
                'source': source,
                'sentiment_score': article.get('sentiment_score'),
                'sentiment_label': label,
                'tags': json.dumps(tags, ensure_ascii=False),
                'published_at': article.get('published_at'),
            })

        session = self.session_factory()
        try:
            session.execute(
                text(
                    "DELETE FROM stock_news "
                    "WHERE stock_code = :c AND analysis_date = :d"
                ),
                {'c': stock_code, 'd': analysis_date}
            )
            session.execute(insert_sql, params)
            session.commit()
            logger.debug(f"Saved {len(params)} stock_news rows for {stock_code}")
            return len(params)

        except SQLAlchemyError as e:
            session.rollback()
            logger.error(f"Error saving stock_news for {stock_code}: {e}")
            return 0

        finally:
            session.close()

    def save_market_summary(
        self,
        summary_data: Dict[str, any],
        summary_date: date
    ) -> bool:
        """
        Save market daily summary to market_daily_summary table.

        Args:
            summary_data: Dict with market summary data
            summary_date: Summary date

        Returns:
            True if successful, False otherwise
        """
        session = self.session_factory()
        try:
            logger.info(f"Saving market summary for {summary_date}")

            # Delete existing record for this date (if any)
            session.query(MarketDailySummary).filter(
                MarketDailySummary.summary_date == summary_date
            ).delete()

            # Create new record with all available fields
            record = MarketDailySummary(
                summary_date=summary_date,
                kospi_index=summary_data.get('kospi_index'),
                kospi_change_rate=summary_data.get('kospi_change_rate'),
                kospi_volume=summary_data.get('kospi_volume'),
                total_stocks=summary_data.get('total_stocks'),
                rising_stocks=summary_data.get('rising_stocks'),
                falling_stocks=summary_data.get('falling_stocks'),
                unchanged_stocks=summary_data.get('unchanged_stocks'),
                total_foreign_net_buy=summary_data.get('total_foreign_net_buy'),
                total_institutional_net_buy=summary_data.get('total_institutional_net_buy'),
                market_sentiment_score=summary_data.get('market_sentiment_score')
            )

            session.add(record)
            session.commit()

            logger.info(f"Successfully saved market summary: KOSPI={summary_data.get('kospi_index'):.2f}")
            return True

        except SQLAlchemyError as e:
            logger.error(f"Database error while saving market summary: {e}")
            session.rollback()
            return False

        finally:
            session.close()

    def update_market_sentiment(
        self,
        summary_date: date,
        market_sentiment_score: float
    ) -> bool:
        """
        Update market_sentiment_score on an existing market_daily_summary row.

        Stage 1 에서 insert 된 해당 날짜의 행을 찾아 Track 1(시장 전반) 감성
        점수만 갱신한다. 스키마는 변경하지 않으며, 행이 없으면 경고만 남기고
        실패(False)를 반환한다 (파이프라인을 중단시키지 않음).

        Args:
            summary_date: market_daily_summary 대상 날짜 (Stage 1 trade_date 와 동일)
            market_sentiment_score: Track 1 시장 감성점수 (-1.0 ~ 1.0)

        Returns:
            True if a row was updated, False otherwise
        """
        session = self.session_factory()
        try:
            record = session.query(MarketDailySummary).filter(
                MarketDailySummary.summary_date == summary_date
            ).first()

            if record is None:
                logger.warning(
                    f"No market_daily_summary row for {summary_date}; "
                    f"cannot update market_sentiment_score"
                )
                return False

            record.market_sentiment_score = market_sentiment_score
            session.commit()
            logger.info(
                f"Updated market_sentiment_score={market_sentiment_score:.4f} "
                f"for {summary_date}"
            )
            return True

        except SQLAlchemyError as e:
            logger.error(f"Database error while updating market sentiment: {e}")
            session.rollback()
            return False

        finally:
            session.close()

    # Deprecated: kept for backward compatibility
    def save_kospi_index(self, kospi_data: Dict[str, float], trade_date: date) -> bool:
        """Deprecated: Use save_market_summary instead."""
        return self.save_market_summary(kospi_data, trade_date)

    def save_prophet_forecast_detailed(self, forecast_data: Dict, trade_date: date) -> bool:
        """
        Save detailed Prophet forecast results with D+1~D+5 values to prophet_forecast table.

        Args:
            forecast_data: Dict with all Prophet forecast values including detailed D+1~D+5 predictions
            trade_date: Trade date

        Returns:
            True if successful, False otherwise
        """
        session = self.session_factory()
        try:
            stock_code = forecast_data['stock_code']
            logger.debug(f"Saving detailed Prophet forecast for {stock_code}")

            # Delete existing record for this stock and date (if any)
            session.query(ProphetForecast).filter(
                ProphetForecast.stock_code == stock_code,
                ProphetForecast.forecast_date == trade_date  # Fixed: use forecast_date column name
            ).delete()

            # Helper function to clamp numeric values to DB column limits
            def clamp_value(value, max_val, min_val=None):
                """Clamp value to prevent numeric overflow in DB"""
                if value is None:
                    return None
                if min_val is not None:
                    value = max(min_val, value)
                return min(max_val, value)

            # Clamp values to DB column limits to prevent overflow
            # NUMERIC(12, 2) → max = 9,999,999,999.99
            # NUMERIC(10, 6) → max = 9,999.999999
            # NUMERIC(10, 4) → max = 999,999.9999

            price_trend_val = clamp_value(forecast_data.get('prophet_price_trend', 0.0), 9999.0, -9999.0)
            volume_trend_val = clamp_value(forecast_data.get('prophet_volume_trend', 0.0), 9999.0, -9999.0)
            uncertainty_val = clamp_value(forecast_data.get('prophet_price_uncertainty', 0.0), 999999.0, 0.0)

            # Create new record with all detailed values
            record = ProphetForecast(
                stock_code=stock_code,
                stock_name=forecast_data.get('stock_name', stock_code),  # Added: stock_name required by DB schema
                forecast_date=trade_date,  # Fixed: use forecast_date column name
                # Aggregated trends - fixed column names to match DB schema with clamping
                price_trend=price_trend_val,
                volume_trend=volume_trend_val,
                price_uncertainty=uncertainty_val,
                # Detailed predictions (D+1 to D+5) - fixed column names to match DB schema
                # Clamp to NUMERIC(12, 2) max = 9,999,999,999.99
                yhat_d1=clamp_value(forecast_data.get('yhat_price_d1'), 9999999999.99, 0.0),
                yhat_d2=clamp_value(forecast_data.get('yhat_price_d2'), 9999999999.99, 0.0),
                yhat_d3=clamp_value(forecast_data.get('yhat_price_d3'), 9999999999.99, 0.0),
                yhat_d4=clamp_value(forecast_data.get('yhat_price_d4'), 9999999999.99, 0.0),
                yhat_d5=clamp_value(forecast_data.get('yhat_price_d5'), 9999999999.99, 0.0),
                yhat_lower_d1=clamp_value(forecast_data.get('yhat_price_lower_d1'), 9999999999.99, 0.0),
                yhat_lower_d2=clamp_value(forecast_data.get('yhat_price_lower_d2'), 9999999999.99, 0.0),
                yhat_lower_d3=clamp_value(forecast_data.get('yhat_price_lower_d3'), 9999999999.99, 0.0),
                yhat_lower_d4=clamp_value(forecast_data.get('yhat_price_lower_d4'), 9999999999.99, 0.0),
                yhat_lower_d5=clamp_value(forecast_data.get('yhat_price_lower_d5'), 9999999999.99, 0.0),
                yhat_upper_d1=clamp_value(forecast_data.get('yhat_price_upper_d1'), 9999999999.99, 0.0),
                yhat_upper_d2=clamp_value(forecast_data.get('yhat_price_upper_d2'), 9999999999.99, 0.0),
                yhat_upper_d3=clamp_value(forecast_data.get('yhat_price_upper_d3'), 9999999999.99, 0.0),
                yhat_upper_d4=clamp_value(forecast_data.get('yhat_price_upper_d4'), 9999999999.99, 0.0),
                yhat_upper_d5=clamp_value(forecast_data.get('yhat_price_upper_d5'), 9999999999.99, 0.0)
            )

            session.add(record)
            session.commit()

            logger.debug(f"Successfully saved detailed Prophet forecast for {stock_code}")
            return True

        except SQLAlchemyError as e:
            logger.error(f"Database error while saving Prophet forecast: {e}")
            session.rollback()
            return False

        finally:
            session.close()

    def save_prophet_forecast(self,
                             stock_code: str,
                             forecast_date: date,
                             prophet_price_trend: float,
                             prophet_volume_trend: float,
                             prophet_price_uncertainty: float,
                             conn) -> bool:
        """
        [Deprecated] Save Prophet forecast results to prophet_forecast table.
        Use save_prophet_forecast_detailed() for new implementations.

        Args:
            stock_code: 6-digit stock code
            forecast_date: Forecast base date
            prophet_price_trend: Price trend slope
            prophet_volume_trend: Volume trend slope
            prophet_price_uncertainty: Price uncertainty (CI width)
            conn: Database connection

        Returns:
            True if successful, False otherwise
        """
        try:
            import pandas as pd
            df = pd.DataFrame([{
                'stock_code': stock_code,
                'forecast_date': forecast_date,
                'price_trend': prophet_price_trend,
                'volume_trend': prophet_volume_trend,
                'price_uncertainty': prophet_price_uncertainty
            }])

            df.to_sql('prophet_forecast', conn, if_exists='append', index=False)
            logger.debug(f"Saved Prophet forecast for {stock_code}")
            return True

        except Exception as e:
            logger.error(f"Error saving Prophet forecast for {stock_code}: {e}")
            return False

    def save_ai_decisions(self,
                         decisions: dict,
                         trade_date: date,
                         conn=None) -> bool:
        """
        Save Gemini AI trading decisions to ai_trade_decision table.

        Args:
            decisions: Dict with buy_top3 and sell_top3 lists
            trade_date: Trade date
            conn: Database connection (optional, uses engine if None)

        Returns:
            True if successful, False otherwise
        """
        try:
            import pandas as pd
            records = []

            # Buy decisions
            for idx, item in enumerate(decisions.get('buy_top3', [])):
                stock_code = item['stock_code']
                records.append({
                    'stock_code': stock_code,
                    'stock_name': STOCK_NAMES.get(stock_code, 'Unknown'),  # Lookup from constants
                    'decision_date': trade_date,  # Changed from trade_date
                    'decision': 'BUY',  # Changed from decision_type
                    'reason': item.get('reason', ''),
                    'rank': idx + 1
                })

            # Sell decisions
            for idx, item in enumerate(decisions.get('sell_top3', [])):
                stock_code = item['stock_code']
                records.append({
                    'stock_code': stock_code,
                    'stock_name': STOCK_NAMES.get(stock_code, 'Unknown'),  # Lookup from constants
                    'decision_date': trade_date,  # Changed from trade_date
                    'decision': 'SELL',  # Changed from decision_type
                    'reason': item.get('reason', ''),
                    'rank': idx + 1
                })

            if records:
                df = pd.DataFrame(records)
                # Use engine if conn not provided
                db_conn = conn if conn is not None else self.engine
                df.to_sql('ai_trade_decision', db_conn, if_exists='append', index=False)
                logger.info(f"Saved {len(records)} AI trading decisions")
                return True
            else:
                logger.warning("No AI decisions to save")
                return False

        except Exception as e:
            logger.error(f"Error saving AI decisions: {e}")
            return False

    def save_safety_filter_results(self,
                                   filter_results: list,
                                   filter_date: date,
                                   conn=None) -> bool:
        """
        Save safety filter results to safety_filter_result table.

        Args:
            filter_results: List of filter result dicts
            filter_date: Filter date
            conn: Database connection (optional, uses engine if None)

        Returns:
            True if successful, False otherwise
        """
        import json
        import math
        import numpy as np

        def _sanitize(value):
            """
            Recursively sanitize a value for JSON/JSONB serialization.

            Converts numpy scalar types to native Python types and maps any
            non-finite float (NaN, +Inf, -Inf) to None so PostgreSQL's JSON/JSONB
            type accepts the result (it rejects the NaN/Infinity literal tokens
            that json.dumps emits by default).

            Args:
                value: Arbitrary value (dict, list, numpy/native scalar, None)

            Returns:
                A JSON-safe value composed of native Python types only.
            """
            # 중첩 dict 재귀 처리
            if isinstance(value, dict):
                return {k: _sanitize(v) for k, v in value.items()}
            # 중첩 list/tuple 재귀 처리
            if isinstance(value, (list, tuple)):
                return [_sanitize(v) for v in value]
            # np.bool_ → bool (bool 체크를 int보다 먼저 수행)
            if isinstance(value, (bool, np.bool_)):
                return bool(value)
            # numpy/native 정수 → int
            if isinstance(value, (int, np.integer)):
                return int(value)
            # numpy/native 실수 → float, NaN/±Inf → None
            if isinstance(value, (float, np.floating)):
                f = float(value)
                if math.isnan(f) or math.isinf(f):
                    return None
                return f
            return value

        def _sanitize_scalar_float(value):
            """NUMERIC 컬럼용: numpy/native float 변환, NaN/±Inf/None → None."""
            if value is None:
                return None
            try:
                f = float(value)
            except (TypeError, ValueError):
                return None
            if math.isnan(f) or math.isinf(f):
                return None
            return f

        def _sanitize_scalar_int(value):
            """정수 컬럼용: numpy/native int 변환, NaN/None → None."""
            if value is None:
                return None
            if isinstance(value, (float, np.floating)) and (math.isnan(float(value)) or math.isinf(float(value))):
                return None
            try:
                return int(value)
            except (TypeError, ValueError):
                return None

        def _build_record(result):
            """단일 filter_result dict → 정제된 INSERT 파라미터 dict."""
            sanitized_checks = _sanitize(result.get('filter_checks', {}))
            # allow_nan=False로 잔여 비유한값이 있으면 로컬에서 예외 발생 (잘못된 JSON 생성 방지)
            checks_json = json.dumps(sanitized_checks, ensure_ascii=False, allow_nan=False)

            failure_reason = result.get('failure_reason')
            if failure_reason is not None:
                failure_reason = str(failure_reason)

            return {
                'stock_code': str(result['stock_code']),
                'stock_name': str(result.get('stock_name', '')),
                'filter_date': filter_date,
                'passed': bool(result['passed']),
                'failure_reason': failure_reason,
                'max_quantity': _sanitize_scalar_int(result.get('max_quantity')),
                'current_price': _sanitize_scalar_float(result.get('current_price')),
                'filter_checks': checks_json,
            }

        if not filter_results:
            logger.warning("No safety filter results to save")
            return False

        # filter_checks는 JSONB 컬럼이므로 CAST 필요
        insert_sql = text("""
            INSERT INTO safety_filter_result
                (stock_code, stock_name, filter_date, passed, failure_reason,
                 max_quantity, current_price, filter_checks)
            VALUES
                (:stock_code, :stock_name, :filter_date, :passed, :failure_reason,
                 :max_quantity, :current_price, CAST(:filter_checks AS JSONB))
        """)

        # 정제 단계: 행 단위로 실패해도 나머지 행은 보존
        records = []
        for result in filter_results:
            try:
                records.append(_build_record(result))
            except Exception as e:
                logger.error(
                    f"Skipping safety filter result for "
                    f"{result.get('stock_code', '?')}: sanitize failed: {e}"
                )

        if not records:
            logger.warning("No valid safety filter results to save after sanitizing")
            return False

        db_conn = conn if conn is not None else self.engine

        # Happy path: 단일 트랜잭션 bulk insert
        try:
            with db_conn.begin() as transaction:
                transaction.execute(insert_sql, records)
            logger.info(f"Saved {len(records)} safety filter results")
            return True
        except SQLAlchemyError as e:
            logger.error(
                f"Bulk insert of safety filter results failed, "
                f"falling back to per-row insert: {e}"
            )

        # Fallback: 행 단위 삽입 (한 행이 나빠도 당일의 정상 행은 보존)
        saved = 0
        for record in records:
            try:
                with db_conn.begin() as transaction:
                    transaction.execute(insert_sql, record)
                saved += 1
            except SQLAlchemyError as e:
                logger.error(
                    f"Failed to insert safety filter result for "
                    f"{record['stock_code']}: {e}"
                )

        if saved > 0:
            logger.info(f"Saved {saved}/{len(records)} safety filter results (per-row fallback)")
            return True

        logger.error("Error saving safety filter results: all rows failed")
        return False

    def get_user_order_amount(self, user_id: int = 1) -> int:
        """
        Get user's configured order_amount from user_trade_config table.

        Args:
            user_id: User ID (default: 1 for MVP)

        Returns:
            order_amount in won (default: 1,000,000)
        """
        try:
            with self.session_factory() as session:
                query = text("""
                    SELECT order_amount
                    FROM user_trade_config
                    WHERE user_id = :user_id
                """)

                result = session.execute(query, {'user_id': user_id}).fetchone()

                if result:
                    return result[0]
                else:
                    logger.warning(f"No trade config found for user_id={user_id}, using default 1,000,000")
                    return 1_000_000  # Default 1M won

        except Exception as e:
            logger.error(f"Failed to fetch user order_amount: {e}")
            return 1_000_000  # Fallback to default

    def save_trade_execution_plan(self, user_id, execution_date, records) -> int:
        """
        유저별 매수/매도 실행 결과를 trade_execution_plan 에 기록 (멀티유저, 멱등).

        (user_id, execution_date) 키의 기존 행을 삭제 후 재삽입한다(재실행 중복 방지).

        Args:
            user_id: 사용자 id
            execution_date: 실행일(date)
            records: [{stock_code, stock_name, trade_type('BUY'|'SELL'), planned_quantity,
                       reference_price, estimated_amount, gemini_reason, gemini_rank,
                       safety_filter_passed, execution_status, execution_result(dict)}]

        Returns:
            int: 삽입한 행 수 (실패 시 0)
        """
        if not records:
            return 0

        insert_sql = text("""
            INSERT INTO trade_execution_plan
                (user_id, execution_date, stock_code, stock_name, trade_type,
                 planned_quantity, reference_price, estimated_amount, gemini_reason,
                 gemini_rank, safety_filter_passed, execution_status, order_no, executed_at,
                 execution_result)
            VALUES
                (:user_id, :execution_date, :stock_code, :stock_name, :trade_type,
                 :planned_quantity, :reference_price, :estimated_amount, :gemini_reason,
                 :gemini_rank, :safety_filter_passed, :execution_status, :order_no, now(),
                 CAST(:execution_result AS JSONB))
        """)

        params = []
        for r in records:
            params.append({
                'user_id': user_id,
                'execution_date': execution_date,
                'stock_code': r['stock_code'],
                'stock_name': (r.get('stock_name') or r['stock_code'])[:100],
                'trade_type': str(r['trade_type'])[:4],
                'planned_quantity': int(r.get('planned_quantity') or 0),
                'reference_price': r.get('reference_price'),
                'estimated_amount': r.get('estimated_amount'),
                'gemini_reason': r.get('gemini_reason') or '',
                'gemini_rank': int(r.get('gemini_rank') or 0),
                'safety_filter_passed': bool(r.get('safety_filter_passed', True)),
                'execution_status': str(r.get('execution_status') or 'PENDING')[:20],
                'order_no': (str(r.get('order_no'))[:30] if r.get('order_no') else None),
                'execution_result': json.dumps(r.get('execution_result') or {}, ensure_ascii=False),
            })

        session = self.session_factory()
        try:
            session.execute(
                text("DELETE FROM trade_execution_plan WHERE user_id = :u AND execution_date = :d"),
                {'u': user_id, 'd': execution_date}
            )
            session.execute(insert_sql, params)
            session.commit()
            logger.info(f"Saved {len(params)} trade_execution_plan rows for user {user_id}")
            return len(params)
        except SQLAlchemyError as e:
            session.rollback()
            logger.error(f"Error saving trade_execution_plan for user {user_id}: {e}")
            return 0
        finally:
            session.close()
