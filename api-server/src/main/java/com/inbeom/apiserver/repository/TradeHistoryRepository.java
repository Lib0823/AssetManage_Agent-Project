package com.inbeom.apiserver.repository;

import com.inbeom.apiserver.domain.TradeHistory;
import com.inbeom.apiserver.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TradeHistoryRepository extends JpaRepository<TradeHistory, Long> {

    List<TradeHistory> findByUserOrderByExecutedAtDesc(User user);

    Page<TradeHistory> findByUserOrderByExecutedAtDesc(User user, Pageable pageable);

    List<TradeHistory> findByUserAndStockCodeOrderByExecutedAtDesc(User user, String stockCode);

    List<TradeHistory> findByUserAndExecutedAtBetweenOrderByExecutedAtDesc(
        User user,
        LocalDateTime startDate,
        LocalDateTime endDate
    );

    @Query("SELECT DISTINCT th.stockCode FROM TradeHistory th WHERE th.user = :user AND th.orderType = 'buy'")
    List<String> findCurrentHoldingStockCodes(@Param("user") User user);

    List<TradeHistory> findByUserIdOrderByOrderedAtDesc(Long userId);

    /**
     * Kafka 멱등키로 기존 처리 이력 조회. 존재하면(PENDING/EXECUTED/FAILED 무관) 이미 KIS 로
     * 주문이 나갔을 수 있다는 뜻이므로 재실행하지 않는다.
     */
    Optional<TradeHistory> findByIdempotencyKey(String idempotencyKey);

    void deleteByUserId(Long userId);
}
