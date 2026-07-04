package com.inbeom.apiserver.service;

import com.inbeom.apiserver.client.KisApiClient;
import com.inbeom.apiserver.service.KisAuthService.KisCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AssetService 단위 테스트")
class AssetServiceTest {

    @Mock
    private KisAuthService kisAuthService;

    @Mock
    private KisApiClient kisApiClient;

    @InjectMocks
    private AssetService assetService;

    private KisCredentials mockCredentials;
    private String mockKisToken;
    private Long kisAccountId;

    @BeforeEach
    void setUp() {
        kisAccountId = 1L;
        mockKisToken = "MOCK_KIS_ACCESS_TOKEN";
        mockCredentials = new KisCredentials(
                "MOCK_APP_KEY",
                "MOCK_APP_SECRET",
                "12345678-01",
                "01",
                "https://openapivts.koreainvestment.com:29443"
        );
    }

    @Test
    @DisplayName("getHoldings - KIS API로부터 보유 종목 조회 성공")
    void getHoldings_Success() {
        // Given
        Map<String, Object> expectedHoldings = new HashMap<>();
        expectedHoldings.put("output1", Map.of(
                "tot_evlu_amt", "10000000",  // 총 평가금액
                "pchs_amt_smtl_amt", "8000000"  // 총 매입금액
        ));
        expectedHoldings.put("output2", new Object[]{
                Map.of("pdno", "005930", "prdt_name", "삼성전자", "hldg_qty", "100")
        });

        when(kisAuthService.getKisAccessToken(kisAccountId)).thenReturn(mockKisToken);
        when(kisAuthService.getKisCredentials(kisAccountId)).thenReturn(mockCredentials);
        when(kisApiClient.get(
                anyString(),
                eq("/uapi/domestic-stock/v1/trading/inquire-balance"),
                eq("VTTC8434R"),
                eq(mockKisToken),
                eq("MOCK_APP_KEY"),
                eq("MOCK_APP_SECRET"),
                anyMap(),
                eq(Map.class)
        )).thenReturn(new ResponseEntity<>(expectedHoldings, HttpStatus.OK));

        // When
        Map<String, Object> result = assetService.getHoldings(kisAccountId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).containsKey("output1");
        assertThat(result).containsKey("output2");

        verify(kisAuthService, times(1)).getKisAccessToken(kisAccountId);
        verify(kisAuthService, times(1)).getKisCredentials(kisAccountId);
        verify(kisApiClient, times(1)).get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyMap(), eq(Map.class));
    }

    @Test
    @DisplayName("getBalance - 예수금 정보 조회 성공")
    void getBalance_Success() {
        // Given
        Map<String, Object> mockHoldings = new HashMap<>();
        mockHoldings.put("output1", Map.of(
                "dnca_tot_amt", "5000000",  // 예수금 총액
                "nxdy_excc_amt", "4500000"  // 익일 정산 금액
        ));

        when(kisAuthService.getKisAccessToken(kisAccountId)).thenReturn(mockKisToken);
        when(kisAuthService.getKisCredentials(kisAccountId)).thenReturn(mockCredentials);
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(mockHoldings, HttpStatus.OK));

        // When
        Map<String, Object> result = assetService.getBalance(kisAccountId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).containsKey("balance");
        assertThat(result.get("balance")).isEqualTo(mockHoldings);
    }

    @Test
    @DisplayName("getHoldings - KIS API 호출 시 토큰 캐싱 활용")
    void getHoldings_UsesTokenCaching() {
        // Given
        Map<String, Object> mockResponse = new HashMap<>();
        when(kisAuthService.getKisAccessToken(kisAccountId)).thenReturn(mockKisToken);
        when(kisAuthService.getKisCredentials(kisAccountId)).thenReturn(mockCredentials);
        when(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyMap(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(mockResponse, HttpStatus.OK));

        // When
        assetService.getHoldings(kisAccountId);

        // Then - Verify that KisAuthService.getKisAccessToken is called (utilizing cache)
        verify(kisAuthService, times(1)).getKisAccessToken(kisAccountId);
    }
}
