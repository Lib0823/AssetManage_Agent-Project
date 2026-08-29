package com.inbeom.apiserver.repository;

import com.inbeom.apiserver.domain.CoinTradeHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CoinTradeHistoryRepository extends JpaRepository<CoinTradeHistory, Long> {

    List<CoinTradeHistory> findByUserIdOrderByOrderedAtDesc(Long userId);

    /**
     * 멱등키 조회. 네트워크 타임아웃 후 같은 키로 재시도가 오면 업비트를 다시 호출하지 않고
     * 이미 접수된 주문을 그대로 돌려주기 위한 것이다 (DB UNIQUE 제약이 최종 방어선).
     */
    Optional<CoinTradeHistory> findByIdentifier(String identifier);

    void deleteByUserId(Long userId);
}
