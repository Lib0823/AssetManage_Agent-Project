package com.inbeom.apiserver.repository;

import com.inbeom.apiserver.domain.AssetDailySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AssetDailySnapshotRepository extends JpaRepository<AssetDailySnapshot, Long> {

    /**
     * 사용자의 특정 날짜 스냅샷 조회 (upsert 판별용).
     */
    Optional<AssetDailySnapshot> findByUserIdAndSnapshotDate(Long userId, LocalDate snapshotDate);

    /**
     * 사용자의 기간 내 스냅샷 목록 (날짜 오름차순 — 추이 차트용).
     */
    List<AssetDailySnapshot> findByUserIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            Long userId, LocalDate from, LocalDate to);
}
