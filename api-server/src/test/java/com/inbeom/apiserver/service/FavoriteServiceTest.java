package com.inbeom.apiserver.service;

import com.inbeom.apiserver.domain.StockMaster;
import com.inbeom.apiserver.domain.UserFavorite;
import com.inbeom.apiserver.dto.favorite.FavoriteResponse;
import com.inbeom.apiserver.repository.StockMasterRepository;
import com.inbeom.apiserver.repository.UserFavoriteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@DisplayName("FavoriteService 단위 테스트")
class FavoriteServiceTest {

    @Mock
    private UserFavoriteRepository userFavoriteRepository;

    @Mock
    private StockMasterRepository stockMasterRepository;

    @Mock
    private KisQuoteClient kisQuoteClient;

    @InjectMocks
    private FavoriteService favoriteService;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = 1L;
    }

    private UserFavorite domesticFavorite(String code, String name) {
        return UserFavorite.builder()
                .id(1L)
                .userId(userId)
                .stockCode(code)
                .stockName(name)
                .build();
    }

    @Nested
    @DisplayName("관심 종목 목록 조회 테스트")
    class GetFavoritesTest {

        @Test
        @DisplayName("getFavorites - 국내 종목은 KIS 시세로 현재가/등락률을 채운다")
        void getFavorites_Domestic_WithQuote() {
            // Given
            given(userFavoriteRepository.findByUserId(userId))
                    .willReturn(List.of(domesticFavorite("005930", "삼성전자")));
            given(kisQuoteClient.fetchCurrentPrice("005930"))
                    .willReturn(Map.of("stck_prpr", "71200", "prdy_ctrt", "1.35"));

            // When
            List<FavoriteResponse> result = favoriteService.getFavorites(userId);

            // Then
            assertThat(result).hasSize(1);
            FavoriteResponse item = result.get(0);
            assertThat(item.getStockCode()).isEqualTo("005930");
            assertThat(item.getStockName()).isEqualTo("삼성전자");
            assertThat(item.getCurrentPrice()).isEqualTo(71200L);
            assertThat(item.getChangeRate()).isEqualByComparingTo(new BigDecimal("1.35"));
            assertThat(item.getNotice()).isNull();
        }

        @Test
        @DisplayName("getFavorites - 시세 조회 실패 시 가격은 null, notice 로 사유를 노출한다")
        void getFavorites_QuoteUnavailable_DegradesWithNotice() {
            // Given
            given(userFavoriteRepository.findByUserId(userId))
                    .willReturn(List.of(domesticFavorite("005930", "삼성전자")));
            given(kisQuoteClient.fetchCurrentPrice("005930")).willReturn(null);
            given(kisQuoteClient.unavailableNotice()).willReturn(KisQuoteClient.NOTICE_KIS_QUOTE);

            // When
            List<FavoriteResponse> result = favoriteService.getFavorites(userId);

            // Then
            assertThat(result).hasSize(1);
            FavoriteResponse item = result.get(0);
            assertThat(item.getCurrentPrice()).isNull();
            assertThat(item.getChangeRate()).isNull();
            assertThat(item.getNotice()).isEqualTo(KisQuoteClient.NOTICE_KIS_QUOTE);
        }

        @Test
        @DisplayName("getFavorites - 해외 종목은 국내 시세 API 를 호출하지 않고 거래소 코드만 반환한다")
        void getFavorites_Overseas_SkipsDomesticQuote() {
            // Given
            UserFavorite overseas = UserFavorite.builder()
                    .id(2L)
                    .userId(userId)
                    .stockCode("AAPL")
                    .stockName("Apple Inc.")
                    .exchangeCode("NASD")
                    .build();
            given(userFavoriteRepository.findByUserId(userId)).willReturn(List.of(overseas));

            // When
            List<FavoriteResponse> result = favoriteService.getFavorites(userId);

            // Then
            assertThat(result).hasSize(1);
            FavoriteResponse item = result.get(0);
            assertThat(item.getStockCode()).isEqualTo("AAPL");
            assertThat(item.getExchangeCode()).isEqualTo("NASD");
            assertThat(item.getCurrentPrice()).isNull();
            assertThat(item.getNotice()).isNull();

            then(kisQuoteClient).should(never()).fetchCurrentPrice(anyString());
        }

        @Test
        @DisplayName("getFavorites - 등록된 관심 종목이 없으면 빈 목록")
        void getFavorites_Empty() {
            // Given
            given(userFavoriteRepository.findByUserId(userId)).willReturn(List.of());

            // When
            List<FavoriteResponse> result = favoriteService.getFavorites(userId);

            // Then
            assertThat(result).isEmpty();
            then(kisQuoteClient).should(never()).fetchCurrentPrice(anyString());
        }

        @Test
        @DisplayName("getFavorites - 천단위 콤마/소수점이 섞인 시세 문자열도 Long 으로 파싱한다")
        void getFavorites_ParsesCommaAndDecimalPrice() {
            // Given
            given(userFavoriteRepository.findByUserId(userId))
                    .willReturn(List.of(domesticFavorite("005930", "삼성전자")));
            given(kisQuoteClient.fetchCurrentPrice("005930"))
                    .willReturn(Map.of("stck_prpr", "1,234,567.89", "prdy_ctrt", "-2,5"));

            // When
            List<FavoriteResponse> result = favoriteService.getFavorites(userId);

            // Then
            assertThat(result.get(0).getCurrentPrice()).isEqualTo(1_234_567L);
            // "-2,5" 는 콤마 제거 후 "-25" 로 파싱된다 (KIS 는 콤마 없는 값을 주므로 실사용 경로는 아님).
            assertThat(result.get(0).getChangeRate()).isEqualByComparingTo(new BigDecimal("-25"));
        }

        @Test
        @DisplayName("getFavorites - 시세 필드가 숫자가 아니거나 비어 있으면 null 로 degrade")
        void getFavorites_UnparsablePriceBecomesNull() {
            // Given
            given(userFavoriteRepository.findByUserId(userId))
                    .willReturn(List.of(domesticFavorite("005930", "삼성전자")));
            given(kisQuoteClient.fetchCurrentPrice("005930"))
                    .willReturn(Map.of("stck_prpr", "N/A", "prdy_ctrt", "   "));

            // When
            List<FavoriteResponse> result = favoriteService.getFavorites(userId);

            // Then
            assertThat(result.get(0).getCurrentPrice()).isNull();
            assertThat(result.get(0).getChangeRate()).isNull();
            // 응답 맵 자체는 정상이므로 notice 는 붙지 않는다.
            assertThat(result.get(0).getNotice()).isNull();
        }
    }

    @Nested
    @DisplayName("관심 종목 추가 테스트")
    class AddFavoriteTest {

        @Test
        @DisplayName("addFavorite - 신규 종목은 stock_master 에서 종목명을 해석해 저장한다")
        void addFavorite_New_ResolvesNameFromCatalog() {
            // Given
            given(userFavoriteRepository.findByUserIdAndStockCode(userId, "005930"))
                    .willReturn(Optional.empty());
            given(stockMasterRepository
                    .findTop30ByStockCodeStartingWithOrStockNameContainingIgnoreCase("005930", "005930"))
                    .willReturn(List.of(
                            StockMaster.builder().stockCode("005935").stockName("삼성전자우").build(),
                            StockMaster.builder().stockCode("005930").stockName("삼성전자").build()));
            given(userFavoriteRepository.save(any(UserFavorite.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(kisQuoteClient.fetchCurrentPrice("005930")).willReturn(null);
            given(kisQuoteClient.unavailableNotice()).willReturn(KisQuoteClient.NOTICE_KIS_QUOTE);

            // When
            FavoriteResponse result = favoriteService.addFavorite(userId, "005930", null, null);

            // Then
            ArgumentCaptor<UserFavorite> captor = ArgumentCaptor.forClass(UserFavorite.class);
            then(userFavoriteRepository).should(times(1)).save(captor.capture());
            UserFavorite saved = captor.getValue();
            assertThat(saved.getUserId()).isEqualTo(userId);
            assertThat(saved.getStockCode()).isEqualTo("005930");
            assertThat(saved.getStockName()).isEqualTo("삼성전자");
            assertThat(saved.getExchangeCode()).isNull();

            assertThat(result.getStockName()).isEqualTo("삼성전자");
        }

        @Test
        @DisplayName("addFavorite - 카탈로그에 없는 종목은 종목코드를 이름으로 사용한다")
        void addFavorite_New_FallsBackToCodeAsName() {
            // Given
            given(userFavoriteRepository.findByUserIdAndStockCode(userId, "999999"))
                    .willReturn(Optional.empty());
            given(stockMasterRepository
                    .findTop30ByStockCodeStartingWithOrStockNameContainingIgnoreCase("999999", "999999"))
                    .willReturn(List.of());
            given(userFavoriteRepository.save(any(UserFavorite.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(kisQuoteClient.fetchCurrentPrice("999999")).willReturn(null);
            given(kisQuoteClient.unavailableNotice()).willReturn(KisQuoteClient.NOTICE_KIS_UNAVAILABLE);

            // When
            FavoriteResponse result = favoriteService.addFavorite(userId, "999999", null, null);

            // Then
            assertThat(result.getStockName()).isEqualTo("999999");
        }

        @Test
        @DisplayName("addFavorite - 프론트가 종목명을 주면 카탈로그를 조회하지 않는다 (해외 종목 경로)")
        void addFavorite_ProvidedNameWins() {
            // Given
            given(userFavoriteRepository.findByUserIdAndStockCode(userId, "AAPL"))
                    .willReturn(Optional.empty());
            given(userFavoriteRepository.save(any(UserFavorite.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            FavoriteResponse result = favoriteService.addFavorite(userId, "AAPL", "Apple Inc.", "NASD");

            // Then
            ArgumentCaptor<UserFavorite> captor = ArgumentCaptor.forClass(UserFavorite.class);
            then(userFavoriteRepository).should(times(1)).save(captor.capture());
            assertThat(captor.getValue().getStockName()).isEqualTo("Apple Inc.");
            assertThat(captor.getValue().getExchangeCode()).isEqualTo("NASD");

            assertThat(result.getExchangeCode()).isEqualTo("NASD");
            then(stockMasterRepository).shouldHaveNoInteractions();
            then(kisQuoteClient).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("addFavorite - 이미 등록된 종목은 저장 없이 기존 항목을 반환한다 (멱등)")
        void addFavorite_Idempotent_WhenAlreadyRegistered() {
            // Given
            UserFavorite existing = domesticFavorite("005930", "삼성전자");
            given(userFavoriteRepository.findByUserIdAndStockCode(userId, "005930"))
                    .willReturn(Optional.of(existing));
            given(kisQuoteClient.fetchCurrentPrice("005930"))
                    .willReturn(Map.of("stck_prpr", "71200", "prdy_ctrt", "1.35"));

            // When
            FavoriteResponse result = favoriteService.addFavorite(userId, "005930", null, null);

            // Then
            assertThat(result.getStockCode()).isEqualTo("005930");
            assertThat(result.getCurrentPrice()).isEqualTo(71200L);
            then(userFavoriteRepository).should(never()).save(any(UserFavorite.class));
            then(stockMasterRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("addFavorite - 앞뒤 공백은 trim 후 저장된다")
        void addFavorite_TrimsInputs() {
            // Given
            given(userFavoriteRepository.findByUserIdAndStockCode(userId, "005930"))
                    .willReturn(Optional.empty());
            given(userFavoriteRepository.save(any(UserFavorite.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            favoriteService.addFavorite(userId, "  005930  ", "  삼성전자  ", "  NASD  ");

            // Then
            ArgumentCaptor<UserFavorite> captor = ArgumentCaptor.forClass(UserFavorite.class);
            then(userFavoriteRepository).should(times(1)).save(captor.capture());
            UserFavorite saved = captor.getValue();
            assertThat(saved.getStockCode()).isEqualTo("005930");
            assertThat(saved.getStockName()).isEqualTo("삼성전자");
            assertThat(saved.getExchangeCode()).isEqualTo("NASD");
        }

        @Test
        @DisplayName("addFavorite - 종목코드가 null 이면 null 반환하고 저장하지 않는다")
        void addFavorite_NullCode_ReturnsNull() {
            // When
            FavoriteResponse result = favoriteService.addFavorite(userId, null, "삼성전자", null);

            // Then
            assertThat(result).isNull();
            then(userFavoriteRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("addFavorite - 종목코드가 공백이면 null 반환하고 저장하지 않는다")
        void addFavorite_BlankCode_ReturnsNull() {
            // When
            FavoriteResponse result = favoriteService.addFavorite(userId, "   ", "삼성전자", null);

            // Then
            assertThat(result).isNull();
            then(userFavoriteRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("addFavorite - 거래소 코드가 공백이면 null 로 저장된다 (국내 취급)")
        void addFavorite_BlankExchange_StoredAsNull() {
            // Given
            given(userFavoriteRepository.findByUserIdAndStockCode(userId, "005930"))
                    .willReturn(Optional.empty());
            given(userFavoriteRepository.save(any(UserFavorite.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(kisQuoteClient.fetchCurrentPrice("005930")).willReturn(null);
            given(kisQuoteClient.unavailableNotice()).willReturn(KisQuoteClient.NOTICE_KIS_QUOTE);

            // When
            favoriteService.addFavorite(userId, "005930", "삼성전자", "   ");

            // Then
            ArgumentCaptor<UserFavorite> captor = ArgumentCaptor.forClass(UserFavorite.class);
            then(userFavoriteRepository).should(times(1)).save(captor.capture());
            assertThat(captor.getValue().getExchangeCode()).isNull();
        }
    }

    @Nested
    @DisplayName("관심 종목 삭제 테스트")
    class RemoveFavoriteTest {

        @Test
        @DisplayName("removeFavorite - 사용자/종목 조합으로 삭제한다")
        void removeFavorite_Success() {
            // When
            favoriteService.removeFavorite(userId, "005930");

            // Then
            then(userFavoriteRepository).should(times(1)).deleteByUserIdAndStockCode(userId, "005930");
        }

        @Test
        @DisplayName("removeFavorite - 앞뒤 공백은 trim 후 삭제한다")
        void removeFavorite_TrimsCode() {
            // When
            favoriteService.removeFavorite(userId, "  005930 ");

            // Then
            then(userFavoriteRepository).should(times(1)).deleteByUserIdAndStockCode(userId, "005930");
        }

        @Test
        @DisplayName("removeFavorite - 종목코드가 null 이면 예외 없이 아무 것도 하지 않는다")
        void removeFavorite_NullCode_NoOp() {
            // When
            favoriteService.removeFavorite(userId, null);

            // Then
            then(userFavoriteRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("removeFavorite - 종목코드가 공백이면 예외 없이 아무 것도 하지 않는다")
        void removeFavorite_BlankCode_NoOp() {
            // When
            favoriteService.removeFavorite(userId, "  ");

            // Then
            then(userFavoriteRepository).shouldHaveNoInteractions();
        }
    }
}
