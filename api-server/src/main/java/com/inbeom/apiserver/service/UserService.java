package com.inbeom.apiserver.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.inbeom.apiserver.domain.User;
import com.inbeom.apiserver.domain.UserKisAccount;
import com.inbeom.apiserver.domain.UserSettings;
import com.inbeom.apiserver.domain.UserTradeConfig;
import com.inbeom.apiserver.dto.user.KisAccountResponse;
import com.inbeom.apiserver.dto.user.TradeConfigResponse;
import com.inbeom.apiserver.dto.user.UpdateKisAccountRequest;
import com.inbeom.apiserver.dto.user.UpdateTradeConfigRequest;
import com.inbeom.apiserver.dto.user.UpdateUserProfileRequest;
import com.inbeom.apiserver.dto.user.UpdateUserSettingsRequest;
import com.inbeom.apiserver.dto.user.UserProfileResponse;
import com.inbeom.apiserver.dto.user.UserSettingsResponse;
import com.inbeom.apiserver.exception.ErrorCode;
import com.inbeom.apiserver.exception.BusinessException;
import com.inbeom.apiserver.exception.UserNotFoundException;
import com.inbeom.apiserver.repository.RefreshTokenRepository;
import com.inbeom.apiserver.repository.UserKisAccountRepository;
import com.inbeom.apiserver.repository.UserRepository;
import com.inbeom.apiserver.repository.UserSettingsRepository;
import com.inbeom.apiserver.repository.UserTradeConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final ObjectMapper objectMapper;

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

        // 5. Trade history not stored in DB (fetched from KIS API directly)
        log.debug("Trade history is fetched from KIS API, no DB cleanup needed");

        // 6. Finally delete user
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
                .appKey(kisAccount.getAppKey())
                .appSecret(kisAccount.getAppSecret())
                .htsId(kisAccount.getHtsId())
                .accountMode(kisAccount.getAccountMode())
                .isVerified(kisAccount.getIsVerified())
                .createdAt(kisAccount.getCreatedAt())
                .updatedAt(kisAccount.getUpdatedAt())
                .build();
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
        kisAccount.setAppKey(request.getAppKey());
        kisAccount.setAppSecret(request.getAppSecret());
        // HTS ID (체결통보 tr_key) — 선택값. 제공된 경우에만 반영.
        if (request.getHtsId() != null) {
            kisAccount.setHtsId(request.getHtsId());
        }
        // KIS 모드(MOCK/REAL) — 제공된 경우에만 반영. 이후 이 계정의 모든 매매/조회가 해당 도메인으로 라우팅됨.
        if (request.getMode() != null && !request.getMode().isBlank()) {
            kisAccount.setAccountMode("REAL".equalsIgnoreCase(request.getMode()) ? "REAL" : "MOCK");
        }
        // Reset verification status when credentials are changed
        kisAccount.setIsVerified(false);

        kisAccount = kisAccountRepository.save(kisAccount);

        return KisAccountResponse.builder()
                .id(kisAccount.getId())
                .accountNumber(kisAccount.getAccountNumber())
                .accountProductCode(kisAccount.getAccountProductCode())
                .appKey(kisAccount.getAppKey())
                .appSecret(kisAccount.getAppSecret())
                .htsId(kisAccount.getHtsId())
                .accountMode(kisAccount.getAccountMode())
                .isVerified(kisAccount.getIsVerified())
                .createdAt(kisAccount.getCreatedAt())
                .updatedAt(kisAccount.getUpdatedAt())
                .build();
    }
}
