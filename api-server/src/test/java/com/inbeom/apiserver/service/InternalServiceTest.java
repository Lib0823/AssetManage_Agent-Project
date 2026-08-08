package com.inbeom.apiserver.service;

import com.inbeom.apiserver.domain.User;
import com.inbeom.apiserver.domain.UserKisAccount;
import com.inbeom.apiserver.domain.UserTradeConfig;
import com.inbeom.apiserver.dto.internal.AutoTradingUserResponse;
import com.inbeom.apiserver.dto.internal.InternalTradeRequest;
import com.inbeom.apiserver.dto.trade.BalanceSummaryResponse;
import com.inbeom.apiserver.exception.BusinessException;
import com.inbeom.apiserver.exception.ErrorCode;
import com.inbeom.apiserver.repository.UserRepository;
import com.inbeom.apiserver.repository.UserTradeConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InternalService 단위 테스트")
class InternalServiceTest {

    @Mock
    private UserTradeConfigRepository tradeConfigRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TradingService tradingService;

    @InjectMocks
    private InternalService internalService;

    private Long userId;
    private Long kisAccountId;

    @BeforeEach
    void setUp() {
        userId = 1L;
        kisAccountId = 100L;
    }

    /**
     * KIS 계좌를 가진 User 목. userId/kisAccountId 는 필드값을 사용한다.
     */
    private User userWithKisAccount() {
        UserKisAccount kisAccount = mock(UserKisAccount.class);
        lenient().when(kisAccount.getId()).thenReturn(kisAccountId);
        User user = mock(User.class);
        lenient().when(user.getId()).thenReturn(userId);
        lenient().when(user.getKisAccount()).thenReturn(kisAccount);
        return user;
    }

    private UserTradeConfig config(User user) {
        return UserTradeConfig.builder()
                .id(1L)
                .user(user)
                .orderAmount(1_000_000L)
                .maxHoldings(10)
                .orderType("market")
                .isActive(true)
                .build();
    }

    private InternalTradeRequest tradeRequest(BigDecimal price) {
        return new InternalTradeRequest("005930", "삼성전자", 10, price);
    }

    // ─── getActiveAutoTradingUsers ──────────────────────────────────────────

    @Test
    @DisplayName("getActiveAutoTradingUsers - 자동매매 활성 사용자의 실행 컨텍스트를 반환한다")
    void getActiveAutoTradingUsers_Success() {
        // Given
        UserTradeConfig activeConfig = config(userWithKisAccount());
        when(tradeConfigRepository.findByIsActiveTrue())
                .thenReturn(List.of(activeConfig));

        // When
        List<AutoTradingUserResponse> result = internalService.getActiveAutoTradingUsers();

        // Then
        assertThat(result).hasSize(1);
        AutoTradingUserResponse response = result.get(0);
        assertThat(response.getUserId()).isEqualTo(userId);
        assertThat(response.getKisAccountId()).isEqualTo(kisAccountId);
        assertThat(response.getOrderAmount()).isEqualTo(1_000_000L);
        assertThat(response.getMaxHoldings()).isEqualTo(10);
        assertThat(response.getOrderType()).isEqualTo("market");
    }

    @Test
    @DisplayName("getActiveAutoTradingUsers - KIS 계좌가 없는 사용자는 매매 대상이 될 수 없어 제외한다")
    void getActiveAutoTradingUsers_SkipsUserWithoutKisAccount() {
        // Given
        User withoutAccount = mock(User.class);
        when(withoutAccount.getKisAccount()).thenReturn(null);
        when(withoutAccount.getId()).thenReturn(2L);

        UserTradeConfig withAccountConfig = config(userWithKisAccount());
        UserTradeConfig withoutAccountConfig = config(withoutAccount);
        when(tradeConfigRepository.findByIsActiveTrue())
                .thenReturn(List.of(withAccountConfig, withoutAccountConfig));

        // When
        List<AutoTradingUserResponse> result = internalService.getActiveAutoTradingUsers();

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUserId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("getActiveAutoTradingUsers - user 가 연결되지 않은 설정은 제외한다")
    void getActiveAutoTradingUsers_SkipsConfigWithoutUser() {
        // Given
        UserTradeConfig withoutUserConfig = config(null);
        UserTradeConfig withUserConfig = config(userWithKisAccount());
        when(tradeConfigRepository.findByIsActiveTrue())
                .thenReturn(List.of(withoutUserConfig, withUserConfig));

        // When
        List<AutoTradingUserResponse> result = internalService.getActiveAutoTradingUsers();

        // Then
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("getActiveAutoTradingUsers - 활성 사용자가 없으면 빈 목록")
    void getActiveAutoTradingUsers_NoActiveUsers_ReturnsEmpty() {
        // Given
        when(tradeConfigRepository.findByIsActiveTrue()).thenReturn(List.of());

        // When
        List<AutoTradingUserResponse> result = internalService.getActiveAutoTradingUsers();

        // Then
        assertThat(result).isEmpty();
    }

    // ─── getHoldings ────────────────────────────────────────────────────────

    @Test
    @DisplayName("getHoldings - TradingService 에 그대로 위임한다")
    void getHoldings_DelegatesToTradingService() {
        // Given
        BalanceSummaryResponse expected = BalanceSummaryResponse.builder()
                .totalEvaluationAmount(new BigDecimal("10000000"))
                .build();
        when(tradingService.getHoldings(userId)).thenReturn(expected);

        // When
        BalanceSummaryResponse result = internalService.getHoldings(userId);

        // Then
        assertThat(result).isSameAs(expected);
        verify(tradingService, times(1)).getHoldings(userId);
    }

    // ─── executeBuy / executeSell ───────────────────────────────────────────

    @Test
    @DisplayName("executeBuy - userId 로 kisAccountId 를 해석해 TradingService 에 위임한다")
    void executeBuy_ResolvesKisAccountAndDelegates() {
        // Given
        User userWithAccount = userWithKisAccount();
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithAccount));
        Map<String, Object> kisResult = new HashMap<>();
        kisResult.put("rt_cd", "0");
        when(tradingService.executeBuy(eq(userId), eq(kisAccountId), eq("005930"), eq("삼성전자"),
                eq(10), any(BigDecimal.class))).thenReturn(kisResult);

        // When
        Map<String, Object> result = internalService.executeBuy(userId, tradeRequest(new BigDecimal("70000")));

        // Then
        assertThat(result.get("rt_cd")).isEqualTo("0");
        verify(tradingService, times(1)).executeBuy(
                userId, kisAccountId, "005930", "삼성전자", 10, new BigDecimal("70000"));
    }

