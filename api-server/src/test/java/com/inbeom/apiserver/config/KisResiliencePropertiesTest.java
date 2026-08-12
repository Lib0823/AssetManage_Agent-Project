package com.inbeom.apiserver.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 토큰 버킷 기본값이 <b>버스트 여유</b>를 갖는지 고정한다.
 *
 * <p>용량을 충전 속도와 같게 두면 한 화면이 내는 연속 호출(종목 상세 재무 탭 = 손익/재무비율/
 * 안정성 + 현재가 4연속)이 버킷을 그대로 비워, 뒤따르는 다른 사용자의 첫 조회가 곧바로 거부된다.
 * 지속 호출률은 refill 이 결정하므로 KIS 에 대한 부하 상한은 그대로다.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("KIS rate limit 기본값 — 버스트 여유")
class KisResiliencePropertiesTest {

    @Autowired
    private KisResilienceProperties properties;

    @Test
    @DisplayName("capacity 가 refill-per-second 보다 커서 짧은 호출 묶음을 흡수한다")
    void capacityLeavesBurstHeadroom() {
        KisResilienceProperties.RateLimit rateLimit = properties.getRateLimit();

        assertThat(rateLimit.getCapacity())
                .as("한 화면이 내는 4연속 호출 이후에도 다른 사용자의 첫 조회가 통과할 수 있어야 한다")
                .isGreaterThan((int) rateLimit.getRefillPerSecond());
    }
}
