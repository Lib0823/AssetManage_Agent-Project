"""FastAPI application for AI Agent pipeline."""
import asyncio
import logging
import sys
from datetime import date
from typing import Optional, List, Dict, Any
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from pipeline import PipelineScheduler, PipelineOrchestrator
from database import DatabaseRepository
from messaging import (
    KafkaMessagePublisher,
    PipelineRunConsumer,
    TradeResultConsumer,
    TRIGGER_MANUAL,
    ensure_topics,
)
from config import settings

# Configure logging
logging.basicConfig(
    level=getattr(logging, settings.log_level),
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler(settings.log_file),
        logging.StreamHandler(sys.stdout)
    ]
)

logger = logging.getLogger(__name__)


# Global instances
scheduler: Optional[PipelineScheduler] = None
orchestrator: Optional[PipelineOrchestrator] = None
publisher: Optional[KafkaMessagePublisher] = None
pipeline_run_consumer: Optional[PipelineRunConsumer] = None
trade_result_consumer: Optional[TradeResultConsumer] = None
consumer_tasks: List[asyncio.Task] = []
consumer_workers: Dict[str, Any] = {}  # task name → worker (상태 조회용)

PIPELINE_CONSUMER_NAME = "pipeline-run-consumer"
TRADE_RESULT_CONSUMER_NAME = "trade-result-consumer"


def _log_consumer_task_result(task: asyncio.Task) -> None:
    """컨슈머 백그라운드 태스크가 끝났을 때 원인을 남긴다.

    태스크 참조만 리스트에 담아두고 예외를 회수하지 않으면, 컨슈머가 죽어도 아무
    로그가 남지 않는다. 컨슈머는 정상 운영 중엔 끝나지 않으므로 취소가 아닌 종료는
    전부 이상 상황이다.
    """
    if task.cancelled():
        logger.info(f"Kafka consumer task cancelled: {task.get_name()}")
        return
    exc = task.exception()
    if exc is not None:
        logger.error(f"Kafka consumer task died: {task.get_name()}: {exc}", exc_info=exc)
    else:
        logger.error(f"Kafka consumer task exited unexpectedly: {task.get_name()}")


def _spawn_consumer(worker, name: str) -> asyncio.Task:
    """컨슈머를 백그라운드 태스크로 띄우고 사망 감지 콜백을 붙인다."""
    task = asyncio.create_task(worker.run(), name=name)
    task.add_done_callback(_log_consumer_task_result)
    consumer_workers[name] = worker
    return task


def _consumer_status() -> Dict[str, Any]:
    """각 Kafka 컨슈머의 생사 상태.

    프로듀서 상태(`kafka_connected`)만 노출하면 컨슈머가 죽어도 정상으로 보인다.

    Returns:
        Dict[str, Any]: {consumer_name: {alive, connected, processed, failed, error}}
    """
    tasks = {t.get_name(): t for t in consumer_tasks}
    status: Dict[str, Any] = {}
    for name, worker in consumer_workers.items():
        task = tasks.get(name)
        error = None
        if task is not None and task.done() and not task.cancelled():
            exc = task.exception()
            error = f"{type(exc).__name__}: {exc}" if exc else "exited without error"
        status[name] = {
            "alive": task is not None and not task.done(),
            "connected": bool(getattr(worker, "running", False)),
            "processed": getattr(worker, "processed_count", 0),
            "failed": getattr(worker, "failed_count", 0),
            "error": error,
        }
    return status


