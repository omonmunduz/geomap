#!/usr/bin/env bash
# API smoke test for the Geoportal NSDI KR MVP — checks the "Expected results" from CLAUDE.md.
# Needs: bash, curl, jq. Run against a freshly seeded backend:
#   BASE=http://localhost:8080 scripts/smoke.sh
# Without local jq (via Docker, on the compose network):
#   docker run --rm --network geoportal-mvp_default -v "$PWD:/w" -w /w -e BASE=http://backend:8080 \
#     alpine:3.20 sh -c "apk add -q bash curl jq && bash scripts/smoke.sh"
set -uo pipefail

BASE="${BASE:-http://localhost:8080}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${OUT:-$(mktemp -d)}"
mkdir -p "$OUT"
PASS=0; FAIL=0

ok()   { echo "  PASS  $1"; PASS=$((PASS+1)); }
bad()  { echo "  FAIL  $1"; FAIL=$((FAIL+1)); }
check() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (expected '$3', got '$2')"; fi; }

# http METHOD PATH [curl args...] → body in $BODY, status in $CODE
http() {
  local m=$1 p=$2; shift 2
  CODE=$(curl -s -o "$OUT/body" -w '%{http_code}' -X "$m" "$BASE$p" "$@")
  BODY=$(cat "$OUT/body")
}
login() {
  http POST /api/auth/dev-login -H 'Content-Type: application/json' -d "{\"subject\":\"$1\"}"
  echo "$BODY" | jq -r .token
}

echo "== Catalog / search (anonymous)"
http GET /api/catalog/tree
check "catalog tree: 7 themes" "$(echo "$BODY" | jq length)" 7
check "catalog tree: 34 layers" "$(echo "$BODY" | jq '[.[].layers[]] | length')" 34
http GET "/api/search?q=$(printf 'бишк' | jq -sRr @uri)"
check "search 'бишк' → first title" "$(echo "$BODY" | jq -r '.items[0].title')" "Бишкек"
check "search 'бишк' → first layer" "$(echo "$BODY" | jq -r '.items[0].layerTitle')" "Населённые пункты"

echo "== Auth"
http POST /api/auth/dev-login -H 'Content-Type: application/json' -d '{"subject":"infocom:former"}'
check "dev-login infocom:former → 403" "$CODE" 403
ADMIN=$(login infocom:admin)
[ -n "$ADMIN" ] && [ "$ADMIN" != null ] && ok "login infocom:admin" || bad "login infocom:admin"
A=(-H "Authorization: Bearer $ADMIN")
http GET "/api/admin/audit?outcome=denied" "${A[@]}"
check "audit has a 'denied' event" "$(echo "$BODY" | jq '[.[] | select(.outcome=="denied")] | length > 0')" true
http GET /api/my/layers "${A[@]}"
check "GET /api/my/layers → 200" "$CODE" 200

echo "== Zoning"
http GET /api/zoning/discrepancies "${A[@]}"
check "discrepancies → 29" "$(echo "$BODY" | jq length)" 29
DID=$(echo "$BODY" | jq -r '[.[] | select(.status=="new")][0].id')
http POST "/api/zoning/discrepancies/$DID/status" "${A[@]}" -F status=resolved
check "status=resolved without comment → 400" "$CODE" 400
http POST "/api/zoning/discrepancies/$DID/status" "${A[@]}" -F status=resolved -F "comment=Устранено по акту №1 (smoke)"
check "status=resolved with comment → 200" "$CODE" 200
for f in xlsx pdf; do
  CODE=$(curl -s -o "$OUT/report.$f" -w '%{http_code}' "${A[@]}" "$BASE/api/zoning/report?format=$f")
  SIZE=$(wc -c < "$OUT/report.$f")
  [ "$CODE" = 200 ] && [ "$SIZE" -gt 0 ] && ok "report $f ($SIZE bytes) → $OUT/report.$f" || bad "report $f (HTTP $CODE, $SIZE bytes)"
done

