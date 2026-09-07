#!/usr/bin/env bash
# 서버 포트를 잡고 있는 프로세스를 정리한다 (기본 7070).
#
# "Port 7070 was already in use" 로 기동이 막히는 상황을 자동으로 푸는 용도다.
# 실제로 겪은 사례: 이전 bootRun 이 IDE 종료 후에도 살아남거나, 테스트용으로 띄운
# 엉뚱한 프로세스(node mock 서버 등)가 같은 포트를 선점하고 있었다.
#
# **무엇을 죽였는지 반드시 출력한다.** 조용히 kill 하면, 정작 죽으면 안 되는 것을
# 죽였을 때 원인을 찾을 수 없다.
#
# Docker 가 포트를 publish 중이면 죽이지 않고 안내만 한다 — 그 경우 프로세스를 kill 해도
# 컨테이너가 살아 있어 포트가 다시 잡히고, docker 데몬을 건드리면 다른 컨테이너까지 죽는다.
#
# Usage:
#   ./free-port.sh          # 7070
#   ./free-port.sh 8080     # 임의 포트
set -euo pipefail

PORT="${1:-7070}"

pids_on_port() {
  lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null | sort -u
}

PIDS="$(pids_on_port || true)"
if [ -z "$PIDS" ]; then
  echo "[free-port] :$PORT 비어 있음"
  exit 0
fi

# Docker 가 잡고 있으면 kill 하지 않는다.
for pid in $PIDS; do
  CMD="$(ps -p "$pid" -o comm= 2>/dev/null || true)"
  case "$CMD" in
    *docker*|*com.docker*)
      echo "[free-port] :$PORT 을 Docker 가 사용 중입니다 (pid $pid, $CMD)."
      echo "[free-port] 프로세스를 죽이지 않습니다 — 컨테이너를 내리세요:"
      echo "              docker compose stop api-server"
      exit 1
      ;;
  esac
done

for pid in $PIDS; do
  echo "[free-port] :$PORT 점유 → pid $pid"
  ps -p "$pid" -o pid,lstart,command 2>/dev/null | tail -n +2 | sed 's/^/            /'
done

# 먼저 정상 종료를 시도한다(shutdown hook 이 돌 기회를 준다).
for pid in $PIDS; do kill "$pid" 2>/dev/null || true; done

for _ in 1 2 3 4 5 6 7 8 9 10; do
  sleep 0.3
  [ -z "$(pids_on_port || true)" ] && break
done

# 그래도 남아 있으면 강제 종료.
REMAINING="$(pids_on_port || true)"
if [ -n "$REMAINING" ]; then
  echo "[free-port] 정상 종료에 응답하지 않아 SIGKILL 합니다: $REMAINING"
  for pid in $REMAINING; do kill -9 "$pid" 2>/dev/null || true; done
  sleep 0.5
fi

if [ -n "$(pids_on_port || true)" ]; then
  echo "[free-port] :$PORT 를 비우지 못했습니다. 위 프로세스를 직접 확인하세요." >&2
  exit 1
fi

echo "[free-port] :$PORT 정리 완료"
