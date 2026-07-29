package com.inbeom.apiserver.service;

import com.inbeom.apiserver.domain.User;
import com.inbeom.apiserver.domain.UserKisAccount;
import com.inbeom.apiserver.dto.internal.AutoTradingUserResponse;
import com.inbeom.apiserver.dto.internal.InternalTradeRequest;
import com.inbeom.apiserver.dto.trade.BalanceSummaryResponse;
import com.inbeom.apiserver.exception.KisAccountNotFoundException;
import com.inbeom.apiserver.exception.UserNotFoundException;
import com.inbeom.apiserver.repository.UserRepository;
import com.inbeom.apiserver.repository.UserTradeConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 내부(서비스-투-서비스) 비즈니스 로직.
 *
 * <p>ai-agent 멀티유저 파이프라인을 위한 위임 계층:
 * (1) 자동매매 활성 사용자 목록, (2) 사용자별 보유종목, (3) 사용자 명의 매수/매도.
 * 보유종목/매매는 사용자별 KIS 키를 다루는 기존 {@link TradingService} 에 그대로 위임하며,
 * 키 자체는 ai-agent 로 노출하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InternalService {

    private final UserTradeConfigRepository tradeConfigRepository;
    private final UserRepository userRepository;
    private final TradingService tradingService;

    /**
     * 자동매매가 켜진(is_active=true) 사용자들의 실행 컨텍스트 목록.
     * KIS 계좌가 연결되지 않은 사용자는 매매 대상이 될 수 없으므로 제외(경고 로그)한다.
     */
    @Transactional(readOnly = true)
    public List<AutoTradingUserResponse> getActiveAutoTradingUsers() {
        return tradeConfigRepository.findByIsActiveTrue().stream()
                .map(cfg -> {
                    User user = cfg.getUser();
                    if (user == null) {
                        return null;
                    }
                    UserKisAccount account = user.getKisAccount();
                    if (account == null) {
                        log.warn("Active auto-trading user {} has no KIS account; skipping", user.getId());
                        return null;
                    }
                    return AutoTradingUserResponse.builder()
                            .userId(user.getId())
                            .kisAccountId(account.getId())
                            .orderAmount(cfg.getOrderAmount())
                            .maxHoldings(cfg.getMaxHoldings())
                            .orderType(cfg.getOrderType())
                            .build();
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 특정 사용자의 보유종목 조회(해당 사용자 KIS 키로 KIS 조회). TradingService 위임.
     */
    public BalanceSummaryResponse getHoldings(Long userId) {
        return tradingService.getHoldings(userId);
    }

    /**
     * 특정 사용자 명의 매수 주문 실행. userId → kisAccountId 해석 후 TradingService 위임.
     */
    public Map<String, Object> executeBuy(Long userId, InternalTradeRequest request) {
        return tradingService.executeBuy(
                userId,
                resolveKisAccountId(userId),
                request.getStockCode(),
                request.getStockName(),
                request.getQuantity(),
                priceOrZero(request.getPrice()));
    }

    /**
     * 특정 사용자 명의 매도 주문 실행. userId → kisAccountId 해석 후 TradingService 위임.
     */
    public Map<String, Object> executeSell(Long userId, InternalTradeRequest request) {
        return tradingService.executeSell(
                userId,
                resolveKisAccountId(userId),
                request.getStockCode(),
                request.getStockName(),
                request.getQuantity(),
                priceOrZero(request.getPrice()));
    }

    private BigDecimal priceOrZero(BigDecimal price) {
        return price != null ? price : BigDecimal.ZERO;
    }

    /**
     * userId 로 연결된 KIS 계좌 id 해석. 사용자/계좌가 없으면 명확한 예외.
     */
    private Long resolveKisAccountId(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        UserKisAccount account = user.getKisAccount();
        if (account == null) {
            throw new KisAccountNotFoundException("User has no KIS account: userId=" + userId);
        }
        return account.getId();
    }
}
