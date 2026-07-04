package com.inbeom.apiserver.service;

import com.inbeom.apiserver.client.KisApiClient;
import com.inbeom.apiserver.service.KisAuthService.KisCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssetService {

    private final KisAuthService kisAuthService;
    private final KisApiClient kisApiClient;

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
}
