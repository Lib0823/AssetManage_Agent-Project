package com.inbeom.apiserver.realtime;

import com.inbeom.apiserver.domain.UserKisAccount;
import com.inbeom.apiserver.dto.realtime.FillMessage;
import com.inbeom.apiserver.repository.UserKisAccountRepository;
import com.inbeom.apiserver.service.KisAuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * userId → {@link UserFillsUpstreamConnection} 생성 팩토리 (체결통보 전용).
 *
 * <p>경로(자격증명 분리 — 절대 시세 quote 키와 교차 금지):
 * <ol>
 *   <li>{@link UserKisAccountRepository#findByUserId} → 계좌(htsId 포함).</li>
 *   <li>{@link KisAuthService#getKisCredentials}(kisAccountId) → 사용자 appkey/secret (복호).</li>
 *   <li>approval base = {@code kis.base-url}, ws-url = {@code kis.realtime.fills.ws-url}(:21000).</li>
 *   <li>htsId blank → {@link Result#error} (graceful degrade, 연결 생성 안 함).</li>
 * </ol>
 *
 * <p>approval 발급은 사용자 appkey/secret 으로 {@code POST {kis.base-url}/oauth2/Approval}
 * (body 필드 {@code secretkey} — Phase 1 과 동일). 실패 시 null(예외 비전파).
 *
 * <p>{@code @ConditionalOnProperty(kis.realtime.fills.enabled)} — 비활성 시 빈 미생성.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "kis.realtime.fills.enabled", havingValue = "true")
public class UserFillsConnectionFactory {

    private static final String APPROVAL_PATH = "/oauth2/Approval";
    /** Phase 1 KisApprovalKeyProvider.FIELD_SECRET_KEY 와 동일. */
    private static final String FIELD_SECRET_KEY = "secretkey";

    private final UserKisAccountRepository kisAccountRepository;
    private final KisAuthService kisAuthService;
    private final KisFillFrameDecryptor decryptor;
    private final FillFrameParser fillFrameParser;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final String approvalBaseUrl;   // kis.base-url (trade 도메인, fallback)
    private final String fillsWsUrl;        // kis.realtime.fills.ws-url (:21000)

    private final RestTemplate restTemplate = buildRestTemplate();

    public UserFillsConnectionFactory(
            UserKisAccountRepository kisAccountRepository,
            KisAuthService kisAuthService,
            KisFillFrameDecryptor decryptor,
            FillFrameParser fillFrameParser,
            tools.jackson.databind.ObjectMapper objectMapper,
            @Value("${kis.base-url}") String approvalBaseUrl,
            @Value("${kis.realtime.fills.ws-url:ws://ops.koreainvestment.com:21000}") String fillsWsUrl) {
        this.kisAccountRepository = kisAccountRepository;
        this.kisAuthService = kisAuthService;
        this.decryptor = decryptor;
        this.fillFrameParser = fillFrameParser;
        this.objectMapper = objectMapper;
        this.approvalBaseUrl = approvalBaseUrl;
        this.fillsWsUrl = fillsWsUrl;
    }

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(8000);
        return new RestTemplate(factory);
    }

    /** 팩토리 결과: 연결 또는 에러 사유(status:error notice). */
    public record Result(UserFillsUpstreamConnection connection, String errorNotice) {
        public static Result ok(UserFillsUpstreamConnection c) {
            return new Result(c, null);
        }

        public static Result error(String notice) {
            return new Result(null, notice);
        }

        public boolean isOk() {
            return connection != null;
        }
    }

    /**
     * userId 로 체결통보 연결을 만든다(아직 open 하지 않음 — 호출부가 open()).
     *
     * @param userId   인증 사용자 ID
     * @param fanOut   (userId, FillMessage) → 레지스트리 fan-out
     * @param onStatus (state, notice) → 해당 유저 세션 상태 통지 (nullable)
     */
    public Result create(Long userId,
                         BiConsumer<Long, FillMessage> fanOut,
                         BiConsumer<String, String> onStatus) {
        if (userId == null) {
            return Result.error("사용자 식별 실패 (체결통보)");
        }
        Optional<UserKisAccount> opt = kisAccountRepository.findByUserId(userId);
        if (opt.isEmpty()) {
            log.warn("[fills] userId={} has no KIS account; fills unavailable", userId);
            return Result.error("연결된 KIS 계좌가 없습니다 (체결통보)");
        }
        UserKisAccount account = opt.get();
        String htsId = account.getHtsId();
        if (htsId == null || htsId.isBlank()) {
            log.warn("[fills] userId={} htsId not set; fills unavailable (degrade)", userId);
            return Result.error("체결통보를 받으려면 HTS ID 설정이 필요합니다");
        }

        KisAuthService.KisCredentials creds;
        try {
            creds = kisAuthService.getKisCredentials(account.getId());
        } catch (Exception e) {
            log.warn("[fills] userId={} credential load failed: {}", userId, e.getMessage());
            return Result.error("KIS 계좌 자격증명을 불러오지 못했습니다 (체결통보)");
        }

        String accountApprovalBaseUrl = (creds.baseUrl() != null && !creds.baseUrl().isBlank())
                ? creds.baseUrl() : approvalBaseUrl;

        ConnectionCredentials connCreds = new ConnectionCredentials(
                creds.appKey(), creds.appSecret(), accountApprovalBaseUrl);
        if (!connCreds.isUsable()) {
            log.warn("[fills] userId={} credentials not usable; fills unavailable", userId);
            return Result.error("KIS 계좌 자격증명이 유효하지 않습니다 (체결통보)");
        }

        UserFillsUpstreamConnection connection = new UserFillsUpstreamConnection(
                userId,
                connCreds,
                RealtimeTr.KR_FILL.trId(),
                htsId,
                fillsWsUrl,
                decryptor,
                fillFrameParser,
                objectMapper,
                this::issueApprovalKey,
                fanOut,
                onStatus);
        return Result.ok(connection);
    }

    /**
     * 사용자 자격증명으로 approval_key 발급 ({@code POST {base}/oauth2/Approval}).
     * 실패 시 null(예외 비전파). secretkey 필드명 사용(Phase 1 동일).
     */
    private String issueApprovalKey(ConnectionCredentials creds) {
        if (creds == null || !creds.isUsable()) {
            return null;
        }
        try {
            String url = creds.approvalBaseUrl() + APPROVAL_PATH;
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> body = new LinkedHashMap<>();
            body.put("grant_type", "client_credentials");
            body.put("appkey", creds.appKey());
            body.put(FIELD_SECRET_KEY, creds.appSecret());

            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
            Map<String, Object> respBody = response.getBody();
            if (response.getStatusCode() == HttpStatus.OK && respBody != null) {
                Object key = respBody.get("approval_key");
                if (key instanceof String s && !s.isBlank()) {
                    log.info("[fills] approval_key issued (len={})", s.length());
                    return s;
                }
                log.warn("[fills] approval response missing approval_key: keys={}", respBody.keySet());
                return null;
            }
            log.warn("[fills] approval non-OK response: {}", response.getStatusCode());
            return null;
        } catch (Exception e) {
            log.warn("[fills] approval_key issue failed: {} (verify body field '{}')",
                    e.getMessage(), FIELD_SECRET_KEY);
            return null;
        }
    }
}
