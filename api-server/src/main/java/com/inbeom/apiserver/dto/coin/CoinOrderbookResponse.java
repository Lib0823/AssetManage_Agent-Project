package com.inbeom.apiserver.dto.coin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 코인 호가.
 *
 * <p><b>주식 호가와 구조가 다르다.</b> 매도/매수가 각각의 배열이 아니라 {@code units} 한 배열에
 * <b>쌍으로</b> 들어 있고, index 0 이 최우선 호가다. 15단계까지 온다.
 *
 * <p>호가 잔량에는 {@code 9.128e-05} 같은 아주 작은 값이 실제로 온다. {@code BigDecimal} 로
 * 받아 두었으므로 화면에서 {@code toPlainString()} 없이 출력하면 지수 표기가 그대로 노출된다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoinOrderbookResponse {

    private String market;

    /** epoch millis. */
    private Long timestamp;

    private BigDecimal totalAskSize;
    private BigDecimal totalBidSize;

    /** 항상 non-null. 실패 시 빈 목록. */
    private List<CoinOrderbookUnit> units;

    /** 정상일 때 null. 실패 시 안내 문구. */
    private String notice;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CoinOrderbookUnit {
        private BigDecimal askPrice;
        private BigDecimal askSize;
        private BigDecimal bidPrice;
        private BigDecimal bidSize;
    }
}
