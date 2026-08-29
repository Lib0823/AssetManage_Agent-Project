package com.inbeom.apiserver;

import com.inbeom.apiserver.config.KisBondProperties;
import com.inbeom.apiserver.config.KisResilienceProperties;
import com.inbeom.apiserver.config.UpbitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({KisResilienceProperties.class, KisBondProperties.class, UpbitProperties.class})
public class ApiServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiServerApplication.class, args);
    }

}