@asynccontextmanager
async def lifespan(app: FastAPI):
    """FastAPI lifespan event handler for startup/shutdown.

    기동 순서:
      1. Kafka 토픽 보장(pipeline.run.requested = 파티션 1개) + 프로듀서 기동
      2. 오케스트레이터 생성 (프로듀서 공유 → Stage 6 가 이걸로 주문 발행)
      3. 컨슈머 2개를 백그라운드 태스크로 기동
         - pipeline.run.requested → 파이프라인 실행 (동시 실행 방지의 핵심)
         - trade.order.result     → trade_execution_plan 상태 확정
      4. APScheduler 기동 (실행이 아니라 '발행'만 한다)

    Kafka 접속 실패 시 앱 기동 자체는 막지 않는다(조회 API 는 계속 서비스).
    **프로듀서 기동 실패는 컨슈머 기동을 막지 않는다** — 컨슈머는 프로듀서에
    의존하지 않고, 각자 backoff 로 브로커에 다시 붙는다. 예전처럼 프로듀서 실패 시
    컨슈머를 아예 만들지 않으면, 브로커가 나중에 복구됐을 때 스케줄 발행만
    "성공"하고 소비자는 영원히 없는 상태가 된다.
    """
    global scheduler, orchestrator, publisher, pipeline_run_consumer, trade_result_consumer, consumer_tasks

    # Startup
    logger.info("Starting AI Agent application...")

    publisher = KafkaMessagePublisher()
    try:
        await ensure_topics()
        await publisher.start()
    except Exception as e:
        logger.error(
            f"Kafka producer startup failed — triggers retry the connection on demand: {e}"
        )

    # Initialize orchestrator (reused by the pipeline consumer; OAuth token cached)
    orchestrator = PipelineOrchestrator(publisher=publisher)
    logger.info("PipelineOrchestrator initialized (OAuth token will be cached)")

    consumer_workers.clear()
    pipeline_run_consumer = PipelineRunConsumer(orchestrator=orchestrator)
    trade_result_consumer = TradeResultConsumer(db_repo=DatabaseRepository())
    consumer_tasks = [
        _spawn_consumer(pipeline_run_consumer, PIPELINE_CONSUMER_NAME),
        _spawn_consumer(trade_result_consumer, TRADE_RESULT_CONSUMER_NAME),
    ]
    logger.info("Kafka consumers started (pipeline.run.requested, trade.order.result)")

    # Initialize scheduler (publishes run requests; never runs the pipeline itself)
    scheduler = PipelineScheduler(publisher=publisher)
    scheduler.start()
    logger.info("Application started successfully")

    yield

    # Shutdown
    logger.info("Shutting down AI Agent application...")
    if scheduler:
        scheduler.stop()

    for task in consumer_tasks:
        task.cancel()
    if consumer_tasks:
        await asyncio.gather(*consumer_tasks, return_exceptions=True)
        logger.info("Kafka consumers stopped")

    if publisher:
        await publisher.stop()

    logger.info("Application shut down successfully")


app = FastAPI(
    title="AI Agent - Stock Analysis Pipeline",
    description="Automated stock filtering and analysis pipeline",
    version="1.0.0",
    lifespan=lifespan
)


# Request/Response models
class ManualTriggerRequest(BaseModel):
    """Request model for manual pipeline trigger."""
    trade_date: Optional[str] = None  # Format: YYYY-MM-DD
    holdings: Optional[List[str]] = None


class PipelineStatusResponse(BaseModel):
    """Response model for pipeline status."""
    scheduler_running: bool
    next_run_time: Optional[str]
    latest_execution_date: Optional[str]
    kafka_connected: bool = False  # 프로듀서 상태 (컨슈머 상태는 consumers 참조)
    consumers: Dict[str, Any] = {}  # 컨슈머별 생사/처리 카운트
    running_trade_date: Optional[str] = None  # 현재 컨슈머가 실행 중인 거래일 (없으면 None)
    last_run: Optional[Dict[str, Any]] = None  # 마지막으로 끝난 실행 요약


@app.get("/")
async def root():
    """Root endpoint."""
    return {
        "service": "AI Agent - Stock Analysis Pipeline",
        "version": "1.0.0",
        "status": "running"
    }


@app.get("/health")
async def health_check():
    """Health check endpoint."""
    return {"status": "healthy"}


