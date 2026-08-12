"""Kafka 컨슈머 공통 베이스 (aiokafka).

FastAPI lifespan 이 각 컨슈머를 백그라운드 태스크로 띄우고, 종료 시 cancel 한다.
그룹당 컨슈머 1개 + `async for` 순차 처리이므로 **한 메시지를 처리하는 동안 다음
메시지는 시작되지 않는다** — 파이프라인 동시 실행 방지가 여기에 기댄다.
"""
import asyncio
import json
import logging
from typing import Dict, Optional

from aiokafka import AIOKafkaConsumer

from config import settings

logger = logging.getLogger(__name__)


class KafkaConsumerWorker:
    """단일 토픽 순차 소비 워커. 서브클래스가 `handle()` 을 구현한다."""

    topic: str = ""
    group_id: str = ""

    # 브로커가 아직 안 떴을 때 기동을 다시 시도하는 간격 (지수 backoff, 상한 있음).
    START_RETRY_INITIAL_SECONDS = 1.0
    START_RETRY_MAX_SECONDS = 30.0

    def __init__(
        self,
        bootstrap_servers: Optional[str] = None,
        client_id: Optional[str] = None,
        auto_offset_reset: str = "earliest",
        max_poll_interval_ms: Optional[int] = None,
    ):
        self.bootstrap_servers = bootstrap_servers or settings.kafka_bootstrap_servers
        self.client_id = client_id or settings.kafka_client_id
        self.auto_offset_reset = auto_offset_reset
        self.max_poll_interval_ms = max_poll_interval_ms or settings.kafka_max_poll_interval_ms
        self._consumer: Optional[AIOKafkaConsumer] = None
        self.processed_count = 0
        self.failed_count = 0

    @property
    def running(self) -> bool:
        return self._consumer is not None

    async def start(self) -> None:
        """컨슈머 기동 (그룹 조인 포함). 실패 시 예외를 그대로 올린다."""
        if self._consumer is not None:
            return
        consumer = AIOKafkaConsumer(
            self.topic,
            bootstrap_servers=self.bootstrap_servers,
            group_id=self.group_id,
            client_id=f"{self.client_id}-{self.group_id}",
            # 처리 완료 후 수동 커밋 — 처리 전에 커밋되어 메시지가 증발하는 것을 막는다.
            enable_auto_commit=False,
            auto_offset_reset=self.auto_offset_reset,
            # 한 번에 1건씩만 가져와 처리 경계를 단순하게 유지한다.
            max_poll_records=1,
            # handle() 이 파이프라인 전체를 도는 동안 fetch 가 멈춰 있어도 그룹에서
            # 이탈하지 않게 한다 (기본 300초 → 파이프라인이 5분만 넘겨도 무한 재처리).
            max_poll_interval_ms=self.max_poll_interval_ms,
        )
        await consumer.start()
        self._consumer = consumer
        logger.info(f"Kafka consumer started: topic={self.topic} group={self.group_id}")

    async def stop(self) -> None:
        consumer, self._consumer = self._consumer, None
        if consumer is None:
            return
        await consumer.stop()
        logger.info(f"Kafka consumer stopped: topic={self.topic}")

    async def _start_with_retry(self) -> None:
        """기동에 성공할 때까지 backoff 재시도한다 (취소 시에만 중단).

        브로커보다 ai-agent 가 먼저 뜨는 것은 컨테이너 재기동마다 흔한 상황이다.
        여기서 한 번 실패하고 포기하면 컨슈머가 영영 없는 채로 프로듀서만 살아나
        "발행은 성공하는데 아무도 소비하지 않는" 상태가 된다.
        """
        delay = self.START_RETRY_INITIAL_SECONDS
        attempt = 0
        while True:
            try:
                await self.start()
                return
            except asyncio.CancelledError:
                raise
            except Exception as e:
                attempt += 1
                logger.error(
                    f"Kafka consumer start failed (topic={self.topic}, attempt={attempt}); "
                    f"retrying in {delay:.0f}s: {e}"
                )
                await asyncio.sleep(delay)
                delay = min(delay * 2, self.START_RETRY_MAX_SECONDS)

    async def run(self) -> None:
        """소비 루프. 취소(shutdown)될 때까지 순차 처리한다."""
        await self._start_with_retry()
        try:
            async for message in self._consumer:
                await self._process(message)
        except asyncio.CancelledError:
            logger.info(f"Consumer loop cancelled: topic={self.topic}")
            raise
        except Exception:
            logger.exception(f"Consumer loop crashed: topic={self.topic}")
            raise
        finally:
            await self.stop()

    async def _process(self, message) -> None:
        """메시지 1건 처리 후 오프셋 커밋.

        핸들러가 실패해도 커밋한다. ai-agent 쪽에는 DLQ 가 없어서, 커밋하지 않으면
        같은 poison 메시지를 무한 재처리하며 루프가 멈춘다. 대신 ERROR 로 남긴다.
        """
        try:
            value = self._decode(message.value)
            await self.handle(value)
            self.processed_count += 1
        except Exception as e:
            self.failed_count += 1
            logger.exception(
                f"Message handling failed (topic={self.topic} "
                f"partition={message.partition} offset={message.offset}): {e}"
            )
        finally:
            try:
                await self._consumer.commit()
            except Exception as e:
                logger.error(f"Offset commit failed (topic={self.topic}): {e}")

    @staticmethod
    def _decode(raw: Optional[bytes]) -> Dict:
        if raw is None:
            raise ValueError("empty message body")
        return json.loads(raw.decode("utf-8"))

    async def handle(self, value: Dict) -> None:  # pragma: no cover - 추상 메서드
        raise NotImplementedError
