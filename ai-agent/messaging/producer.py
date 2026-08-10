"""Kafka 프로듀서 래퍼 (aiokafka).

FastAPI lifespan 이 인스턴스 하나를 만들어 공유한다. 파이프라인 코드가 직접
AIOKafkaProducer 를 다루지 않도록 도메인 메서드(publish_trade_order /
publish_pipeline_run)를 노출한다.
"""
import asyncio
import json
import logging
from datetime import date
from typing import Dict, Optional, Tuple

from aiokafka import AIOKafkaProducer

from config import settings

from .messages import build_pipeline_run_message, build_trade_order_message
from .topics import (
    PIPELINE_RUN_PARTITIONS,
    TOPIC_PIPELINE_RUN_REQUESTED,
    TOPIC_TRADE_ORDER_REQUESTED,
)

logger = logging.getLogger(__name__)


def _serialize_value(value: Dict) -> bytes:
    return json.dumps(value, ensure_ascii=False).encode("utf-8")


def _serialize_key(key: Optional[str]) -> Optional[bytes]:
    return key.encode("utf-8") if key is not None else None


class KafkaMessagePublisher:
    """토픽 발행 담당. 시작/종료는 FastAPI lifespan 이 관리한다."""

    def __init__(
        self,
        bootstrap_servers: Optional[str] = None,
        client_id: Optional[str] = None,
        request_timeout_ms: int = 10_000,
    ):
        self.bootstrap_servers = bootstrap_servers or settings.kafka_bootstrap_servers
        self.client_id = client_id or settings.kafka_client_id
        self.request_timeout_ms = request_timeout_ms
        self._producer: Optional[AIOKafkaProducer] = None
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._start_lock: Optional[asyncio.Lock] = None

    @property
    def running(self) -> bool:
        return self._producer is not None

    def _new_producer(self) -> AIOKafkaProducer:
        return AIOKafkaProducer(
            bootstrap_servers=self.bootstrap_servers,
            client_id=self.client_id,
            value_serializer=_serialize_value,
            key_serializer=_serialize_key,
            acks="all",             # 리더+ISR 확인 후 성공 처리 (주문 유실 방지)
            enable_idempotence=True,  # 재시도로 인한 브로커 측 중복 기록 방지
            request_timeout_ms=self.request_timeout_ms,
        )

    async def start(self) -> None:
        """프로듀서 기동. 브로커 접속 실패 시 예외를 그대로 올린다."""
        if self._producer is not None:
            return
        producer = self._new_producer()
        await producer.start()
        self._producer = producer
        self._loop = asyncio.get_running_loop()
        logger.info(f"Kafka producer started (bootstrap={self.bootstrap_servers})")

    async def ensure_started(self) -> None:
        """미기동 상태면 기동한다 (동시 호출 안전)."""
        if self._producer is not None:
            return
        if self._start_lock is None:
            self._start_lock = asyncio.Lock()
        async with self._start_lock:
            if self._producer is None:
                await self.start()

    async def stop(self) -> None:
        """대기 중인 메시지를 flush 하고 종료한다."""
        producer, self._producer = self._producer, None
        self._loop = None
        if producer is None:
            return
        try:
            await producer.flush()
        except Exception as e:  # flush 실패해도 종료는 계속한다
            logger.warning(f"Kafka producer flush failed: {e}")
        finally:
            await producer.stop()
            logger.info("Kafka producer stopped")

    async def publish(self, topic: str, key: Optional[str], value: Dict) -> bool:
        """메시지 1건 발행 후 브로커 ack 까지 대기. 실패 시 False (예외를 삼킨다).

        Stage 6 는 한 주문의 발행 실패가 다른 주문을 막지 않아야 하므로 여기서 격리한다.
        """
        try:
            await self.ensure_started()
            await self._producer.send_and_wait(topic, key=key, value=value)
            logger.info(f"Published to {topic} (key={key})")
            return True
        except Exception as e:
            logger.error(f"Publish to {topic} failed (key={key}): {e}")
            return False

    def publish_sync(self, topic: str, key: Optional[str], value: Dict, timeout: float = 15.0) -> bool:
        """이벤트 루프 밖(APScheduler 워커 스레드 등)에서 발행한다.

        lifespan 이 만든 프로듀서가 살아 있으면 그 루프에 코루틴을 밀어 넣어 재사용하고,
        (테스트/단독 실행처럼) 루프가 없으면 일회용 프로듀서로 발행한다.
        """
        loop = self._loop
        if loop is not None and not loop.is_closed():
            future = asyncio.run_coroutine_threadsafe(self.publish(topic, key, value), loop)
            try:
                return future.result(timeout=timeout)
            except Exception as e:
                logger.error(f"publish_sync to {topic} failed (key={key}): {e}")
                return False
        return asyncio.run(self._publish_standalone(topic, key, value))

    async def _publish_standalone(self, topic: str, key: Optional[str], value: Dict) -> bool:
        """공유 프로듀서가 없을 때 쓰는 일회용 발행 경로."""
        producer = self._new_producer()
        try:
            await producer.start()
            await producer.send_and_wait(topic, key=key, value=value)
            logger.info(f"Published to {topic} (key={key}, standalone producer)")
            return True
        except Exception as e:
            logger.error(f"Standalone publish to {topic} failed (key={key}): {e}")
            return False
        finally:
            try:
                await producer.stop()
            except Exception:  # pragma: no cover - 종료 실패는 결과에 영향 없음
                logger.debug("standalone producer stop failed", exc_info=True)

    # ------------------------------------------------------------------
    # 도메인 메서드
    # ------------------------------------------------------------------
    async def publish_trade_order(
        self,
        user_id: int,
        stock_code: str,
        side: str,
        quantity: int,
        trade_date: date,
        price: float = 0,
    ) -> Tuple[bool, str, Dict]:
        """매매 주문 1건을 `trade.order.requested` 에 발행.

        Returns:
            (성공여부, 멱등키, 발행한 payload)
        """
        key, value = build_trade_order_message(
            user_id=user_id,
            stock_code=stock_code,
            side=side,
            quantity=quantity,
            trade_date=trade_date,
            price=price,
        )
        ok = await self.publish(TOPIC_TRADE_ORDER_REQUESTED, key, value)
        return ok, key, value

    async def publish_pipeline_run(self, trade_date: date, trigger_type: str) -> Tuple[bool, Dict]:
        """파이프라인 실행 요청을 `pipeline.run.requested` 에 발행 (async 경로)."""
        key, value = build_pipeline_run_message(trade_date, trigger_type)
        ok = await self.publish(TOPIC_PIPELINE_RUN_REQUESTED, key, value)
        return ok, value

    def publish_pipeline_run_sync(self, trade_date: date, trigger_type: str) -> Tuple[bool, Dict]:
        """파이프라인 실행 요청 발행 (APScheduler 스레드용 sync 경로)."""
        key, value = build_pipeline_run_message(trade_date, trigger_type)
        ok = self.publish_sync(TOPIC_PIPELINE_RUN_REQUESTED, key, value)
        return ok, value