@app.post("/api/pipeline/trigger", status_code=202)
async def trigger_pipeline_manual(request: ManualTriggerRequest):
    """
    Queue a complete pipeline execution (비동기).

    `pipeline.run.requested` 에 실행 요청만 발행하고 즉시 202 를 반환한다. 실제 실행은
    단일 컨슈머가 순차 처리하므로, 스케줄 트리거와 겹쳐도 동시에 실행되지 않는다.
    (예전에는 파이프라인이 끝날 때까지 응답을 붙들고 있었다 — 수 분 단위)

    Args:
        request: Optional trade_date (holdings 는 무시 — Stage 0-1 에서 api-server 로
            부터 유저별 보유종목을 직접 받는다)

    Returns:
        202 Accepted + 큐잉된 메시지
    """
    global publisher

    if publisher is None:
        raise HTTPException(
            status_code=503,
            detail="Kafka publisher unavailable; cannot queue pipeline run"
        )

    if not publisher.running:
        # 기동 시 브로커가 안 떠 있었을 수 있다. 여기서 다시 붙여보고, 그래도 안 되면 503.
        try:
            await publisher.ensure_started()
        except Exception as e:
            raise HTTPException(
                status_code=503,
                detail=f"Kafka publisher unavailable; cannot queue pipeline run: {e}"
            )

    logger.info(f"Manual trigger request received: {request.dict()}")

    # Parse trade date if provided
    trade_date_obj = date.today()
    if request.trade_date:
        try:
            trade_date_obj = date.fromisoformat(request.trade_date)
        except ValueError:
            raise HTTPException(
                status_code=400,
                detail=f"Invalid date format: {request.trade_date}. Use YYYY-MM-DD"
            )

    queued, message = await publisher.publish_pipeline_run(
        trade_date=trade_date_obj, trigger_type=TRIGGER_MANUAL
    )

    if not queued:
        raise HTTPException(status_code=503, detail="Failed to queue pipeline run")

    body = {
        "message": "Pipeline run queued",
        "status": "QUEUED",
        "request": message,
    }
    if request.holdings:
        # 계약상 전달할 자리가 없고, 보유종목은 파이프라인이 api-server 에서 직접 받는다.
        logger.warning(f"'holdings' is ignored by the queued pipeline run: {request.holdings}")
        body["ignored"] = {"holdings": request.holdings}

    return JSONResponse(status_code=202, content=body)


@app.get("/api/pipeline/status", response_model=PipelineStatusResponse)
async def get_pipeline_status():
    """
    Get current pipeline status.

    Returns:
        Scheduler status, Kafka 연결 상태, 진행 중/직전 실행 정보, latest execution date
    """
    global scheduler, publisher, pipeline_run_consumer

    if scheduler is None:
        raise HTTPException(status_code=500, detail="Scheduler not initialized")

    # Get next run time
    next_run = scheduler.get_next_run_time()
    next_run_str = next_run.isoformat() if next_run else None

    # Get latest execution date from database
    db_repo = DatabaseRepository()
    latest_date = db_repo.get_latest_filter_date()
    latest_date_str = latest_date.isoformat() if latest_date else None

    return PipelineStatusResponse(
        scheduler_running=scheduler.scheduler.running,
        next_run_time=next_run_str,
        latest_execution_date=latest_date_str,
        kafka_connected=bool(publisher and publisher.running),
        consumers=_consumer_status(),
        running_trade_date=(pipeline_run_consumer.current_run if pipeline_run_consumer else None),
        last_run=(pipeline_run_consumer.last_run if pipeline_run_consumer else None),
    )


@app.get("/api/pipeline/results/{trade_date}")
async def get_pipeline_results(trade_date: str):
    """
    Get pipeline results for a specific trade date.

    Args:
        trade_date: Trade date in YYYY-MM-DD format

    Returns:
        Filter scores and selected stocks
    """
    try:
        trade_date_obj = date.fromisoformat(trade_date)
    except ValueError:
        raise HTTPException(
            status_code=400,
            detail=f"Invalid date format: {trade_date}. Use YYYY-MM-DD"
        )

    db_repo = DatabaseRepository()
    df = db_repo.get_filter_scores(trade_date_obj)

    if df is None or df.empty:
        raise HTTPException(
            status_code=404,
            detail=f"No results found for {trade_date}"
        )

    # Convert DataFrame to JSON-serializable format
    results = df.to_dict('records')

    return {
        "trade_date": trade_date,
        "total_stocks": len(results),
        "selected_stocks": len(df[df['is_selected'] == True]),
        "results": results
    }


@app.get("/api/pipeline/selected/{trade_date}")
async def get_selected_stocks(trade_date: str):
    """
    Get list of selected stock codes for a specific date.

    Args:
        trade_date: Trade date in YYYY-MM-DD format

    Returns:
        List of selected stock codes
    """
    try:
        trade_date_obj = date.fromisoformat(trade_date)
    except ValueError:
        raise HTTPException(
            status_code=400,
            detail=f"Invalid date format: {trade_date}. Use YYYY-MM-DD"
        )

    db_repo = DatabaseRepository()
    selected_codes = db_repo.get_selected_stocks(trade_date_obj)

    if not selected_codes:
        raise HTTPException(
            status_code=404,
            detail=f"No selected stocks found for {trade_date}"
        )

    return {
        "trade_date": trade_date,
        "selected_stocks": len(selected_codes),
        "stock_codes": selected_codes
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000, log_config=None)
