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
     *
     * <p><b>반드시 {@code userId} 로 좁혀서 조회한다.</b> {@code idempotencyKey} 는 클라이언트가 값을
     * 완전히 지정할 수 있는 문자열이라, 전역으로 조회하면 아무 사용자나 {@code "1"} 같은 값을 보내
     * <b>남의 주문 내역(주문번호·종목·수량·단가·체결·수수료)을 통째로 받아볼 수 있다.</b> 게다가 그
     * 요청은 "중복"으로 처리돼 <b>업비트로 나가지 않으면서 화면에는 성공으로 보인다</b> — 주문을 냈다고
     * 믿었는데 존재하지 않는 상태가 된다. 멱등은 <b>같은 사용자의 재시도</b>를 막기 위한 것이지
     * 사용자 사이에 적용될 개념이 아니다.
     */
    Optional<CoinTradeHistory> findByUserIdAndIdentifier(Long userId, String identifier);

    void deleteByUserId(Long userId);
}
