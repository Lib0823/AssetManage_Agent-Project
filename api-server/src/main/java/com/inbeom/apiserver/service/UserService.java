package com.inbeom.apiserver.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.inbeom.apiserver.client.UpbitApiClient;
import com.inbeom.apiserver.domain.User;
import com.inbeom.apiserver.domain.UserKisAccount;
import com.inbeom.apiserver.domain.UserSettings;
import com.inbeom.apiserver.domain.UserTradeConfig;
import com.inbeom.apiserver.domain.UserUpbitAccount;
import com.inbeom.apiserver.dto.user.KisAccountResponse;
import com.inbeom.apiserver.dto.user.TradeConfigResponse;
import com.inbeom.apiserver.dto.user.UpbitAccountResponse;
import com.inbeom.apiserver.dto.user.UpdateKisAccountRequest;
import com.inbeom.apiserver.dto.user.UpdateUpbitAccountRequest;
import com.inbeom.apiserver.dto.user.UpdateTradeConfigRequest;
import com.inbeom.apiserver.dto.user.UpdateUserProfileRequest;
import com.inbeom.apiserver.dto.user.UpdateUserSettingsRequest;
import com.inbeom.apiserver.dto.user.UserProfileResponse;
import com.inbeom.apiserver.dto.user.UserSettingsResponse;
import com.inbeom.apiserver.exception.ErrorCode;
import com.inbeom.apiserver.exception.BusinessException;
import com.inbeom.apiserver.exception.UserNotFoundException;
import com.inbeom.apiserver.repository.CoinTradeHistoryRepository;
import com.inbeom.apiserver.repository.RefreshTokenRepository;
import com.inbeom.apiserver.repository.UserKisAccountRepository;
import com.inbeom.apiserver.repository.UserRepository;
import com.inbeom.apiserver.repository.UserSettingsRepository;
import com.inbeom.apiserver.repository.UserTradeConfigRepository;
import com.inbeom.apiserver.repository.UserUpbitAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jasypt.encryption.StringEncryptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserTradeConfigRepository tradeConfigRepository;
    private final UserKisAccountRepository kisAccountRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final UserUpbitAccountRepository upbitAccountRepository;
    private final CoinTradeHistoryRepository coinTradeHistoryRepository;
    private final UpbitAuthService upbitAuthService;
    private final UpbitApiClient upbitApiClient;
    private final ObjectMapper objectMapper;
    private final StringEncryptor jasyptStringEncryptor;

    /**
     * Get user's trade configuration
     */
    @Transactional(readOnly = true)
    public TradeConfigResponse getTradeConfig(Long userId) {
        UserTradeConfig config = tradeConfigRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ENTITY_NOT_FOUND,
                        "Trade configuration not found for user: " + userId
                ));

        return TradeConfigResponse.builder()
                .id(config.getId())
                .orderAmount(config.getOrderAmount())
                .maxHoldings(config.getMaxHoldings())
                .orderType(config.getOrderType())
                .isActive(config.getIsActive())
                .build();
    }

    /**
     * Update user's trade configuration
     */
    @Transactional
    public TradeConfigResponse updateTradeConfig(Long userId, UpdateTradeConfigRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        UserTradeConfig config = tradeConfigRepository.findByUser(user)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ENTITY_NOT_FOUND,
                        "Trade configuration not found for user: " + userId
                ));

        // Update configuration
        config.setOrderAmount(request.getOrderAmount());
        config.setMaxHoldings(request.getMaxHoldings());
        config.setOrderType(request.getOrderType());
        config.setIsActive(request.getIsActive());

        config = tradeConfigRepository.save(config);

        return TradeConfigResponse.builder()
                .id(config.getId())
                .orderAmount(config.getOrderAmount())
                .maxHoldings(config.getMaxHoldings())
                .orderType(config.getOrderType())
                .isActive(config.getIsActive())
                .build();
    }

    /**
     * Get user profile
     */
    @Transactional(readOnly = true)
    public UserProfileResponse getUserProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        return UserProfileResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .name(user.getName())
                .phone(user.getPhone())
                .birthDate(user.getBirthDate())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }

    /**
     * Update user profile
     */
    @Transactional
    public UserProfileResponse updateUserProfile(Long userId, UpdateUserProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        // Check if email is changed and already exists
        if (!user.getEmail().equals(request.getEmail())) {
            if (userRepository.findByEmail(request.getEmail()).isPresent()) {
                throw new BusinessException(ErrorCode.EMAIL_DUPLICATE, "이미 사용 중인 이메일입니다");
            }
        }

        // Update user profile
        user.setName(request.getName());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setBirthDate(request.getBirthDate());

        user = userRepository.save(user);

        return UserProfileResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .name(user.getName())
                .phone(user.getPhone())
                .birthDate(user.getBirthDate())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }

    /**
     * Get user settings
     */
    @Transactional(readOnly = true)
    public UserSettingsResponse getUserSettings(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        UserSettings settings = userSettingsRepository.findByUserId(userId)
                .orElseGet(() -> createDefaultSettings(userId));

        try {
            JsonNode assetOrder = settings.getAssetOrder() != null
                ? objectMapper.readTree(settings.getAssetOrder())
                : objectMapper.createArrayNode();

            JsonNode notifications = settings.getNotifications() != null
                ? objectMapper.readTree(settings.getNotifications())
                : objectMapper.createObjectNode();

            return UserSettingsResponse.builder()
                    .assetOrder(assetOrder)
                    .darkMode(settings.getDarkMode())
                    .autoLogin(settings.getAutoLogin())
                    .notifications(notifications)
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse settings JSON for user: {}", userId, e);
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "설정 정보를 불러오는데 실패했습니다");
        }
    }

    /**
     * Update user settings
     */
    @Transactional
    public UserSettingsResponse updateUserSettings(Long userId, UpdateUserSettingsRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        UserSettings settings = userSettingsRepository.findByUserId(userId)
                .orElseGet(() -> createDefaultSettings(userId));

        try {
            // Update settings
            if (request.getAssetOrder() != null) {
                settings.setAssetOrder(objectMapper.writeValueAsString(request.getAssetOrder()));
            }
            settings.setDarkMode(request.getDarkMode());
            settings.setAutoLogin(request.getAutoLogin());
            if (request.getNotifications() != null) {
                settings.setNotifications(objectMapper.writeValueAsString(request.getNotifications()));
            }

            settings = userSettingsRepository.save(settings);

            return UserSettingsResponse.builder()
                    .assetOrder(request.getAssetOrder())
                    .darkMode(settings.getDarkMode())
                    .autoLogin(settings.getAutoLogin())
                    .notifications(request.getNotifications())
                    .build();
        } catch (Exception e) {
            log.error("Failed to update settings for user: {}", userId, e);
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "설정 저장에 실패했습니다");
        }
    }

    /**
     * Create default settings for user
     */
    private UserSettings createDefaultSettings(Long userId) {
        String defaultAssetOrder = "[{\"key\":\"stocks_overseas\",\"label\":\"주식 (해외)\",\"icon\":\"📈\"},{\"key\":\"stocks_domestic\",\"label\":\"주식 (국내)\",\"icon\":\"🏠\"},{\"key\":\"coins\",\"label\":\"코인\",\"icon\":\"🪙\"},{\"key\":\"bonds\",\"label\":\"채권\",\"icon\":\"📜\"}]";
        String defaultNotifications = "{\"stocks\":{\"news\":true,\"trading\":true},\"coins\":{\"news\":true,\"trading\":true}}";

        UserSettings settings = UserSettings.builder()
                .userId(userId)
                .assetOrder(defaultAssetOrder)
                .darkMode(false)
                .autoLogin(false)
                .notifications(defaultNotifications)
                .build();

        return userSettingsRepository.save(settings);
    }

    /**
     * Delete user account (회원 탈퇴)
     * Cascading delete: RefreshToken, UserKisAccount, UserTradeConfig, UserSettings, TradeHistory
     */
    @Transactional
    public void deleteAccount(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        log.info("Deleting account for user: {} ({})", user.getUsername(), userId);

        // 1. Delete refresh tokens
        refreshTokenRepository.deleteByUserId(userId);
        log.debug("Deleted refresh tokens for user: {}", userId);

        // 2. Delete KIS account (if exists)
        kisAccountRepository.findByUserId(userId).ifPresent(kisAccount -> {
            kisAccountRepository.delete(kisAccount);
            log.debug("Deleted KIS account for user: {}", userId);
        });

        // 3. Delete trade config
        tradeConfigRepository.findByUserId(userId).ifPresent(config -> {
            tradeConfigRepository.delete(config);
            log.debug("Deleted trade config for user: {}", userId);
        });

        // 4. Delete user settings
        userSettingsRepository.findByUserId(userId).ifPresent(settings -> {
            userSettingsRepository.delete(settings);
            log.debug("Deleted user settings for user: {}", userId);
        });

        // 5. Delete Upbit account + coin trade history (if any)
        upbitAccountRepository.findByUserId(userId).ifPresent(upbitAccount -> {
            upbitAccountRepository.delete(upbitAccount);
            log.debug("Deleted Upbit account for user: {}", userId);
        });
        coinTradeHistoryRepository.deleteByUserId(userId);
        log.debug("Deleted coin trade history for user: {}", userId);

        // 6. Stock trade history not stored in DB (fetched from KIS API directly)
        log.debug("Trade history is fetched from KIS API, no DB cleanup needed");

        // 7. Finally delete user
        userRepository.delete(user);
        log.info("Successfully deleted account for user: {}", userId);
    }

    /**
     * Get user KIS account
     */
    @Transactional(readOnly = true)
    public KisAccountResponse getKisAccount(Long userId) {
        UserKisAccount kisAccount = kisAccountRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ENTITY_NOT_FOUND,
                        "KIS 계좌 정보를 찾을 수 없습니다"
                ));

        return KisAccountResponse.builder()
                .id(kisAccount.getId())
                .accountNumber(kisAccount.getAccountNumber())
                .accountProductCode(kisAccount.getAccountProductCode())
                .appKey(decryptForDisplay(kisAccount.getAppKey()))
                .appSecret(decryptForDisplay(kisAccount.getAppSecret()))
                .htsId(kisAccount.getHtsId())
                .isVerified(kisAccount.getIsVerified())
                .createdAt(kisAccount.getCreatedAt())
                .updatedAt(kisAccount.getUpdatedAt())
                .build();
    }

    /**
     * 저장된 자격증명을 화면 표시용으로 복호화한다. 실패 시 예외 대신 null 을 준다.
     *
     * <p>암호화 도입 이전에 평문으로 저장된 레코드는 복호화가 실패하는데, 그 때문에 프로필 화면
     * 전체가 500 으로 죽으면 사용자가 키를 다시 등록할 방법조차 없어진다. null 을 주면 web-app 이
     * 빈 입력칸으로 렌더하고(`response.data.appKey || ''`), 사용자가 재입력하면 암호화되어 저장된다.
     * 매매 경로({@code KisAuthService})는 반대로 복호화 실패를 4006 으로 확실히 끊는다.
     */
    private String decryptForDisplay(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        try {
            return jasyptStringEncryptor.decrypt(encrypted);
        } catch (Exception e) {
            log.warn("KIS 자격증명 복호화 실패 — 평문으로 저장된 레거시 레코드로 보입니다. 재등록이 필요합니다.");
            return null;
        }
    }

    /**
     * Update user KIS account
     */
    @Transactional
    public KisAccountResponse updateKisAccount(Long userId, UpdateKisAccountRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        UserKisAccount kisAccount = kisAccountRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ENTITY_NOT_FOUND,
                        "KIS 계좌 정보를 찾을 수 없습니다"
                ));

        // Check if account number is changed and already exists
        if (!kisAccount.getAccountNumber().equals(request.getAccountNumber())) {
            if (kisAccountRepository.findByAccountNumber(request.getAccountNumber()).isPresent()) {
                throw new BusinessException(ErrorCode.KIS_ACCOUNT_DUPLICATE, "이미 등록된 계좌번호입니다");
            }
        }

        // Update KIS account
        kisAccount.setAccountNumber(request.getAccountNumber());
        kisAccount.setAppKey(jasyptStringEncryptor.encrypt(request.getAppKey()));
        kisAccount.setAppSecret(jasyptStringEncryptor.encrypt(request.getAppSecret()));
        // HTS ID (체결통보 tr_key) — 선택값. 제공된 경우에만 반영.
        if (request.getHtsId() != null) {
            kisAccount.setHtsId(request.getHtsId());
        }
        // Reset verification status when credentials are changed
        kisAccount.setIsVerified(false);

        kisAccount = kisAccountRepository.save(kisAccount);

        return KisAccountResponse.builder()
                .id(kisAccount.getId())
                .accountNumber(kisAccount.getAccountNumber())
                .accountProductCode(kisAccount.getAccountProductCode())
                .appKey(decryptForDisplay(kisAccount.getAppKey()))
                .appSecret(decryptForDisplay(kisAccount.getAppSecret()))
                .htsId(kisAccount.getHtsId())
                .isVerified(kisAccount.getIsVerified())
                .createdAt(kisAccount.getCreatedAt())
                .updatedAt(kisAccount.getUpdatedAt())
                .build();
    }

    // ------------------------------------------------------------------
    // 업비트 계좌 (코인)
    // ------------------------------------------------------------------

    /**
     * 업비트 계좌 등록 상태 조회.
     *
     * <p>미등록이어도 404 가 아니라 {@code registered=false} 를 돌려준다 — 설정 화면은 "등록 안 됨"
     * 상태를 정상적으로 그려야 하고, 그러려면 예외가 아니라 값이 필요하다.
     *
     * <p><b>{@code getKisAccount} 와 달리 자격증명을 복호화하지 않는다.</b> 그쪽의
     * {@code decryptForDisplay} 는 레거시 평문 레코드 대응이라는 사정이 있지만, 이 테이블은
     * 신규라 그 사정이 없다. 복호화 자체를 하지 않으면 유출 경로도 없다.
     */
    @Transactional(readOnly = true)
    public UpbitAccountResponse getUpbitAccount(Long userId) {
        return upbitAccountRepository.findByUserId(userId)
                .map(account -> UpbitAccountResponse.builder()
                        .id(account.getId())
                        .registered(true)
                        .accessKeyMasked(maskAccessKey(account.getAccessKey()))
                        .secretKeyRegistered(account.getSecretKey() != null && !account.getSecretKey().isBlank())
                        .isVerified(account.getIsVerified())
                        .createdAt(account.getCreatedAt())
                        .updatedAt(account.getUpdatedAt())
                        .build())
                .orElseGet(() -> UpbitAccountResponse.builder()
                        .registered(false)
                        .secretKeyRegistered(false)
                        .isVerified(false)
                        .build());
    }

    /**
     * 업비트 계좌 등록·수정. 없으면 만들고 있으면 갱신한다(upsert).
     *
     * <p><b>빈 값은 기존 키 유지</b>다. 조회가 실제 키를 돌려주지 않으므로 프론트가 되채울 수 없고,
     * 빈 값을 삭제로 해석하면 Access Key 만 고치려던 사용자가 Secret Key 를 잃는다.
     *
     * <p>저장 직후 {@code GET /v1/accounts} 를 <b>1회</b> 호출해 키가 실제로 동작하는지 확인한다.
     * 별도 검증 엔드포인트({@code POST /auth/validate-upbit-account})를 만들지 않는 이유는,
     * {@code /auth/**} 가 permitAll 이라 그 자리에 두면 <b>외부인이 이 서버를 업비트 키 유효성
     * 검사기로 쓸 수 있는</b> 미인증 공개 엔드포인트가 되기 때문이다.
     *
     * <p>검증 실패는 저장 실패가 아니다 — IP 화이트리스트 미등록처럼 키 자체는 맞는데 환경이
     * 안 맞는 경우가 흔하고, 그때 저장을 되돌리면 사용자가 IP 를 등록한 뒤 키를 처음부터 다시
     * 입력해야 한다. 저장은 하되 {@code isVerified=false} + 안내 문구로 알린다.
     */
    @Transactional
    public UpbitAccountResponse updateUpbitAccount(Long userId, UpdateUpbitAccountRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        UserUpbitAccount account = upbitAccountRepository.findByUserId(userId).orElse(null);
        boolean isNew = (account == null);

        String accessKey = trimToNull(request.getAccessKey());
        String secretKey = trimToNull(request.getSecretKey());

        if (isNew) {
            if (accessKey == null || secretKey == null) {
                throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE,
                        "업비트 계좌를 처음 등록할 때는 Access Key와 Secret Key가 모두 필요합니다");
            }
            account = UserUpbitAccount.builder().user(user).build();
        }

        if (secretKey != null) {
            // 짧은 키를 그대로 저장하면 주문·자산 조회가 WeakKeyException 으로 500 이 된다.
            // 사용자가 값을 고칠 수 있는 지금 400 으로 돌려주는 편이 낫다.
            UpbitAuthService.requireSignableSecretKey(secretKey);
        }

        if (accessKey != null) {
            account.setAccessKey(jasyptStringEncryptor.encrypt(accessKey));
        }
        if (secretKey != null) {
            account.setSecretKey(jasyptStringEncryptor.encrypt(secretKey));
        }

        // 자격증명이 하나라도 바뀌었으면 이전 검증 결과는 무효다.
        boolean credentialsChanged = (accessKey != null || secretKey != null);
        if (credentialsChanged) {
            account.setIsVerified(false);
        }

        account = upbitAccountRepository.save(account);

        String verificationNotice = null;
        if (credentialsChanged) {
            verificationNotice = verifyUpbitCredentials(userId, account);
            account = upbitAccountRepository.save(account);
        }

        return UpbitAccountResponse.builder()
                .id(account.getId())
                .registered(true)
                .accessKeyMasked(maskAccessKey(account.getAccessKey()))
                .secretKeyRegistered(true)
                .isVerified(account.getIsVerified())
                .verificationNotice(verificationNotice)
                .createdAt(account.getCreatedAt())
                .updatedAt(account.getUpdatedAt())
                .build();
    }

    /**
     * 저장된 키로 {@code GET /v1/accounts} 를 1회 호출한다(자금 이동 없음).
     *
     * @return 검증 실패 시 사용자에게 보여줄 안내 문구, 성공이면 null
     */
    private String verifyUpbitCredentials(Long userId, UserUpbitAccount account) {
        try {
            UpbitAuthService.UpbitCredentials credentials = upbitAuthService.getCredentials(userId);
            upbitApiClient.getAuthenticated("/v1/accounts", java.util.Map.of(),
                    credentials.accessKey(), credentials.secretKey(), java.util.Map[].class);
            account.setIsVerified(true);
            return null;
        } catch (BusinessException e) {
            log.warn("Upbit credential verification failed for userId={}: {}", userId, e.getMessage());
            account.setIsVerified(false);
            return e.getMessage();
        } catch (Exception e) {
            log.warn("Upbit credential verification failed for userId={}: {}", userId, e.toString());
            account.setIsVerified(false);
            return "업비트 키 검증에 실패했습니다. 키와 허용 IP 설정을 확인해 주세요.";
        }
    }

    /**
     * Access Key 앞 4자만 남기고 가린다. <b>복호화가 실패해도 예외를 던지지 않는다</b> —
     * 설정 화면이 죽으면 사용자가 잘못된 키를 고칠 방법 자체가 사라지기 때문이다.
     * (매매 경로는 반대로 {@link UpbitAuthService} 가 복호화 실패를 6004 로 확실히 끊는다.)
     */
    private String maskAccessKey(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        try {
            String plain = jasyptStringEncryptor.decrypt(encrypted);
            if (plain.length() <= 4) {
                return "****";
            }
            return plain.substring(0, 4) + "****";
        } catch (Exception e) {
            log.warn("업비트 Access Key 복호화 실패 — 재등록이 필요합니다. userId 기준 마스킹만 생략합니다.");
            return "****";
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
