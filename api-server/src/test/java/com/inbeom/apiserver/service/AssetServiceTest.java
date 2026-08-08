package com.inbeom.apiserver.service;

import com.inbeom.apiserver.client.KisApiClient;
import com.inbeom.apiserver.domain.AssetDailySnapshot;
import com.inbeom.apiserver.dto.asset.AssetHistoryResponse;
import com.inbeom.apiserver.repository.AssetDailySnapshotRepository;
import com.inbeom.apiserver.service.KisAuthService.KisCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    @Mock
    private AssetDailySnapshotRepository assetDailySnapshotRepository;

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

    // ================== recordSnapshot ==================

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Long USER_ID = 100L;

    @Test
    @DisplayName("recordSnapshot - 같은 날짜 스냅샷이 없으면 새로 생성해 저장한다")
    void recordSnapshot_NoExisting_CreatesNew() {
        // Given
        LocalDate today = LocalDate.now(SEOUL);
        when(assetDailySnapshotRepository.findByUserIdAndSnapshotDate(USER_ID, today))
                .thenReturn(Optional.empty());

        // When
        assetService.recordSnapshot(USER_ID, 12_345_678L);

        // Then
        ArgumentCaptor<AssetDailySnapshot> captor = ArgumentCaptor.forClass(AssetDailySnapshot.class);
        verify(assetDailySnapshotRepository).save(captor.capture());
        AssetDailySnapshot saved = captor.getValue();
        assertThat(saved.getId()).isNull();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getSnapshotDate()).isEqualTo(today);
        assertThat(saved.getTotalAsset()).isEqualTo(12_345_678L);
    }

    @Test
    @DisplayName("recordSnapshot - 같은 날짜 스냅샷이 있으면 total_asset 만 갱신한다(upsert)")
    void recordSnapshot_Existing_UpdatesTotalAsset() {
        // Given: 하루 1건(unique user_id+snapshot_date)이므로 기존 행을 재사용해야 한다.
        LocalDate today = LocalDate.now(SEOUL);
        AssetDailySnapshot existing = AssetDailySnapshot.builder()
                .id(7L)
                .userId(USER_ID)
                .snapshotDate(today)
                .totalAsset(1_000L)
                .build();
        when(assetDailySnapshotRepository.findByUserIdAndSnapshotDate(USER_ID, today))
                .thenReturn(Optional.of(existing));

        // When
        assetService.recordSnapshot(USER_ID, 2_000L);

        // Then
        ArgumentCaptor<AssetDailySnapshot> captor = ArgumentCaptor.forClass(AssetDailySnapshot.class);
        verify(assetDailySnapshotRepository).save(captor.capture());
        AssetDailySnapshot saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(7L);
        assertThat(saved.getTotalAsset()).isEqualTo(2_000L);
    }

    // ================== getHistory ==================

    @Test
    @DisplayName("getHistory - (오늘-days+1 ~ 오늘) 범위를 조회해 날짜/총자산으로 매핑한다")
    void getHistory_MapsSnapshotsInRange() {
        // Given
        LocalDate today = LocalDate.now(SEOUL);
        AssetDailySnapshot s1 = AssetDailySnapshot.builder()
                .userId(USER_ID).snapshotDate(today.minusDays(2)).totalAsset(1_000L).build();
        AssetDailySnapshot s2 = AssetDailySnapshot.builder()
                .userId(USER_ID).snapshotDate(today).totalAsset(3_000L).build();
        when(assetDailySnapshotRepository
                .findByUserIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(eq(USER_ID), any(), any()))
                .thenReturn(List.of(s1, s2));

        // When
        List<AssetHistoryResponse> result = assetService.getHistory(USER_ID, 7);

        // Then
        ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> toCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(assetDailySnapshotRepository).findByUserIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
                eq(USER_ID), fromCaptor.capture(), toCaptor.capture());
        assertThat(fromCaptor.getValue()).isEqualTo(today.minusDays(6));
        assertThat(toCaptor.getValue()).isEqualTo(today);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getDate()).isEqualTo(today.minusDays(2).toString());
        assertThat(result.get(0).getTotalAsset()).isEqualTo(1_000L);
        assertThat(result.get(1).getDate()).isEqualTo(today.toString());
        assertThat(result.get(1).getTotalAsset()).isEqualTo(3_000L);
    }

    @Test
    @DisplayName("getHistory - days<=0 이면 최소 1일로 보정해 오늘 하루만 조회한다")
    void getHistory_NonPositiveDays_ClampsToSingleDay() {
        // Given: Math.max(days, 1) 보정이 없으면 from 이 미래가 되어 항상 빈 결과가 된다.
        LocalDate today = LocalDate.now(SEOUL);
        when(assetDailySnapshotRepository
                .findByUserIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(eq(USER_ID), any(), any()))
                .thenReturn(List.of());

        // When
        List<AssetHistoryResponse> result = assetService.getHistory(USER_ID, 0);

        // Then
        ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(assetDailySnapshotRepository).findByUserIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
                eq(USER_ID), fromCaptor.capture(), eq(today));
        assertThat(fromCaptor.getValue()).isEqualTo(today);
        assertThat(result).isEmpty();
    }
}
