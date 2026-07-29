#!/usr/bin/env bash
# database/schema.sql 재생성 — Liquibase가 적용한 라이브 DB 구조의 스냅샷.
#
# 스키마 소스(편집 대상)는 api-server/src/main/resources/db/changelog/ (Liquibase) 입니다.
# 이 스크립트는 그 결과가 적용된 PostgreSQL에서 schema.sql 을 뽑아냅니다.
#
# 사용법:
#   1) api-server를 한 번 띄워 Liquibase 마이그레이션을 적용 (docker compose up -d api-server)
#   2) ./database/generate-schema.sh
#
# 환경변수(기본값): POSTGRES_CONTAINER=financemanage-postgres, POSTGRES_DB=financemanage, POSTGRES_USER=admin
set -euo pipefail
cd "$(dirname "$0")"

CONTAINER="${POSTGRES_CONTAINER:-financemanage-postgres}"
DB="${POSTGRES_DB:-financemanage}"
DB_USER="${POSTGRES_USER:-admin}"
OUT="schema.sql"

cat > "$OUT" <<'HEADER'
-- ============================================================
-- AI 주식 자동매매 시스템 - Database Schema (AUTO-GENERATED)
-- PostgreSQL 16
--
-- ⚠️  이 파일은 자동 생성됩니다. 직접 편집하지 마세요.
--     스키마 소스: api-server/src/main/resources/db/changelog/ (Liquibase)
--     재생성:      ./database/generate-schema.sh
--     사람용 설명/ERD: database/README.md
-- ============================================================

HEADER

docker exec "$CONTAINER" pg_dump -s -U "$DB_USER" -d "$DB" \
  --no-owner --no-privileges \
  --exclude-table='databasechangelog*' \
  >> "$OUT"

echo "[generate-schema] wrote $OUT from ${CONTAINER}:${DB}"
