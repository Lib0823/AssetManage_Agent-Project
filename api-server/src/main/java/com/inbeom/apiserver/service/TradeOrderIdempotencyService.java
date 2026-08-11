package com.inbeom.apiserver.service;

import com.inbeom.apiserver.domain.StockMaster;
import com.inbeom.apiserver.domain.TradeHistory;
import com.inbeom.apiserver.domain.User;
import com.inbeom.apiserver.domain.UserKisAccount;
import com.inbeom.apiserver.dto.kafka.TradeOrderRequestMessage;
import com.inbeom.apiserver.exception.KisAccountNotFoundException;
import com.inbeom.apiserver.exception.UserNotFoundException;
import com.inbeom.apiserver.repository.StockMasterRepository;
import com.inbeom.apiserver.repository.TradeHistoryRepository;
import com.inbeom.apiserver.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Kafka 매매 주문의 멱등성 · {@code trade_history} 원장 기록.
 *
 * <h2>왜 "선(先) claim" 인가</h2>
 * KIS 주문을 낸 <b>뒤에만</b> 기록하면, 주문 직후 JVM 이 죽거나 커밋이 실패했을 때 아무 흔적이
 * 남지 않는다. 그러면 Kafka 가 같은 메시지를 재전달할 때 "처음 보는 주문"으로 판단해
 * <b>이미 체결된 주문을 한 번 더</b> 내게 된다. 그래서 KIS 를 호출하기 <b>전에</b> PENDING 행을
 * 먼저 INSERT 하고 즉시 커밋한다({@link Propagation#REQUIRES_NEW}).
 *
 * <p>결과적으로 행의 존재 자체가 "이 주문은 이미 KIS 로 나갔을 수 있다"는 뜻이 되고,
 * 상태와 무관하게(PENDING/EXECUTED/FAILED) 재실행을 막는다. 최종 방어선은 애플리케이션 조회가
 * 아니라 DB UNIQUE 제약({@code uk_trade_history_idempotency_key})이다 — 두 컨슈머가 동시에
 * 같은 키를 처리해도 하나만 INSERT 에 성공한다.
 *
 * <p>대가로 "PENDING 인 채 남는 행"이 생길 수 있다(claim 직후 프로세스 사망 등). 이것은 버그가
 * 아니라 <b>의도된 신호</b>다 — KIS 도달 여부가 불확실한 건이므로 사람이 대조해야 한다.
 * 컨슈머는 재전달 시 PENDING 행을 발견하면 DLQ 로 보낸다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeOrderIdempotencyService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_EXECUTED = "EXECUTED";
    public static final String STATUS_FAILED = "FAILED";

    private final TradeHistoryRepository tradeHistoryRepository;
    private final UserRepository userRepository;
    private final StockMasterRepository stockMasterRepository;

    /**
     * 멱등키 선점. 이 메서드는 KIS 를 호출하지 않으므로, 여기서 던진 예외는 재시도해도 안전하다.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException 동시에 다른 컨슈머가 먼저 선점한 경우
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClaimResult claim(TradeOrderRequestMessage request) {
        Optional<TradeHistory> existing = tradeHistoryRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            TradeHistory row = existing.get();
            log.info("Idempotent skip: key={} already recorded with status={}",
                    request.idempotencyKey(), row.getOrderStatus());
            return ClaimResult.duplicate(row.getId(), row.getOrderStatus());
        }

        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new UserNotFoundException(request.userId()));
        UserKisAccount account = user.getKisAccount();
        if (account == null) {
            throw new KisAccountNotFoundException("User has no KIS account: userId=" + request.userId());
        }

        TradeHistory claimed = TradeHistory.builder()
                .user(user)
                .idempotencyKey(request.idempotencyKey())
                .stockCode(request.stockCode())
                .stockName(resolveStockName(request.stockCode()))
                .orderType(request.isBuy() ? "buy" : "sell")
                .orderStatus(STATUS_PENDING)
                .quantity(request.quantity())
                .orderPrice(request.priceOrZero())
                .orderedAt(LocalDateTime.now())
                .build();

        // saveAndFlush: UNIQUE 위반을 이 트랜잭션 안에서 즉시 드러내, 커밋 시점까지 미뤄지지 않게 한다.
        TradeHistory saved = tradeHistoryRepository.saveAndFlush(claimed);
        log.info("Claimed trade order: key={}, tradeHistoryId={}", request.idempotencyKey(), saved.getId());
        return ClaimResult.claimed(saved.getId(), account.getId(), saved.getStockName());
    }

    /**
     * KIS 가 주문을 접수(rt_cd=0)한 경우. 시장가 주문은 접수 시점에 체결가/체결수량을 알 수 없으므로
     * 채우지 않는다 — 체결 상세는 KIS 체결내역 조회가 진실이다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAccepted(Long tradeHistoryId, String kisOrderNo) {
        tradeHistoryRepository.findById(tradeHistoryId).ifPresent(row -> {
            row.setOrderStatus(STATUS_EXECUTED);
            row.setOrderNumber(kisOrderNo);
            row.setExecutedAt(LocalDateTime.now());
            tradeHistoryRepository.save(row);
        });
    }

    /**
     * 선점 취소 — KIS 를 <b>호출하지 않은 채</b> 처리를 포기하고 재시도로 넘길 때만 쓴다
     * (현재는 자체 rate limit 거부가 유일한 경로).
     *
     * <p><b>왜 필요한가</b>: 선점 행을 남긴 채 재시도하면 다음 시도의 {@link #claim} 이 PENDING 행을
     * 발견하고 "KIS 도달 여부 불확실"로 판단해 DLQ 로 보낸다. 즉 취소하지 않으면 재시도가
     * 무의미해지고, rate limit 에 걸린 정상 주문이 그대로 유실된다.
     *
     * <p>KIS 를 이미 호출한 뒤에는 <b>절대 부르면 안 된다</b>. 행을 지우는 순간 "이 주문은 나갔을 수
     * 있다"는 유일한 흔적이 사라져 재시도가 중복 주문이 된다. 그래서 PENDING 인 행만 지운다 —
     * EXECUTED/FAILED 로 확정된 행은 남긴다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Long tradeHistoryId) {
        tradeHistoryRepository.findById(tradeHistoryId)
                .filter(row -> STATUS_PENDING.equals(row.getOrderStatus()))
                .ifPresent(row -> {
                    tradeHistoryRepository.delete(row);
                    log.info("Released unused trade order claim: tradeHistoryId={}", tradeHistoryId);
                });
    }

    /** 확정 실패. 재시도하지 않으므로 이 상태가 최종이다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long tradeHistoryId) {
        tradeHistoryRepository.findById(tradeHistoryId).ifPresent(row -> {
            row.setOrderStatus(STATUS_FAILED);
            tradeHistoryRepository.save(row);
        });
    }

    /**
     * 종목명 해석. {@code trade_history.stock_name} 이 NOT NULL 인데 메시지 계약에는 종목명이 없어서
     * {@code stock_master} 에서 찾고, 없으면 종목코드로 대체한다(주문을 막지는 않는다).
     */
    private String resolveStockName(String stockCode) {
        return stockMasterRepository.findFirstByStockCode(stockCode)
                .map(StockMaster::getStockName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(stockCode);
    }

    /**
     * 선점 결과.
     *
     * @param duplicate      이미 같은 멱등키로 기록이 있어 재실행하지 않아야 하는지
     * @param tradeHistoryId 해당 {@code trade_history} 행 id
     * @param existingStatus duplicate 인 경우 기존 상태(PENDING/EXECUTED/FAILED)
     * @param kisAccountId   신규 선점인 경우 주문에 사용할 KIS 계좌 id
     * @param stockName      신규 선점인 경우 해석된 종목명
     */
    public record ClaimResult(
            boolean duplicate,
            Long tradeHistoryId,
            String existingStatus,
            Long kisAccountId,
            String stockName
    ) {
        static ClaimResult duplicate(Long tradeHistoryId, String existingStatus) {
            return new ClaimResult(true, tradeHistoryId, existingStatus, null, null);
        }

        static ClaimResult claimed(Long tradeHistoryId, Long kisAccountId, String stockName) {
            return new ClaimResult(false, tradeHistoryId, null, kisAccountId, stockName);
        }

        /** 이전 시도의 KIS 도달 여부가 불확실한 상태 — 사람이 대조해야 한다. */
        public boolean isUnresolvedPending() {
            return duplicate && STATUS_PENDING.equals(existingStatus);
        }
    }
}
