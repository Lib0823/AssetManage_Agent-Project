package com.inbeom.apiserver.service;

import com.inbeom.apiserver.client.DartApiClient;
import com.inbeom.apiserver.client.KisApiClient;
import com.inbeom.apiserver.dto.company.BasicInfoResponse;
import com.inbeom.apiserver.dto.company.DisclosuresResponse;
import com.inbeom.apiserver.dto.company.FinancialsResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 종목 상세 화면(기본정보/재무제표/공시정보)용 데이터 오케스트레이션.
 *
 * KIS Open API(시세/재무) + DART Open API(회사개황/공시) 를 조합한다.
 *
 * <p>KIS 시세/재무 API 는 모의(openapivts) 도메인에서 제공되지 않으므로, 매매/잔고 흐름과 분리된
 * 실전 도메인 + 실전 자격증명({@link KisQuoteService}, 설정 kis.quote-*)으로 호출한다.
 * DB 의 모의 UserKisAccount(매매 전용)는 이 경로에서 사용하지 않는다.
 *
 * <p>모든 외부 호출은 실패 시 null 로 degrade 하고 절대 예외를 전파하지 않으며,
 * 미연동 사유는 응답의 notice 필드로 노출한다(silent all-null 방지).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyInfoService {

    private final KisQuoteService kisQuoteService;
    private final KisQuoteClient kisQuoteClient;
    private final KisApiClient kisApiClient;
    private final DartApiClient dartApiClient;

    private static final String MARKET_DIV = "J";
    private static final DateTimeFormatter DART_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String NOTICE_DART =
            "공시 데이터가 연동되지 않았습니다 (DART_API_KEY 필요)";
    private static final String NOTICE_DART_PROFILE =
            "회사 개황(공시)이 연동되지 않았습니다 (DART_API_KEY 필요)";

    // ---------------------------------------------------------------------
    // 1) 기본정보
    // ---------------------------------------------------------------------
    public BasicInfoResponse getBasicInfo(String stockCode) {
        BasicInfoResponse.BasicInfoResponseBuilder builder = BasicInfoResponse.builder()
                .stockCode(stockCode)
                .hasDartProfile(false);

        // --- KIS 주식현재가 시세 (실전 시세 도메인) ---
        String kisSector = null;
        boolean kisQuoteAvailable = false;
        Map<String, Object> price = kisQuoteClient.fetchCurrentPrice(stockCode);
        if (price != null) {
            kisQuoteAvailable = true;
            builder.currentPrice(parseLong(price.get("stck_prpr")));
            builder.changeRate(parseBigDecimal(price.get("prdy_ctrt")));
            builder.per(parseBigDecimal(price.get("per")));
            builder.pbr(parseBigDecimal(price.get("pbr")));
            builder.eps(parseBigDecimal(price.get("eps")));
            builder.bps(parseBigDecimal(price.get("bps")));
            builder.listedShares(parseLong(price.get("lstn_stcn")));
            builder.week52High(parseLong(price.get("w52_hgpr")));
            builder.week52Low(parseLong(price.get("w52_lwpr")));

            // 시가총액: hts_avls (억원) → 원
            Long htsAvls = parseLong(price.get("hts_avls"));
            if (htsAvls != null) {
                builder.marketCap(htsAvls * 100_000_000L);
            }

            kisSector = asString(price.get("bstp_kor_isnm"));
        }

        // --- DART 회사개황 ---
        String resolvedName = null;
        String dartInduty = null;
        boolean dartEnabled = dartApiClient.isEnabled();
        try {
            String corpCode = dartApiClient.getCorpCode(stockCode);
            if (corpCode != null) {
                Map<String, Object> profile = dartApiClient.getCompanyProfile(corpCode);
                if (profile != null) {
                    builder.hasDartProfile(true);
                    resolvedName = asString(profile.get("corp_name"));
                    builder.stockNameEn(asString(profile.get("corp_name_eng")));
                    builder.address(asString(profile.get("adres")));
                    builder.homepage(asString(profile.get("hm_url")));
                    builder.ceoName(asString(profile.get("ceo_nm")));
                    builder.establishedDate(asString(profile.get("est_dt")));
                    dartInduty = asString(profile.get("induty_code"));
                }
            }
        } catch (Exception e) {
            log.warn("DART profile lookup failed for stockCode={}: {}", stockCode, e.getMessage());
        }

        // sector: KIS 업종명 우선, 없으면 DART induty_code, 둘 다 없으면 null
        builder.sector(kisSector != null ? kisSector : dartInduty);

        // 종목명: DART corp_name (KIS 시세 응답엔 한글 종목명 필드가 없음). 없으면 null.
        builder.stockName(resolvedName);

        // notice: KIS 시세 미연동 / DART 회사개황 미연동 사유를 한 문장으로 결합 (모두 정상이면 null)
        List<String> notices = new ArrayList<>();
        if (!kisQuoteAvailable) {
            notices.add(kisQuoteClient.unavailableNotice());
        }
        if (!dartEnabled) {
            notices.add(NOTICE_DART_PROFILE);
        }
        builder.notice(joinNotice(notices));

        return builder.build();
    }

    // ---------------------------------------------------------------------
    // 2) 재무제표
    // ---------------------------------------------------------------------
    public FinancialsResponse getFinancials(String stockCode) {
        List<FinancialsResponse.AnnualFinancial> annual = new ArrayList<>();
        FinancialsResponse.Ratios.RatiosBuilder ratios = FinancialsResponse.Ratios.builder();

        // --- 손익계산서 (연간) ---
        try {
            Map<String, Object> income = fetchFinance(stockCode,
                    "/uapi/domestic-stock/v1/finance/income-statement", "FHKST66430200");
            List<Map<String, Object>> rows = extractOutput(income);
            if (rows != null) {
                int count = 0;
                for (Map<String, Object> row : rows) {
                    if (count >= 4) {
                        break;
                    }
                    annual.add(FinancialsResponse.AnnualFinancial.builder()
                            .year(toYear(asString(row.get("stac_yymm"))))
                            .revenue(parseLong(row.get("sale_account")))
                            .operatingProfit(parseLong(row.get("bsop_prti")))
                            .netProfit(parseLong(row.get("thtr_ntin")))
                            .build());
                    count++;
                }
            }
        } catch (Exception e) {
            log.warn("KIS income-statement failed for stockCode={}: {}", stockCode, e.getMessage());
        }

        // --- 재무비율 (ROE, EPS) ---
        try {
            Map<String, Object> ratio = fetchFinance(stockCode,
                    "/uapi/domestic-stock/v1/finance/financial-ratio", "FHKST66430300");
            List<Map<String, Object>> rows = extractOutput(ratio);
            if (rows != null && !rows.isEmpty()) {
                Map<String, Object> latest = rows.get(0);
                ratios.roe(parseBigDecimal(latest.get("roe_val")));

                // 재무비율 EPS 를 연간 손익의 최신연도에 매핑(있을 때)
                BigDecimal eps = parseBigDecimal(latest.get("eps"));
                if (eps != null && !annual.isEmpty()) {
                    annual.get(0).setEps(eps);
                }
            }
        } catch (Exception e) {
            log.warn("KIS financial-ratio failed for stockCode={}: {}", stockCode, e.getMessage());
        }

        // --- 안정성비율 (부채비율, 유동비율) ---
        try {
            Map<String, Object> stability = fetchFinance(stockCode,
                    "/uapi/domestic-stock/v1/finance/stability-ratio", "FHKST66430600");
            List<Map<String, Object>> rows = extractOutput(stability);
            if (rows != null && !rows.isEmpty()) {
                Map<String, Object> latest = rows.get(0);
                ratios.debtRatio(parseBigDecimal(latest.get("lblt_rate")));
                ratios.currentRatio(parseBigDecimal(latest.get("crnt_rate")));
            }
        } catch (Exception e) {
            log.warn("KIS stability-ratio failed for stockCode={}: {}", stockCode, e.getMessage());
        }

        // --- PER / PBR (현재가 시세) ---
        try {
            Map<String, Object> price = kisQuoteClient.fetchCurrentPrice(stockCode);
            if (price != null) {
                ratios.per(parseBigDecimal(price.get("per")));
                ratios.pbr(parseBigDecimal(price.get("pbr")));
            }
        } catch (Exception e) {
            log.warn("KIS inquire-price (ratios) failed for stockCode={}: {}", stockCode, e.getMessage());
        }

        // roa 는 KIS 미제공 → null 유지

        // notice: 시세/재무가 비활성이거나 아무 데이터도 못 받은 경우 안내
        FinancialsResponse.Ratios builtRatios = ratios.build();
        String financialsNotice = null;
        boolean anyData = !annual.isEmpty() || hasAnyRatio(builtRatios);
        if (!kisQuoteService.isQuoteEnabled() || !anyData) {
            financialsNotice = kisQuoteClient.unavailableNotice();
        }

        return FinancialsResponse.builder()
                .stockCode(stockCode)
                .annual(annual)
                .ratios(builtRatios)
                .notice(financialsNotice)
                .build();
    }

    private boolean hasAnyRatio(FinancialsResponse.Ratios r) {
        return r != null && (r.getRoe() != null || r.getRoa() != null
                || r.getPer() != null || r.getPbr() != null
                || r.getDebtRatio() != null || r.getCurrentRatio() != null);
    }

    // ---------------------------------------------------------------------
    // 3) 공시정보
    // ---------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    public DisclosuresResponse getDisclosures(String stockCode) {
        List<DisclosuresResponse.Disclosure> disclosures = new ArrayList<>();

        try {
            String corpCode = dartApiClient.getCorpCode(stockCode);
            if (corpCode != null) {
                LocalDate today = LocalDate.now();
                String begin = today.minusMonths(6).format(DART_DATE);
                String end = today.format(DART_DATE);

                Map<String, Object> body = dartApiClient.getDisclosureList(corpCode, begin, end, 20);
                if (body != null) {
                    Object listObj = body.get("list");
                    if (listObj instanceof List<?> rawList) {
                        for (Object o : rawList) {
                            if (!(o instanceof Map)) {
                                continue;
                            }
                            Map<String, Object> item = (Map<String, Object>) o;
                            String reportNm = asString(item.get("report_nm"));
                            disclosures.add(DisclosuresResponse.Disclosure.builder()
                                    .id(asString(item.get("rcept_no")))
                                    .type(deriveType(reportNm))
                                    .title(reportNm)
                                    .date(toDashDate(asString(item.get("rcept_dt"))))
                                    .important(isImportant(reportNm))
                                    .build());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("DART disclosures failed for stockCode={}: {}", stockCode, e.getMessage());
        }

        String disclosuresNotice = dartApiClient.isEnabled() ? null : NOTICE_DART;

        return DisclosuresResponse.builder()
                .stockCode(stockCode)
                .disclosures(disclosures)
                .notice(disclosuresNotice)
                .build();
    }

    // ---------------------------------------------------------------------
    // KIS helpers
    // ---------------------------------------------------------------------

    /**
     * KIS 국내주식 재무 API (손익/재무비율/안정성). 실전 시세 도메인 + 실전 quote 자격증명 사용.
     * 비활성/실패/rt_cd!=0 시 null.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchFinance(String stockCode, String endpoint, String trId) {
        QuoteContext ctx = resolveQuoteContext();
        if (ctx == null) {
            return null;
        }
        Map<String, String> params = new HashMap<>();
        params.put("FID_COND_MRKT_DIV_CODE", MARKET_DIV);
        params.put("FID_INPUT_ISCD", stockCode);
        params.put("FID_DIV_CLS_CODE", "0"); // 0=연간

        ResponseEntity<Map> response = kisApiClient.get(
                ctx.baseUrl(), endpoint, trId,
                ctx.token(), ctx.appKey(), ctx.appSecret(),
                params, Map.class
        );
        Map<String, Object> body = response.getBody();
        if (!isRtOk(body)) {
            log.warn("KIS finance {} rt_cd!=0 for stockCode={}: {}", trId, stockCode,
                    body != null ? body.get("msg1") : "null body");
            return null;
        }
        return body;
    }

    /**
     * KIS 재무 응답의 output 배열 추출 (output 또는 output1).
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractOutput(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        Object output = body.get("output");
        if (output == null) {
            output = body.get("output1");
        }
        if (output instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map) {
                    result.add((Map<String, Object>) o);
                }
            }
            return result;
        }
        return null;
    }

    private boolean isRtOk(Map<String, Object> body) {
        return body != null && "0".equals(String.valueOf(body.get("rt_cd")));
    }

    /**
     * 시세/재무 호출용 실전(real) 자격증명·토큰·도메인 해석.
     * 매매 흐름(DB 모의 키)과 분리된 설정(kis.quote-*) 기반.
     * 비활성(키 미설정)이거나 토큰 획득 실패 시 null → 시세/재무 필드 전체 degrade.
     */
    private QuoteContext resolveQuoteContext() {
        if (!kisQuoteService.isQuoteEnabled()) {
            log.warn("KIS quote credentials not configured; quotation/finance fields will be null");
            return null;
        }
        String token = kisQuoteService.getQuoteAccessToken();
        if (token == null) {
            log.warn("KIS quote token unavailable; quotation/finance fields will be null");
            return null;
        }
        return new QuoteContext(
                kisQuoteService.getQuoteBaseUrl(),
                token,
                kisQuoteService.getQuoteAppKey(),
                kisQuoteService.getQuoteAppSecret()
        );
    }

    private record QuoteContext(String baseUrl, String token, String appKey, String appSecret) {}

    /**
     * notice 문자열 결합. 비어있으면 null, 아니면 " / " 로 결합한 단일 문자열.
     */
    private String joinNotice(List<String> notices) {
        if (notices == null || notices.isEmpty()) {
            return null;
        }
        return String.join(" / ", notices);
    }

    // ---------------------------------------------------------------------
    // parsing / derivation helpers
    // ---------------------------------------------------------------------

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
            // 음수/소수/콤마 안전 처리
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

    /**
     * stac_yymm(YYYYMM) → "YYYY" 연도 문자열.
     */
    private String toYear(String stacYymm) {
        if (stacYymm == null || stacYymm.length() < 4) {
            return stacYymm;
        }
        return stacYymm.substring(0, 4);
    }

    /**
     * DART rcept_dt(YYYYMMDD) → "YYYY-MM-DD".
     */
    private String toDashDate(String yyyymmdd) {
        if (yyyymmdd == null || yyyymmdd.length() != 8) {
            return yyyymmdd;
        }
        return yyyymmdd.substring(0, 4) + "-" + yyyymmdd.substring(4, 6) + "-" + yyyymmdd.substring(6, 8);
    }

    /**
     * report_nm → 짧은 카테고리.
     */
    private String deriveType(String reportNm) {
        if (reportNm == null) {
            return "기타";
        }
        if (reportNm.contains("사업보고서") || reportNm.contains("분기보고서")
                || reportNm.contains("반기보고서") || reportNm.contains("감사보고서")) {
            return "정기공시";
        }
        if (reportNm.contains("주요사항")) {
            return "주요사항";
        }
        if (reportNm.contains("공정공시") || reportNm.contains("잠정")) {
            return "공정공시";
        }
        return "기타";
    }

    /**
     * 중요 공시 휴리스틱: 제목에 실적/영업(잠정)/배당/유상증자/주요사항/합병 포함 시 true.
     */
    private boolean isImportant(String reportNm) {
        if (reportNm == null) {
            return false;
        }
        return reportNm.contains("실적")
                || reportNm.contains("영업(잠정)")
                || reportNm.contains("배당")
                || reportNm.contains("유상증자")
                || reportNm.contains("주요사항")
                || reportNm.contains("합병");
    }
}
