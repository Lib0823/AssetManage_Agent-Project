package com.inbeom.apiserver.dto.coin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 원화마켓 목록 + 안내 문구.
 *
 * <p>목록을 그대로 내보내지 않고 감싸는 이유는 <b>빈 목록의 뜻이 두 가지</b>이기 때문이다 —
 * "업비트가 응답했는데 결과가 없다"와 "업비트 호출이 실패했다". 조회 경로는 예외를 던지지 않고
 * degrade 하므로, 구분이 없으면 화면이 장애를 "코인이 하나도 없음"으로 그린다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoinMarketListResponse {

    /** 항상 non-null. 실패 시 빈 목록. */
    private List<CoinMarketResponse> markets;

    /** 정상일 때 null. 실패·미연동 시 사용자에게 보여줄 안내 문구. */
    private String notice;
}
