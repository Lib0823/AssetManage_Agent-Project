"""Kafka 메시지 페이로드 조립/파싱.

api-server 와 공유하는 계약이므로 **필드명은 camelCase** 이다(Python 내부 규약인
snake_case 와 다르다). 이 모듈이 두 규약의 경계 역할을 한다.
"""
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from typing import Dict, Optional, Tuple

KST = timezone(timedelta(hours=9))

SIDE_BUY = "BUY"
SIDE_SELL = "SELL"


def now_kst_iso() -> str:
    """현재 시각을 KST ISO-8601 문자열로 반환 (예: 2026-08-09T08:55:00+09:00)."""
    return datetime.now(KST).isoformat(timespec="seconds")


def build_idempotency_key(user_id: int, stock_code: str, trade_date: date, side: str) -> str:
    """멱등키 조립: "{userId}:{stockCode}:{tradeDate}:{side}".

    api-server 는 이 키로 중복 주문을 걸러낸다. 같은 거래일에 파이프라인이 두 번
    실행되어도 같은 (유저, 종목, 방향) 주문은 한 번만 체결되도록 보장하는 핵심 값.
    """
    trade_date_str = trade_date.isoformat() if isinstance(trade_date, date) else str(trade_date)
    return f"{int(user_id)}:{stock_code}:{trade_date_str}:{str(side).upper()}"


def build_trade_order_message(
    user_id: int,
    stock_code: str,
    side: str,
    quantity: int,
    trade_date: date,
    price: float = 0,
    requested_at: Optional[str] = None,
) -> Tuple[str, Dict]:
    """`trade.order.requested` 메시지 (key, value) 조립.

    Args:
        price: 0 이면 시장가. 기존 REST 경로와 동일하게 Stage 6 는 항상 시장가로 낸다.

    Returns:
        (key, value) — key 는 멱등키와 동일한 문자열이므로 같은 주문은 항상 같은
        파티션으로 가고, 파티션 내 순서가 보장된다.
    """
    key = build_idempotency_key(user_id, stock_code, trade_date, side)
    value = {
        "idempotencyKey": key,
        "userId": int(user_id),
        "stockCode": stock_code,
        "side": str(side).upper(),
        "quantity": int(quantity),
        "price": price or 0,
        "tradeDate": trade_date.isoformat() if isinstance(trade_date, date) else str(trade_date),
        "requestedAt": requested_at or now_kst_iso(),
    }
    return key, value


def build_pipeline_run_message(
    trade_date: date,
    trigger_type: str,
    requested_at: Optional[str] = None,
) -> Tuple[str, Dict]:
    """`pipeline.run.requested` 메시지 (key, value) 조립. key 는 거래일(yyyy-MM-dd)."""
    trade_date_str = trade_date.isoformat() if isinstance(trade_date, date) else str(trade_date)
    value = {
        "tradeDate": trade_date_str,
        "triggerType": str(trigger_type).upper(),
        "requestedAt": requested_at or now_kst_iso(),
    }
    return trade_date_str, value


@dataclass(frozen=True)
class PipelineRunRequest:
    """`pipeline.run.requested` 메시지 파싱 결과."""

    trade_date: date
    trigger_type: str
    requested_at: Optional[str]

    @classmethod
    def from_message(cls, value: Dict) -> "PipelineRunRequest":
        if not isinstance(value, dict):
            raise ValueError(f"pipeline.run.requested payload must be an object: {value!r}")
        raw_date = value.get("tradeDate")
        if not raw_date:
            raise ValueError("pipeline.run.requested is missing tradeDate")
        try:
            trade_date = date.fromisoformat(str(raw_date))
        except ValueError as e:
            raise ValueError(f"invalid tradeDate: {raw_date!r}") from e
        return cls(
            trade_date=trade_date,
            trigger_type=str(value.get("triggerType") or "UNKNOWN").upper(),
            requested_at=value.get("requestedAt"),
        )


@dataclass(frozen=True)
class TradeOrderResult:
    """`trade.order.result` 메시지 파싱 결과 (DB 갱신에 필요한 형태로 정규화)."""

    idempotency_key: str
    user_id: int
    stock_code: str
    side: str
    trade_date: date
    status: str
    kis_order_no: Optional[str]
    error_message: Optional[str]
    processed_at: Optional[str]

    @classmethod
    def from_message(cls, value: Dict) -> "TradeOrderResult":
        """결과 메시지를 파싱한다.

        `tradeDate` 는 계약에 없으므로 멱등키에서 되꺼낸다
        ("{userId}:{stockCode}:{tradeDate}:{side}"). userId/stockCode/side 는 본문에
        있으면 본문을, 없으면 멱등키를 쓴다.
        """
        if not isinstance(value, dict):
            raise ValueError(f"trade.order.result payload must be an object: {value!r}")

        key = value.get("idempotencyKey")
        if not key:
            raise ValueError("trade.order.result is missing idempotencyKey")

        parts = str(key).split(":")
        if len(parts) != 4:
            raise ValueError(f"malformed idempotencyKey: {key!r}")
        key_user, key_code, key_date, key_side = parts

        try:
            trade_date = date.fromisoformat(key_date)
        except ValueError as e:
            raise ValueError(f"malformed tradeDate in idempotencyKey: {key!r}") from e

        user_id_raw = value.get("userId", key_user)
        try:
            user_id = int(user_id_raw)
        except (TypeError, ValueError) as e:
            raise ValueError(f"invalid userId: {user_id_raw!r}") from e

        status = str(value.get("status") or "").upper()
        if not status:
            raise ValueError(f"trade.order.result is missing status (key={key})")

        return cls(
            idempotency_key=str(key),
            user_id=user_id,
            stock_code=str(value.get("stockCode") or key_code),
            side=str(value.get("side") or key_side).upper(),
            trade_date=trade_date,
            status=status,
            kis_order_no=value.get("kisOrderNo"),
            error_message=value.get("errorMessage"),
            processed_at=value.get("processedAt"),
        )
