package com.inbeom.apiserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스프링 컨텍스트 기동(빈 와이어링 / 엔티티 매핑) 검증.
 *
 * <p>{@code @ActiveProfiles("test")} 로 {@code src/test/resources/application-test.yml} 을 얹어
 * H2 인메모리 DB + Liquibase 비활성으로 돌린다. 이게 없으면 운영 application.yml 의
 * {@code localhost:5432} 에 직결되어, 로컬에 PostgreSQL 이 없으면 코드와 무관하게 실패한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApiServerApplicationTests {

    @Autowired
    private DataSource dataSource;

    @Test
    void contextLoads() {
    }

    /**
     * 테스트 프로파일이 실제로 인메모리 DB 를 물고 있는지 검증한다.
     *
     * <p>개발자 로컬에는 PostgreSQL 이 떠 있는 경우가 많아, {@code contextLoads()} 가 통과해도
     * 그게 H2 덕분인지 그냥 로컬 DB 에 붙어서인지 구분할 수 없다. 이 테스트가 없으면
     * {@code application-test.yml} 이 조용히 무력화돼도(프로파일 오타 등) 아무도 눈치채지 못하고,
     * CI 처럼 DB 가 없는 환경에서만 뒤늦게 깨진다.
     */
    @Test
    void usesInMemoryDatabaseNotLocalPostgres() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:");
        }
    }

}
