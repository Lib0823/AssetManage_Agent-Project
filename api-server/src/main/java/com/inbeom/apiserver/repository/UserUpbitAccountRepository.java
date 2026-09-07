package com.inbeom.apiserver.repository;

import com.inbeom.apiserver.domain.UserUpbitAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserUpbitAccountRepository extends JpaRepository<UserUpbitAccount, Long> {

    Optional<UserUpbitAccount> findByUserId(Long userId);
}
