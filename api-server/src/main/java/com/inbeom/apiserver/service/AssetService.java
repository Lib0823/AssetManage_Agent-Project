package com.inbeom.apiserver.service;

import com.inbeom.apiserver.client.KisApiClient;
import com.inbeom.apiserver.domain.AssetDailySnapshot;
import com.inbeom.apiserver.dto.asset.AssetHistoryResponse;
import com.inbeom.apiserver.repository.AssetDailySnapshotRepository;
import com.inbeom.apiserver.service.KisAuthService.KisCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssetService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 자산 추이 조회 최대 기간(일). */
    static final int MAX_HISTORY_DAYS = 365;

    private final KisAuthService kisAuthService;
    private final KisApiClient kisApiClient;
    private final AssetDailySnapshotRepository assetDailySnapshotRepository;

    /**
     * Get holdings from KIS API (VTTC8434R)
     */
    public Map<String, Object> getHoldings(Long kisAccountId) {
        // 1. Get KIS Access Token (cache hit ~50ms)
        String kisToken = kisAuthService.getKisAccessToken(kisAccountId);

        // 2. Get credentials
        KisCredentials credentials = kisAuthService.getKisCredentials(kisAccountId);

        // 3. Build query parameters
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("CANO", credentials.accountNumber());
        queryParams.put("ACNT_PRDT_CD", credentials.accountProductCode());
        queryParams.put("AFHR_FLPR_YN", "N");
        queryParams.put("OFL_YN", "");
        queryParams.put("INQR_DVSN", "02");
        queryParams.put("UNPR_DVSN", "01");
        queryParams.put("FUND_STTL_ICLD_YN", "N");
        queryParams.put("FNCG_AMT_AUTO_RDPT_YN", "N");
        queryParams.put("PRCS_DVSN", "01");
        queryParams.put("CTX_AREA_FK100", "");
        queryParams.put("CTX_AREA_NK100", "");

        // 4. Call KIS API
        ResponseEntity<Map> response = kisApiClient.get(
                credentials.baseUrl(),
                "/uapi/domestic-stock/v1/trading/inquire-balance",
                "VTTC8434R",
                kisToken,
                credentials.appKey(),
                credentials.appSecret(),
                queryParams,
                Map.class
        );

        return response.getBody();
    }

    /**
     * Get balance (예수금) from KIS API (VTTC8434R)
     */
    public Map<String, Object> getBalance(Long kisAccountId) {
        // Same as getHoldings, but extract only balance info
        Map<String, Object> holdings = getHoldings(kisAccountId);

        // Extract balance from holdings response
        return Map.of("balance", holdings);
    }

    /**
     * 오늘(Asia/Seoul) 총자산 스냅샷을 upsert 한다.
     * 같은 날짜 스냅샷이 있으면 total_asset 을 갱신, 없으면 새로 저장한다.
     */
    @Transactional
    public void recordSnapshot(Long userId, Long totalAsset) {
        LocalDate today = LocalDate.now(SEOUL);

        AssetDailySnapshot snapshot = assetDailySnapshotRepository
                .findByUserIdAndSnapshotDate(userId, today)
                .orElseGet(() -> AssetDailySnapshot.builder()
                        .userId(userId)
                        .snapshotDate(today)
                        .build());

        snapshot.setTotalAsset(totalAsset);
        assetDailySnapshotRepository.save(snapshot);

        log.debug("Recorded asset snapshot: userId={}, date={}, totalAsset={}",
                userId, today, totalAsset);
    }

    /**
     * (오늘-days+1 ~ 오늘) 범위의 스냅샷을 날짜 오름차순으로 반환한다.
     *
     * <p>{@code days} 는 1~{@value #MAX_HISTORY_DAYS} 로 클램프한다. 상한이 없으면
     * {@code days=1000000} 같은 요청이 전 기간 스캔이 되고, 자산 추이 차트가 쓰는 범위는
     * 최대 1년이면 충분하다.
     */
    @Transactional(readOnly = true)
    public List<AssetHistoryResponse> getHistory(Long userId, int days) {
        LocalDate today = LocalDate.now(SEOUL);
        LocalDate from = today.minusDays(Math.clamp(days, 1, MAX_HISTORY_DAYS) - 1L);

        return assetDailySnapshotRepository
                .findByUserIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(userId, from, today)
                .stream()
                .map(s -> AssetHistoryResponse.builder()
                        .date(s.getSnapshotDate().format(DATE_FORMAT))
                        .totalAsset(s.getTotalAsset())
                        .build())
                .collect(Collectors.toList());
    }
}