    @Test
    @DisplayName("executeBuy - price 미지정(null)은 0(시장가)으로 위임한다")
    void executeBuy_NullPrice_DelegatesZero() {
        // Given: ai-agent Stage 6 는 시장가 주문에 price 를 생략한다.
        User userWithAccount = userWithKisAccount();
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithAccount));
        when(tradingService.executeBuy(anyLong(), anyLong(), anyString(), anyString(), anyInt(),
                any(BigDecimal.class))).thenReturn(new HashMap<>());

        // When
        internalService.executeBuy(userId, tradeRequest(null));

        // Then
        verify(tradingService, times(1)).executeBuy(
                userId, kisAccountId, "005930", "삼성전자", 10, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("executeSell - userId 로 kisAccountId 를 해석해 TradingService 에 위임한다")
    void executeSell_ResolvesKisAccountAndDelegates() {
        // Given
        User userWithAccount = userWithKisAccount();
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithAccount));
        Map<String, Object> kisResult = new HashMap<>();
        kisResult.put("rt_cd", "0");
        when(tradingService.executeSell(anyLong(), anyLong(), anyString(), anyString(), anyInt(),
                any(BigDecimal.class))).thenReturn(kisResult);

        // When
        Map<String, Object> result = internalService.executeSell(userId, tradeRequest(new BigDecimal("75000")));

        // Then
        assertThat(result.get("rt_cd")).isEqualTo("0");
        verify(tradingService, times(1)).executeSell(
                userId, kisAccountId, "005930", "삼성전자", 10, new BigDecimal("75000"));
    }

    @Test
    @DisplayName("executeSell - price 미지정(null)은 0(시장가)으로 위임한다")
    void executeSell_NullPrice_DelegatesZero() {
        // Given
        User userWithAccount = userWithKisAccount();
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithAccount));
        when(tradingService.executeSell(anyLong(), anyLong(), anyString(), anyString(), anyInt(),
                any(BigDecimal.class))).thenReturn(new HashMap<>());

        // When
        internalService.executeSell(userId, tradeRequest(null));

        // Then
        verify(tradingService, times(1)).executeSell(
                userId, kisAccountId, "005930", "삼성전자", 10, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("executeBuy - 사용자가 없으면 USER_NOT_FOUND(3000)로 매매 전에 막는다")
    void executeBuy_UserNotFound_Throws() {
        // Given
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> internalService.executeBuy(userId, tradeRequest(new BigDecimal("70000"))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);

        verifyNoInteractions(tradingService);
    }

    @Test
    @DisplayName("executeBuy - KIS 계좌가 없으면 KIS_ACCOUNT_NOT_FOUND(4000)로 막는다")
    void executeBuy_NoKisAccount_Throws() {
        // Given
        User user = mock(User.class);
        when(user.getKisAccount()).thenReturn(null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // When / Then
        assertThatThrownBy(() -> internalService.executeBuy(userId, tradeRequest(new BigDecimal("70000"))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KIS_ACCOUNT_NOT_FOUND);

        verifyNoInteractions(tradingService);
    }

    @Test
    @DisplayName("executeSell - KIS 계좌가 없으면 KIS_ACCOUNT_NOT_FOUND(4000)로 막는다")
    void executeSell_NoKisAccount_Throws() {
        // Given
        User user = mock(User.class);
        when(user.getKisAccount()).thenReturn(null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // When / Then
        assertThatThrownBy(() -> internalService.executeSell(userId, tradeRequest(new BigDecimal("70000"))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KIS_ACCOUNT_NOT_FOUND);

        verifyNoInteractions(tradingService);
    }
}
