package com.inbeom.apiserver.repository;

import com.inbeom.apiserver.domain.StockMaster;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockMasterRepository extends JpaRepository<StockMaster, Long> {

    /**
     * 종목코드로 단건 조회. Kafka 매매 주문 메시지에는 종목명이 없어
     * {@code trade_history.stock_name}(NOT NULL)을 채우기 위해 사용한다.
     * 국내/해외에 같은 코드가 있을 수 있으므로 First 로 제한한다.
     */
    Optional<StockMaster> findFirstByStockCode(String stockCode);

    /**
     * 종목 검색: 종목 코드 prefix 또는 종목명 부분일치(대소문자 무시), 최대 30건.
     */
    List<StockMaster> findTop30ByStockCodeStartingWithOrStockNameContainingIgnoreCase(
            String stockCode, String stockName);

    /**
     * 통화 분기 종목 검색: 종목 코드 prefix 또는 종목명 부분일치(대소문자 무시) AND 통화 일치.
     * 국내는 currency=KRW, 해외(US)는 currency=USD 로 호출한다. 호출 측에서 {@code Pageable}
     * 로 상위 30건을 제한한다(예: {@code PageRequest.of(0, 30)}).
     */
    @Query("SELECT s FROM StockMaster s "
            + "WHERE (UPPER(s.stockCode) LIKE UPPER(CONCAT(:keyword, '%')) "
            + "OR UPPER(s.stockName) LIKE UPPER(CONCAT('%', :keyword, '%'))) "
            + "AND s.currency = :currency")
    List<StockMaster> searchByKeywordAndCurrency(
            @Param("keyword") String keyword,
            @Param("currency") String currency,
            Pageable pageable);
}
