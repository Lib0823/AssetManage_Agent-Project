package com.inbeom.apiserver.repository;

import com.inbeom.apiserver.domain.UserFavorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserFavoriteRepository extends JpaRepository<UserFavorite, Long> {

    /**
     * 사용자의 관심 종목 목록 (등록 순).
     */
    List<UserFavorite> findByUserId(Long userId);

    /**
     * 사용자의 특정 종목 관심 등록 여부 조회 (멱등 처리용).
     */
    Optional<UserFavorite> findByUserIdAndStockCode(Long userId, String stockCode);

    /**
     * 사용자의 특정 관심 종목 삭제.
     */
    void deleteByUserIdAndStockCode(Long userId, String stockCode);
}
