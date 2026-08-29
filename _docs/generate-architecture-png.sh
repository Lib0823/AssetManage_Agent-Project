#!/usr/bin/env bash
# ARCHITECTURE.svg -> ARCHITECTURE.png 재생성
#
# 소스는 ARCHITECTURE.svg 다. PNG 는 이 스크립트의 산출물이므로 직접 편집하지 말 것 —
# 손으로 고치면 다음 재생성에서 조용히 덮어써진다.
#
# 헤드리스 Chrome 을 쓰는 이유: rsvg-convert/resvg 는 한글 폰트 폴백이 불안정해
# 텍스트가 두부(□)로 렌더되는 경우가 있다. Chrome 은 시스템 폰트를 그대로 쓴다.
#
# 사용법:  ./generate-architecture-png.sh
set -euo pipefail

cd "$(dirname "$0")"

SVG="ARCHITECTURE.svg"
PNG="ARCHITECTURE.png"
# SVG 의 viewBox 와 반드시 일치해야 한다. 어긋나면 잘리거나 여백이 생긴다.
WIDTH=1600
HEIGHT=830
SCALE=2   # 2x = 3200x1660 (문서 확대 시에도 글자가 깨지지 않게)

CHROME="${CHROME_BIN:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
if [[ ! -x "$CHROME" ]]; then
  echo "[generate-architecture-png] Chrome 을 찾지 못했습니다: $CHROME" >&2
  echo "  CHROME_BIN 환경변수로 경로를 지정하세요." >&2
  exit 1
fi

TMP_HTML="_render_tmp.html"
cleanup() { rm -f "$TMP_HTML"; }
trap cleanup EXIT

# img 태그로 감싸 크기를 고정한다. SVG 를 직접 열면 Chrome 이 뷰포트에 맞춰 늘린다.
cat > "$TMP_HTML" <<HTML
<!doctype html><html><head><meta charset="utf-8">
<style>html,body{margin:0;padding:0;background:#fff}
img{display:block;width:${WIDTH}px;height:${HEIGHT}px}</style>
</head><body><img src="${SVG}"></body></html>
HTML

"$CHROME" --headless --disable-gpu --no-sandbox --hide-scrollbars \
  --force-device-scale-factor="$SCALE" \
  --window-size="${WIDTH},${HEIGHT}" \
  --default-background-color=FFFFFFFF \
  --screenshot="$PNG" "$TMP_HTML" 2>/dev/null

echo "[generate-architecture-png] wrote $PNG ($(( WIDTH * SCALE ))x$(( HEIGHT * SCALE )))"
