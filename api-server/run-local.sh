#!/usr/bin/env bash
# 환경변수는 저장소 최상위의 단일 .env 파일에서 읽는다 (Docker/로컬 공통 소스).
# 루트 .env가 없으면 api-server/.env로 폴백. (Spring의 DotenvEnvironmentPostProcessor도
# cwd에서 상위로 올라가며 .env를 찾으므로 루트 .env가 자동 적용된다.)
# Usage: ./run-local.sh   (pass extra gradle args after if needed)
set -euo pipefail
cd "$(dirname "$0")"

ENV_FILE=""
if [ -f ../.env ]; then
  ENV_FILE="../.env"
elif [ -f .env ]; then
  ENV_FILE=".env"
fi

if [ -n "$ENV_FILE" ]; then
  set -a
  . "$ENV_FILE"
  set +a
  echo "[run-local] loaded $ENV_FILE (DART_API_KEY set: $([ -n "${DART_API_KEY:-}" ] && echo yes || echo no), KIS_QUOTE_APP_KEY set: $([ -n "${KIS_QUOTE_APP_KEY:-}" ] && echo yes || echo no))"
else
  echo "[run-local] WARNING: .env not found — KIS quote / DART will be disabled"
fi
# 이전 실행이 남아 있으면 "Port 7070 was already in use" 로 막힌다. 먼저 비운다.
./free-port.sh "${SERVER_PORT:-7070}"

exec ./gradlew bootRun "$@"