echo "== Import: hospitals_kyrg06_zone3.zip → «health» (Динара Мамытова)"
HEALTH=$(login infocom:minzdrav)
H=(-H "Authorization: Bearer $HEALTH")
http GET /api/my/layers "${H[@]}"
HLAYER=$(echo "$BODY" | jq -r '.[] | select(.code=="health") | .id')
http POST "/api/imports?layerId=$HLAYER" "${H[@]}" -F "file=@$ROOT/demo-data/samples/hospitals_kyrg06_zone3.zip"
check "upload → detectedSrid 7694" "$(echo "$BODY" | jq -r .detectedSrid)" 7694
JOB=$(echo "$BODY" | jq -r .jobId)
REQ=$(echo "$BODY" | jq -c '{sourceSrid: 7694, mapping: .suggestedMapping, titleField: .suggestedTitleField, makeValid: false}')
http POST "/api/imports/$JOB/validate" "${H[@]}" -H 'Content-Type: application/json' -d "$REQ"
check "validate → blocking=true" "$(echo "$BODY" | jq -r .blocking)" true
check "validate → 1 error" "$(echo "$BODY" | jq '.errors | length')" 1
check "validate → error text" "$(echo "$BODY" | jq -r '"Объект №\(.errors[0].fid): \(.errors[0].message)"')" "Объект №3: не заполнено обязательное поле «Наименование»"

echo "== Import: green_zones_with_error.geojson → new layer → publish (admin)"
THEME=$(curl -s "$BASE/api/catalog/themes" | jq -r '.[0].code')
http POST /api/layers "${A[@]}" -H 'Content-Type: application/json' \
  -d "{\"titleRu\":\"Зелёные зоны (smoke)\",\"geomType\":\"polygon\",\"themeCode\":\"$THEME\",\"accessLevel\":\"open\"}"
check "create layer → 200" "$CODE" 200
NL=$(echo "$BODY" | jq -r .id)
http POST "/api/imports?layerId=$NL" "${A[@]}" -F "file=@$ROOT/demo-data/samples/green_zones_with_error.geojson"
JOB=$(echo "$BODY" | jq -r .jobId)
REQ=$(echo "$BODY" | jq -c '{sourceSrid: (.detectedSrid // 4326), mapping: .suggestedMapping, titleField: .suggestedTitleField}')
http POST "/api/imports/$JOB/validate" "${A[@]}" -H 'Content-Type: application/json' -d "$(echo "$REQ" | jq -c '.makeValid=false')"
check "validate without makeValid → blocking" "$(echo "$BODY" | jq -r .blocking)" true
echo "        error: $(echo "$BODY" | jq -r '[.errors[].message, .globalErrors[]] | first')"
http POST "/api/imports/$JOB/validate" "${A[@]}" -H 'Content-Type: application/json' -d "$(echo "$REQ" | jq -c '.makeValid=true')"
check "validate with makeValid → not blocking" "$(echo "$BODY" | jq -r .blocking)" false
check "validate with makeValid → 2 valid" "$(echo "$BODY" | jq -r .valid)" 2
http POST "/api/imports/$JOB/commit" "${A[@]}"
check "commit → 200" "$CODE" 200
for i in $(seq 1 60); do
  http GET "/api/imports/$JOB" "${A[@]}"
  ST=$(echo "$BODY" | jq -r .status)
  [ "$ST" = done ] || [ "$ST" = failed ] && break
  sleep 1
done
check "import job → done" "$ST" done
http POST "/api/layers/$NL/publish" "${A[@]}"
check "publish → 200" "$CODE" 200
http GET /api/catalog/tree
check "new layer visible anonymously" "$(echo "$BODY" | jq --arg id "$NL" '[.[].layers[] | select(.id==$id)] | length')" 1

echo "== Vector tiles"
OBL=$(echo "$BODY" | jq -r '[.[].layers[] | select(.code=="atd_oblasts")][0].id')
CODE=$(curl -s -o "$OUT/tile.pbf" -w '%{http_code}' "$BASE/api/tiles/$OBL/5/22/11.pbf")
SIZE=$(wc -c < "$OUT/tile.pbf")
[ "$CODE" = 200 ] && [ "$SIZE" -gt 0 ] && ok "tile atd_oblasts 5/22/11 ($SIZE bytes)" || bad "tile (HTTP $CODE, $SIZE bytes)"
CODE=$(curl -s -o "$OUT/tile2.pbf" -w '%{http_code}' "$BASE/api/tiles/$NL/11/1448/753.pbf")
SIZE=$(wc -c < "$OUT/tile2.pbf")
[ "$CODE" = 200 ] && [ "$SIZE" -gt 0 ] && ok "tile new layer 11/1448/753 ($SIZE bytes)" || bad "tile new layer (HTTP $CODE, $SIZE bytes)"

echo
echo "Result: $PASS passed, $FAIL failed (artifacts in $OUT)"
[ "$FAIL" -eq 0 ]
