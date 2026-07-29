package com.inbeom.apiserver.dto.favorite;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

/**
 * 관심 종목 추가 요청 (관심종목 화면 - FavoritesView).
 * POST /favorites
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddFavoriteRequest {

    @NotBlank(message = "Stock code is required")
    private String stockCode;

    // 표시용 종목명(선택). 해외처럼 stock_master 에 없을 수 있어 프론트가 넘겨준다.
    private String stockName;

    // 해외 거래소 코드(NASD/NYSE/AMEX). 국내면 생략(null).
    private String exchangeCode;
}
