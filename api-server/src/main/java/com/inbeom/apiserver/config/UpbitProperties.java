package com.inbeom.apiserver.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 업비트 Open API 연동 설정 ({@code upbit.*}).
 *
 * <p>rate limit 수치를 상수가 아니라 설정으로 두는 이유는 {@link KisResilienceProperties} 와 같다:
 * 통합/단위 테스트에서 한도를 극단값으로 줄여야 "한도 초과 거부"를 현실적인 시간 안에 검증할 수 있고,
 * 업비트가 한도를 조정하면 재배포 없이 따라갈 수 있어야 한다.
 *
 * <p><b>기본값을 업비트 공식 한도보다 낮게 잡은 것은 의도적이다.</b> 토큰 버킷은 이 서버가 보내는
 * 요청만 세는데, 429 가 누적되면 업비트가 <b>418(일시 차단)</b> 로 올려버린다. 한도에 딱 맞추면
 * 시계 오차나 다중 인스턴스만으로도 418 을 밟는다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "upbit")
public class UpbitProperties {

    /** 한국 리전. 글로벌({@code sg|id|th})은 이 기능의 범위 밖이다. */
    private String baseUrl = "https://api.upbit.com";

    private final Quote quote = new Quote();
    private final Order order = new Order();

    /**
     * 시세(Quotation) API 한도. 업비트 공식 한도는 <b>10 req/s, IP 단위</b>다.
     *
     * <p>여기가 이 연동에서 가장 위험한 지점이다 — IP 단위라 <b>전체 사용자가 서버 공인 IP 하나의
     * 한도를 공유</b>한다. 한 사용자가 코인 목록·상세를 빠르게 오가면 다른 모든 사용자의 시세가
     * 굶는다. 그래서 버킷을 업비트의 카운트 단위와 같은 <b>그룹별</b>(ticker/orderbook/candle/market)
     * 로 나눈다 — 그렇게 하지 않으면 실제보다 과하게 조여 멀쩡한 호출까지 막는다.
     */
    @Getter
    @Setter
    public static class Quote {
        private int capacity = 10;
        private double refillPerSecond = 8.0;
    }

    /**
     * 주문 API 한도. 업비트 공식 한도는 <b>12 req/s, Pocket(계정) 단위</b>다.
     *
     * <p>Pocket 단위이므로 사용자끼리 간섭하지 않는다 — 버킷 키를 access_key 해시로 잡는 이유다.
     */
    @Getter
    @Setter
    public static class Order {
        private int capacity = 12;
        private double refillPerSecond = 10.0;
    }
}
