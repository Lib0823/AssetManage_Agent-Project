package com.inbeom.apiserver.repository;

import com.inbeom.apiserver.domain.User;
import com.inbeom.apiserver.domain.UserTradeConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserTradeConfigRepository extends JpaRepository<UserTradeConfig, Long> {

    Optional<UserTradeConfig> findByUser(User user);

    Optional<UserTradeConfig> findByUserId(Long userId);

    /**
     * 자동매매가 켜진 사용자 설정 전체 (멀티유저 파이프라인 사용자 루프 대상).
     */
    List<UserTradeConfig> findByIsActiveTrue();
}
