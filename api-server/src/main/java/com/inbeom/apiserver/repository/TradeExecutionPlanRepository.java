package com.inbeom.apiserver.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * trade_execution_plan 조회 (멀티유저 봇 실행 기록).
 *
 * <p>거래내역 화면의 "AI 매매" 배지를 위해, 해당 사용자의 봇 실행 주문을
 * {@code "yyyy-MM-dd|ODNO"} 복합키 집합으로 제공한다. KIS 주문번호(ODNO)는
 * 당일 채번이라 날짜가 다르면 재사용될 수 있으므로, 거래내역과 매칭할 때
 * 반드시 주문일자를 함께 사용해야 다른 날 동일 ODNO 오매칭을 막을 수 있다.
 */
@Repository
public class TradeExecutionPlanRepository {

    private final JdbcTemplate jdbcTemplate;

    public TradeExecutionPlanRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 해당 사용자의 봇 실행 주문 키 집합. 각 키는 {@code "yyyy-MM-dd|ODNO"} 형식이며,
     * order_no 가 기록된 행만(= KIS 가 접수해 주문번호가 부여된 봇 주문) 포함한다.
     */
    public Set<String> findExecutedOrderKeys(Long userId) {
        String sql = """
            SELECT execution_date, order_no
            FROM trade_execution_plan
            WHERE user_id = ?
                AND order_no IS NOT NULL
            """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, userId);
        Set<String> keys = new HashSet<>();
        for (Map<String, Object> row : rows) {
            Object date = row.get("execution_date");
            Object orderNo = row.get("order_no");
            if (date != null && orderNo != null) {
                // java.sql.Date#toString() → "yyyy-MM-dd" (TradeHistoryResponse.orderDate 와 동일 포맷)
                keys.add(date.toString() + "|" + orderNo);
            }
        }
        return keys;
    }
}