async def ensure_topics(bootstrap_servers: Optional[str] = None) -> bool:
    """ai-agent 가 소유한 토픽을 파티션 수까지 명시해 생성한다 (best-effort).

    `pipeline.run.requested` 는 파티션 1개여야 동시 실행 방지가 성립한다. 브로커의
    auto-create 에 맡기면 `num.partitions` 기본값(보통 1, 운영에선 3 이상)에 좌우되므로
    기동 시 직접 만든다. 이미 있으면 그대로 두고, 실패해도 앱 기동은 막지 않는다.
    """
    from aiokafka.admin import AIOKafkaAdminClient, NewTopic

    servers = bootstrap_servers or settings.kafka_bootstrap_servers
    admin = AIOKafkaAdminClient(bootstrap_servers=servers, client_id=f"{settings.kafka_client_id}-admin")
    try:
        await admin.start()
        await admin.create_topics([
            NewTopic(
                name=TOPIC_PIPELINE_RUN_REQUESTED,
                num_partitions=PIPELINE_RUN_PARTITIONS,
                replication_factor=1,
            )
        ])
        logger.info(f"Created topic {TOPIC_PIPELINE_RUN_REQUESTED} (partitions={PIPELINE_RUN_PARTITIONS})")
        return True
    except Exception as e:
        # TopicAlreadyExistsError 포함 — 이미 있으면 정상 상황이다.
        logger.info(f"ensure_topics skipped ({type(e).__name__}: {e})")
        return False
    finally:
        try:
            await admin.close()
        except Exception:  # pragma: no cover
            logger.debug("admin client close failed", exc_info=True)
