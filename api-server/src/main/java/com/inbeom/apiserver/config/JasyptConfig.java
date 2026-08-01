package com.inbeom.apiserver.config;

import com.ulisesbocchio.jasyptspringboot.annotation.EnableEncryptableProperties;
import org.jasypt.encryption.StringEncryptor;
import org.jasypt.encryption.pbe.PooledPBEStringEncryptor;
import org.jasypt.encryption.pbe.config.SimpleStringPBEConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableEncryptableProperties
public class JasyptConfig {

    @Value("${jasypt.encryptor.password}")
    private String encryptorPassword;

    @Bean("jasyptStringEncryptor")
    public StringEncryptor stringEncryptor() {
        if (encryptorPassword == null || encryptorPassword.isBlank()) {
            throw new IllegalStateException(
                    "JASYPT_PASSWORD 환경변수가 설정되지 않았습니다. KIS 자격증명 암호화 키를 주입하세요.");
        }

        PooledPBEStringEncryptor encryptor = new PooledPBEStringEncryptor();
        SimpleStringPBEConfig config = new SimpleStringPBEConfig();

        config.setPassword(encryptorPassword);
        config.setAlgorithm("PBEWITHHMACSHA512ANDAES_256"); // Upgraded from MD5+DES to SHA512+AES256
        config.setKeyObtentionIterations("1000");
        config.setPoolSize("1");
        config.setProviderName("SunJCE");
        config.setSaltGeneratorClassName("org.jasypt.salt.RandomSaltGenerator");
        config.setIvGeneratorClassName("org.jasypt.iv.RandomIvGenerator"); // Changed from NoIvGenerator
        config.setStringOutputType("base64");

        encryptor.setConfig(config);
        return encryptor;
    }
}
