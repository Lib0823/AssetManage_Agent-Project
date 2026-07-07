package com.inbeom.apiserver.service;

import com.inbeom.apiserver.domain.StockMaster;
import com.inbeom.apiserver.domain.UserFavorite;
import com.inbeom.apiserver.dto.favorite.FavoriteResponse;
import com.inbeom.apiserver.repository.StockMasterRepository;
import com.inbeom.apiserver.repository.UserFavoriteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 관심 종목 서비스 (관심종목 화면 - FavoritesView).
 *
 * <p>관심 종목 목록/추가/삭제를 제공한다. 종목명은 추가 시 stock_master 카탈로그에서 해석해
 * 표시용으로 비정규화 저장하며, 현재가는 공용 {@link KisQuoteClient} 로 KIS 실전 시세를 조회한다.
 * 시세 비활성/실패 시 가격은 null, notice 로 사유를 노출하며 절대 예외를 전파하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final UserFavoriteRepository userFavoriteRepository;
    private final StockMasterRepository stockMasterRepository;
    private final KisQuoteClient kisQuoteClient;

    /**
     * 사용자의 관심 종목 목록 + 종목별 현재가/등락률.
     * 시세 비활성/실패 시 가격 null + notice.
     */
    @Transactional(readOnly = true)
    public List<FavoriteResponse> getFavorites(Long userId) {
        return userFavoriteRepository.findByUserId(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 관심 종목 추가. stock_master 에서 종목명을 해석하고, 이미 등록된 종목은
     * unique(user_id, stock_code) 충돌 없이 멱등 처리(기존 항목 반환).
     */
    @Transactional
    public FavoriteResponse addFavorite(Long userId, String stockCode, String stockName, String exchangeCode) {
        String code = stockCode != null ? stockCode.trim() : null;
        if (code == null || code.isBlank()) {
            return null;
        }
        String exchange = (exchangeCode != null && !exchangeCode.isBlank()) ? exchangeCode.trim() : null;
        String providedName = (stockName != null && !stockName.isBlank()) ? stockName.trim() : null;

        UserFavorite favorite = userFavoriteRepository
                .findByUserIdAndStockCode(userId, code)
                .orElseGet(() -> {
                    // 종목명: 프론트가 넘긴 값 우선(해외처럼 stock_master 에 없는 경우), 없으면 국내 카탈로그 해석.
                    String resolvedName = providedName != null ? providedName
                            : stockMasterRepository
                                    .findTop30ByStockCodeStartingWithOrStockNameContainingIgnoreCase(code, code)
                                    .stream()
                                    .filter(m -> code.equals(m.getStockCode()))
                                    .map(StockMaster::getStockName)
                                    .findFirst()
                                    .orElse(code);
                    UserFavorite created = UserFavorite.builder()
                            .userId(userId)
                            .stockCode(code)
                            .stockName(resolvedName)
                            .exchangeCode(exchange)
                            .build();
                    return userFavoriteRepository.save(created);
                });

        return toResponse(favorite);
    }

    /**
     * 관심 종목 삭제. 미등록 종목이어도 예외 없이 멱등 처리.
     */
    @Transactional
    public void removeFavorite(Long userId, String stockCode) {
        String code = stockCode != null ? stockCode.trim() : null;
        if (code == null || code.isBlank()) {
            return;
        }
        userFavoriteRepository.deleteByUserIdAndStockCode(userId, code);
    }

    /**
     * 관심 종목 엔티티 → 응답 DTO. 현재가/등락률을 공용 quote 헬퍼로 조회한다.
     */
    private FavoriteResponse toResponse(UserFavorite favorite) {
        String exchange = favorite.getExchangeCode();

        // 해외 종목: 국내 시세 API(fetchCurrentPrice)로는 조회 불가 → 코드/이름/거래소만 반환하고
        // 시세는 프론트가 해외 시세 API 로 lazy 조회한다(원화 환산 표시).
        if (exchange != null && !exchange.isBlank()) {
            return FavoriteResponse.builder()
                    .stockCode(favorite.getStockCode())
                    .stockName(favorite.getStockName())
                    .exchangeCode(exchange)
                    .build();
        }

        Map<String, Object> price = kisQuoteClient.fetchCurrentPrice(favorite.getStockCode());
        if (price == null) {
            return FavoriteResponse.builder()
                    .stockCode(favorite.getStockCode())
                    .stockName(favorite.getStockName())
                    .notice(kisQuoteClient.unavailableNotice())
                    .build();
        }
        return FavoriteResponse.builder()
                .stockCode(favorite.getStockCode())
                .stockName(favorite.getStockName())
                .currentPrice(parseLong(price.get("stck_prpr")))
                .changeRate(parseBigDecimal(price.get("prdy_ctrt")))
                .build();
    }

    private String asString(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private Long parseLong(Object o) {
        String s = asString(o);
        if (s == null) {
            return null;
        }
        try {
            String cleaned = s.replace(",", "");
            if (cleaned.contains(".")) {
                return (long) Double.parseDouble(cleaned);
            }
            return Long.parseLong(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parseBigDecimal(Object o) {
        String s = asString(o);
        if (s == null) {
            return null;
        }
        try {
            return new BigDecimal(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
