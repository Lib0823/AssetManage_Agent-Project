package com.inbeom.apiserver.service;

import com.inbeom.apiserver.client.KisApiClient;
import com.inbeom.apiserver.client.KisBondClient;
import com.inbeom.apiserver.client.KisBondClient.BondCallContext;
import com.inbeom.apiserver.dto.bond.BondInfoResponse;
import com.inbeom.apiserver.dto.bond.BondIssueInfoResponse;
import com.inbeom.apiserver.dto.bond.BondOrderbookResponse;
import com.inbeom.apiserver.dto.bond.BondOrderbookResponse.BondQuoteLevel;
import com.inbeom.apiserver.dto.bond.BondPriceResponse;
import com.inbeom.apiserver.exception.KisRateLimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.inbeom.apiserver.service.BondResponses.asString;
import static com.inbeom.apiserver.service.BondResponses.decimal;
import static com.inbeom.apiserver.service.BondResponses.firstMap;
import static com.inbeom.apiserver.service.BondResponses.isRtOk;
import static com.inbeom.apiserver.service.BondResponses.message;
import static com.inbeom.apiserver.service.BondResponses.string;

/**
 * 장내채권 시세 조회 서비스 (기본조회 · 발행정보 · 현재가 · 호가).
 *
 * <p>공개(permitAll) 경로이므로 매매용 사용자 키가 아니라 <b>앱 단위 quote 자격증명</b>
 * ({@link KisQuoteService}, 설정 {@code kis.quote-*})으로 호출한다 —
 * {@link KisQuoteClient} 가 국내주식 시세에서 쓰는 것과 같은 경로다.
 *
 * <p><b>절대 예외를 전파하지 않는다.</b> 네 종류 모두 조회 경로이고, 채권 상세 화면은 넷을
 * 병렬로 부른 뒤 되는 것만 보여준다. 하나가 예외로 끊기면 화면 전체가 사라지므로
 * 실패는 값 null + {@code notice} 로 내려간다.
 *
 * <p>안내 문구는 {@link KisQuoteClient} 의 상수를 재사용한다 — "키 미설정"·"KIS 점검"·
 * "우리 쪽 한도 초과"·"최신 아님"은 사용자가 취할 행동이 각각 달라서 구분이 필요하다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BondQuoteService {

    private final KisQuoteService kisQuoteService;
    private final KisBondClient kisBondClient;

    public BondInfoResponse getBondInfo(String bondCode) {
        return query(bondCode, kisBondClient::searchBondInfo, this::mapBondInfo,
                notice -> BondInfoResponse.builder().bondCode(bondCode).notice(notice).build());
    }

    public BondIssueInfoResponse getIssueInfo(String bondCode) {
        return query(bondCode, kisBondClient::issueInfo, this::mapIssueInfo,
                notice -> BondIssueInfoResponse.builder().bondCode(bondCode).notice(notice).build());
    }

    public BondPriceResponse getPrice(String bondCode) {
        return query(bondCode, kisBondClient::inquirePrice, this::mapPrice,
                notice -> BondPriceResponse.builder().bondCode(bondCode).notice(notice).build());
    }

    public BondOrderbookResponse getOrderbook(String bondCode) {
        return query(bondCode, kisBondClient::inquireAskingPrice,
                output -> mapOrderbook(bondCode, output),
                notice -> emptyOrderbook(bondCode, notice));
    }

    /**
     * 네 조회의 공통 골격: 자격증명 확인 → 호출 → rt_cd 확인 → output 매핑.
     * 어느 단계에서 실패하든 {@code empty.apply(안내문구)} 로 degrade 한다.
     *
     * @param call    KIS 호출 (KisBondClient 의 메서드 레퍼런스)
     * @param map     성공 시 output 맵 → DTO
     * @param empty   실패 시 안내문구 → 빈 DTO
     */
    @SuppressWarnings("rawtypes")
    private <T> T query(String bondCode,
                        java.util.function.BiFunction<BondCallContext, String, ResponseEntity<Map>> call,
                        Function<Map<String, Object>, T> map,
                        Function<String, T> empty) {
        BondCallContext ctx = resolveQuoteContext();
        if (ctx == null) {
            return empty.apply(KisQuoteClient.NOTICE_KIS_QUOTE);
        }

        ResponseEntity<Map> response;
        try {
            response = call.apply(ctx, bondCode);
        } catch (KisRateLimitExceededException e) {
            // KIS 는 멀쩡하고 원인이 우리 쪽이다 — "점검 중"으로 안내하면 사용자가 할 수 있는
            // 행동(잠시 후 재시도)이 전달되지 않는다.
            log.warn("채권 조회가 자체 rate limit 에 걸림: bondCode={}", bondCode);
            return empty.apply(KisQuoteClient.NOTICE_KIS_BUSY);
        } catch (Exception e) {
            log.warn("채권 조회 실패: bondCode={}, {}", bondCode, e.getMessage());
            return empty.apply(KisQuoteClient.NOTICE_KIS_UNAVAILABLE);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> body = response.getBody();
        if (!isRtOk(body)) {
            log.warn("채권 조회 rt_cd!=0: bondCode={}, msg={}", bondCode, message(body));
            return empty.apply(KisQuoteClient.NOTICE_KIS_UNAVAILABLE);
        }

        Map<String, Object> output = firstMap(body.get("output"));
        if (output == null) {
            // rt_cd=0 인데 output 이 비는 경우가 있다(상장폐지/미거래 종목). 오류는 아니다.
            log.debug("채권 조회 output 없음: bondCode={}", bondCode);
            output = Map.of();
        }

        T result = map.apply(output);
        if (KisApiClient.isStale(response)) {
            // 값은 있지만 지금 시점의 값이 아니다 — "불러올 수 없음"과 구분해야 오인하지 않는다.
            return withStaleNotice(result);
        }
        return result;
    }

    /**
     * 시세 호출용 자격증명·토큰·도메인 해석. 키 미설정/토큰 실패면 null → 전체 degrade.
     * ({@link KisQuoteClient#resolveQuoteContext} 와 같은 규칙)
     */
    private BondCallContext resolveQuoteContext() {
        if (!kisQuoteService.isQuoteEnabled()) {
            log.warn("KIS quote 자격증명 미설정 — 채권 시세는 안내만 내려간다");
            return null;
        }
        String token = kisQuoteService.getQuoteAccessToken();
        if (token == null) {
            log.warn("KIS quote 토큰 획득 실패 — 채권 시세는 안내만 내려간다");
            return null;
        }
        return new BondCallContext(kisQuoteService.getQuoteBaseUrl(), token,
                kisQuoteService.getQuoteAppKey(), kisQuoteService.getQuoteAppSecret());
    }

    private <T> T withStaleNotice(T result) {
        if (result instanceof BondInfoResponse r) {
            r.setNotice(KisQuoteClient.NOTICE_KIS_STALE);
        } else if (result instanceof BondIssueInfoResponse r) {
            r.setNotice(KisQuoteClient.NOTICE_KIS_STALE);
        } else if (result instanceof BondPriceResponse r) {
            r.setNotice(KisQuoteClient.NOTICE_KIS_STALE);
        } else if (result instanceof BondOrderbookResponse r) {
            r.setNotice(KisQuoteClient.NOTICE_KIS_STALE);
        }
        return result;
    }

    // ── output → DTO 매핑 (필드명 근거: _workspace/bond_api_contract.md) ──────────

    private BondInfoResponse mapBondInfo(Map<String, Object> o) {
        return BondInfoResponse.builder()
                .bondCode(string(o, "pdno"))
                .bondName(string(o, "ksd_bond_item_name"))
                .issueDate(string(o, "issu_dt"))
                .redemptionDate(string(o, "rdpt_dt"))
                .listingDate(string(o, "lstg_dt"))
                .couponRate(decimal(o, "ksd_rcvg_bond_srfc_inrt"))
                .maturityRedemptionRate(decimal(o, "bond_expd_rdpt_rt"))
                .maturityYield(decimal(o, "bond_expd_asrc_erng_rt"))
                .currencyCode(string(o, "iso_crcy_cd"))
                .bondClassName(string(o, "bond_clsf_kor_name"))
                .separateTaxationPossible(string(o, "sprx_psbl_yn"))
                .defaultOccurred(string(o, "dshn_occr_yn"))
                .build();
    }

    private BondIssueInfoResponse mapIssueInfo(Map<String, Object> o) {
        return BondIssueInfoResponse.builder()
                .bondCode(string(o, "pdno"))
                .bondName(string(o, "prdt_name"))
                .shortName(string(o, "prdt_abrv_name"))
                .faceValue(decimal(o, "papr"))
                .maturityDate(string(o, "expd_dt"))
                .couponRate(decimal(o, "srfc_inrt"))
                // 평가사 4곳을 그대로 보존한다 — 하나로 뭉개면 어느 등급인지 알 수 없어진다.
                .kisCreditGrade(string(o, "kis_crdt_grad_text"))
                .kbpCreditGrade(string(o, "kbp_crdt_grad_text"))
                .niceCreditGrade(string(o, "nice_crdt_grad_text"))
                .fnpCreditGrade(string(o, "fnp_crdt_grad_text"))
                .quoteUnitPrice(decimal(o, "bond_nmpr_unit_pric"))
                .issueAmount(decimal(o, "issu_amt"))
                .listedBalance(decimal(o, "lstg_rmnd"))
                .interestPaymentMonths(string(o, "int_dfrm_mcnt"))
                .prevInterestPaymentDate(string(o, "rgbf_int_dfrm_dt"))
                .nextInterestPaymentDate(string(o, "nxtm_int_dfrm_dt"))
                .separateTaxationPossible(string(o, "sprx_psbl_yn"))
                .tradingStopCode(string(o, "bond_tr_stop_dvsn_cd"))
                .investmentCaution(string(o, "ivst_heed_prdt_yn"))
                .build();
    }

    private BondPriceResponse mapPrice(Map<String, Object> o) {
        return BondPriceResponse.builder()
                .bondCode(string(o, "stnd_iscd"))
                .bondName(string(o, "hts_kor_isnm"))
                .currentPrice(decimal(o, "bond_prpr"))
                .prevDaySign(string(o, "prdy_vrss_sign"))
                .prevDayDiff(decimal(o, "bond_prdy_vrss"))
                .prevDayRate(decimal(o, "prdy_ctrt"))
                .accumulatedVolume(decimal(o, "acml_vol"))
                .prevClosePrice(decimal(o, "bond_prdy_clpr"))
                .openPrice(decimal(o, "bond_oprc"))
                .highPrice(decimal(o, "bond_hgpr"))
                .lowPrice(decimal(o, "bond_lwpr"))
                .earningRate(decimal(o, "ernn_rate"))
                .upperLimitPrice(decimal(o, "bond_mxpr"))
                .lowerLimitPrice(decimal(o, "bond_llam"))
                .build();
    }

    /**
     * 5단 호가 매핑.
     *
     * <p>가격은 {@code bond_askpN}(접두사 있음), 잔량은 {@code askp_rsqnN}(접두사 없음)이다.
     * 가격이 없는 단계는 목록에 넣지 않는다 — 유동성이 낮아 1~2단만 차는 것이 흔하다.
     */
    private BondOrderbookResponse mapOrderbook(String bondCode, Map<String, Object> o) {
        List<BondQuoteLevel> asks = new ArrayList<>();
        List<BondQuoteLevel> bids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            addLevel(asks, i, decimal(o, "bond_askp" + i), decimal(o, "askp_rsqn" + i),
                    decimal(o, "seln_ernn_rate" + i));
            addLevel(bids, i, decimal(o, "bond_bidp" + i), decimal(o, "bidp_rsqn" + i),
                    decimal(o, "shnu_ernn_rate" + i));
        }
        return BondOrderbookResponse.builder()
                .bondCode(bondCode)
                .quoteTime(asString(o.get("aspr_acpt_hour")))
                .asks(asks)
                .bids(bids)
                .totalAskQty(decimal(o, "total_askp_rsqn"))
                .totalBidQty(decimal(o, "total_bidp_rsqn"))
                .netQty(decimal(o, "ntby_aspr_rsqn"))
                .build();
    }

    private void addLevel(List<BondQuoteLevel> target, int level,
                          BigDecimal price, BigDecimal qty, BigDecimal yieldRate) {
        if (price == null || price.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        target.add(BondQuoteLevel.builder()
                .level(level)
                .price(price)
                .remainQty(qty)
                .yieldRate(yieldRate)
                .build());
    }

    private BondOrderbookResponse emptyOrderbook(String bondCode, String notice) {
        return BondOrderbookResponse.builder()
                .bondCode(bondCode)
                .asks(new ArrayList<>())
                .bids(new ArrayList<>())
                .notice(notice)
                .build();
    }
}
