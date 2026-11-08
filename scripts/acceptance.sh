#!/usr/bin/env bash
# API acceptance run for the FR control scenarios (ЧТЗ Appendix З) that the MVP touches.
# Prints an evidence log: every request with its HTTP status and a short response excerpt,
# and a CHECK line per assertion. Run on a FRESH database (docker compose down -v && up):
#   docker run --rm --network geoportal-mvp_default -v "$PWD:/w" -v "<dir with format samples>:/fmt" -w /w \
#     -e BASE=http://backend:8080 alpine:3.20 sh -c "apk add -q bash curl jq && bash scripts/acceptance.sh"
# /fmt holds settlements.{gpkg,kml,kmz,csv,gml}, settlements_tab.zip, settlements_mif.zip, settlements.gdb.zip
# (generated from demo-data/settlements.geojson with ogr2ogr; see docs/acceptance/mvp-verification.md).
set -uo pipefail
IFS=$'\n\t'   # so that $(json …) and other substitutions stay one argument
BASE="${BASE:-http://localhost:8080}"
FMT="${FMT:-/fmt}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${OUT:-$(mktemp -d)}"; mkdir -p "$OUT"
PASS=0; FAIL=0

say()  { echo "$@"; }
fr()   { echo; echo "### $1"; }
check() { # check "description" <actual> <expected>
  if [ "$2" = "$3" ]; then echo "CHECK PASS  $1"; PASS=$((PASS+1)); else echo "CHECK FAIL  $1 (expected '$3', got '$2')"; FAIL=$((FAIL+1)); fi; }
token() { curl -s -X POST "$BASE/api/auth/dev-login" -H 'Content-Type: application/json' -d "{\"subject\":\"$1\"}" | jq -r .token; }
# call ROLE METHOD PATH [curl args…] → $CODE, $BODY
call() {
  local role=$1 m=$2 p=$3; shift 3
  local auth=(); case $role in anon) ;; *) auth=(-H "Authorization: Bearer ${TOK[$role]}");; esac
  if [ $# -gt 0 ] && [[ "$1" == [{[]* ]]; then local body=$1; shift; set -- -H 'Content-Type: application/json' -d "$body" "$@"; fi
  CODE=$(curl -s -o "$OUT/body" -w '%{http_code}' -X "$m" "$BASE$p" "${auth[@]}" "$@")
  BODY=$(cat "$OUT/body")
  echo "\$ curl -X $m '$p' [$role] → HTTP $CODE"
}
show() { echo "  ↳ $(echo "$BODY" | jq -c "$1" 2>/dev/null | cut -c1-400)"; }
json() { printf '%s' "$1"; }   # JSON body for call(); IFS below keeps it one word
jq_uri() { printf '%s' "$1" | jq -sRr @uri; }

declare -A TOK
TOK[citizen]=$(token tunduk:citizen)      # authorized (Түндүк, гражданин)
TOK[staff]=$(token infocom:minzdrav)      # staff (Минздрав)
TOK[staff2]=$(token infocom:minarch)      # staff (Минархстрой)
TOK[mchs]=$(token infocom:mchs)           # staff (МЧС, оперативные слои)
TOK[admin]=$(token infocom:admin)         # admin
for r in citizen staff staff2 mchs admin; do [ -n "${TOK[$r]}" ] && [ "${TOK[$r]}" != null ] && say "token $r: ok" || say "token $r: MISSING"; done

THEME=$(curl -s "$BASE/api/catalog/themes" | jq -r '.[0].code')
TREE_ADMIN=$(curl -s -H "Authorization: Bearer ${TOK[admin]}" "$BASE/api/catalog/tree")
lid() { echo "$TREE_ADMIN" | jq -r --arg c "$1" '[.[].layers[] | select(.code==$c)][0].id'; }
newlayer() { # newlayer ROLE title geomType access → id
  call "$1" POST /api/layers $(json "{\"titleRu\":\"$2\",\"geomType\":\"$3\",\"themeCode\":\"$THEME\",\"accessLevel\":\"$4\"}") >/dev/null
  echo "$BODY" | jq -r .id; }

# ---------------------------------------------------------------------------------------------
fr "PHASE 1 — catalog and roles"
call anon GET /api/catalog/tree; show '{themes: length, layers: ([.[].layers[]] | length)}'
check "anon: 7 themes" "$(echo "$BODY" | jq length)" 7
check "anon: 34 layers" "$(echo "$BODY" | jq '[.[].layers[]] | length')" 34

# ---------------------------------------------------------------------------------------------
fr "FR-DAT-01 / FR-GIS-03 — point, line, polygon: create, read, edit vertices, move, delete"
LP=$(newlayer admin "АТ точки" point open); LL=$(newlayer admin "АТ линии" line open); LG=$(newlayer admin "АТ полигоны" polygon open)
say "created layers point=$LP line=$LL polygon=$LG"
call admin POST "/api/layers/$LP/features" $(json '{"geometry":{"type":"Point","coordinates":[74.6,42.87]},"attrs":{"name":"Точка АТ"}}')
FP=$(echo "$BODY" | jq -r .id); show '{id, geometryType, title, lon, lat}'
check "point saved" "$(echo "$BODY" | jq -r .geometryType)" POINT
call admin POST "/api/layers/$LL/features" $(json '{"geometry":{"type":"LineString","coordinates":[[74.58,42.86],[74.62,42.88]]},"attrs":{"name":"Линия АТ"}}')
FL=$(echo "$BODY" | jq -r .id); show '{geometryType, lengthM}'
check "line saved" "$(echo "$BODY" | jq -r .geometryType)" LINESTRING
call admin POST "/api/layers/$LG/features" $(json '{"geometry":{"type":"Polygon","coordinates":[[[74.58,42.86],[74.60,42.86],[74.60,42.88],[74.58,42.86]]]},"attrs":{"name":"Полигон АТ"}}')
FG=$(echo "$BODY" | jq -r .id); show '{geometryType, areaM2}'
check "polygon saved" "$(echo "$BODY" | jq -r .geometryType)" POLYGON
call admin GET "/api/features/$FG"; show '.geometry'
check "polygon read back from DB (4 vertices)" "$(echo "$BODY" | jq '.geometry.coordinates[0] | length')" 4
call admin PUT "/api/features/$FG" $(json '{"geometry":{"type":"Polygon","coordinates":[[[74.58,42.86],[74.60,42.86],[74.61,42.87],[74.60,42.88],[74.58,42.86]]]}}')
check "polygon: vertex added (5 vertices)" "$(echo "$BODY" | jq '.geometry.coordinates[0] | length')" 5
call admin PUT "/api/features/$FP" $(json '{"geometry":{"type":"Point","coordinates":[74.61,42.875]}}'); show '{lon, lat}'
check "point moved" "$(echo "$BODY" | jq -r .lon)" 74.61
call admin POST "/api/layers/$LP/features" $(json '{"geometry":{"type":"LineString","coordinates":[[74.58,42.86],[74.62,42.88]]}}'); show .
check "wrong geometry type rejected" "$CODE" 400
call admin POST "/api/layers/$LP/features" $(json '{"geometry":{"type":"Point","coordinates":[10,50]}}'); show .
check "point outside KR rejected" "$CODE" 400
call admin DELETE "/api/features/$FL"; show .
check "line deleted" "$CODE" 200
call admin GET "/api/features/$FL"
check "deleted feature no longer readable" "$CODE" 403
call admin GET "/api/catalog/layers/$LG?" ; show '{draftVersion, publishedVersion, status}'

# ---------------------------------------------------------------------------------------------
fr "FR-DAT-08 — CRUD with audit and rights; links"
call staff PUT "/api/features/$FP" $(json '{"attrs":{"name":"чужой"}}'); show .
check "staff of another org cannot edit (403)" "$CODE" 403
call citizen POST "/api/layers/$LP/features" $(json '{"geometry":{"type":"Point","coordinates":[74.6,42.87]}}')
check "citizen cannot create (403)" "$CODE" 403
call anon DELETE "/api/features/$FP"
check "anonymous cannot delete (401)" "$CODE" 401
call admin PUT "/api/features/$FP" $(json '{"attrs":{"name":"Точка АТ (изменена)"}}'); show '{title}'
call admin GET "/api/features/$FP/history"; show '[.[] | {op, author, at}]'
check "history has create + 2 updates" "$(echo "$BODY" | jq length)" 3
call admin GET "/api/admin/audit?limit=50"; show '[.[] | select(.action|startswith("feature_")) | {action, userName, objectId}] | .[0:4]'
check "audit has feature_create/update/delete" "$(echo "$BODY" | jq '[.[] | .action] | (index("feature_create")!=null and index("feature_update")!=null and index("feature_delete")!=null)')" true
call admin GET "/api/features/$FP/links"
say "  links endpoint: HTTP $CODE (no relation model in schema)"

# ---------------------------------------------------------------------------------------------
fr "FR-DAT-03 — attachments (image + scan) on a zoning discrepancy; on an ordinary feature"
printf '\x89PNG\r\n\x1a\n%s' "fake-image-for-acceptance" > "$OUT/photo.png"
printf '%%PDF-1.4\n%% scan for acceptance\n%%%%EOF\n' > "$OUT/scan.pdf"
call admin GET "/api/zoning/discrepancies"; DID=$(echo "$BODY" | jq -r '.[0].id')
call admin POST "/api/zoning/discrepancies/$DID/status" -F status=in_review -F "comment=Фото с выезда" -F "file=@$OUT/photo.png;type=image/png"
check "attach image → 200" "$CODE" 200
call admin POST "/api/zoning/discrepancies/$DID/status" -F status=in_review -F "comment=Скан акта" -F "file=@$OUT/scan.pdf;type=application/pdf"
check "attach scan → 200" "$CODE" 200
call admin GET "/api/zoning/discrepancies/$DID"; show '[.history[] | {id, toStatus, comment, attachmentName}]'
for name in photo.png scan.pdf; do
  HID=$(echo "$BODY" | jq -r --arg n "$name" '[.history[] | select(.attachmentName==$n)][0].id')
  CODE=$(curl -s -o "$OUT/dl_$name" -w '%{http_code}' -H "Authorization: Bearer ${TOK[admin]}" "$BASE/api/zoning/discrepancies/$DID/attachments/$HID")
  echo "\$ curl '/api/zoning/discrepancies/$DID/attachments/$HID' [admin] → HTTP $CODE"
  check "download $name identical" "$(cmp -s "$OUT/$name" "$OUT/dl_$name" && echo same || echo different)" same
done
call admin POST "/api/features/$FP/attachments" -F "file=@$OUT/photo.png"
say "  feature attachment endpoint: HTTP $CODE"
check "no attachments for ordinary features (gap confirmed)" "$([ "$CODE" = 404 ] || [ "$CODE" = 405 ] && echo absent || echo present)" absent

# ---------------------------------------------------------------------------------------------
fr "FR-GIS-07 / FR-DAT-04 — import every supported format via the API (batch upload)"
LM=$(newlayer admin "АТ форматы" point open); say "target layer $LM"
for f in "$ROOT/demo-data/samples/hospitals_kyrg06_zone3.zip" $FMT/settlements.gpkg $FMT/settlements.kml $FMT/settlements.kmz $FMT/settlements_tab.zip \
         $FMT/settlements_mif.zip $FMT/settlements.gdb.zip $FMT/settlements.csv $FMT/settlements.gml "$ROOT/demo-data/samples/green_zones_with_error.geojson"; do
  [ -f "$f" ] || { say "  missing sample $f"; continue; }
  call admin POST "/api/imports?layerId=$LM" -F "file=@$f"; show '{fileName, ogrLayer, featureCount, geometryType, detectedSrid, fields: (.fields|length)} + (if .error then {error} else {} end)'
  n=$(basename "$f"); [ "$CODE" = 200 ] && check "upload $n" ok ok || check "upload $n" "HTTP $CODE" ok
done

fr "FR-GIS-07 / FR-TER-01 — Kyrg-06 zone 3 reprojection: hospitals into a layer without required fields"
LH=$(newlayer admin "АТ больницы Kyrg-06" point open)
call admin POST "/api/imports?layerId=$LH" -F "file=@$ROOT/demo-data/samples/hospitals_kyrg06_zone3.zip"; show '{detectedSrid, detectedSrsName, featureCount}'
JOB=$(echo "$BODY" | jq -r .jobId)
call admin POST "/api/imports/$JOB/validate" $(json '{"sourceSrid":7694,"mapping":{},"titleField":null,"makeValid":false}'); show '{blocking, total, valid, bbox}'
check "hospitals (no required fields) validate OK" "$(echo "$BODY" | jq -r .blocking)" false
call admin POST "/api/imports/$JOB/commit"
for i in $(seq 1 30); do call admin GET "/api/imports/$JOB" >/dev/null; [ "$(echo "$BODY" | jq -r .status)" = done ] && break; sleep 1; done
check "hospitals import done" "$(echo "$BODY" | jq -r .status)" done
call admin GET "/api/catalog/layers/$LH"; show '.bbox'
check "reprojected bbox lies in Bishkek (lon 74.4–74.8)" "$(echo "$BODY" | jq '.bbox[0] > 74.4 and .bbox[2] < 74.8 and .bbox[1] > 42.7 and .bbox[3] < 43.0')" true

fr "FR-DAT-12 / FR-GIS-07 — defective data: missing required field, self-intersection, errors CSV"
call staff GET /api/my/layers; HL=$(echo "$BODY" | jq -r '.[] | select(.code=="health") | .id')
call staff POST "/api/imports?layerId=$HL" -F "file=@$ROOT/demo-data/samples/hospitals_kyrg06_zone3.zip"; JOB=$(echo "$BODY" | jq -r .jobId)
REQ=$(echo "$BODY" | jq -c '{sourceSrid: 7694, mapping: .suggestedMapping, titleField: .suggestedTitleField, makeValid: false}')
call staff POST "/api/imports/$JOB/validate" $(json "$REQ"); show '{blocking, total, valid, errors}'
check "missing «Наименование» → blocking" "$(echo "$BODY" | jq -r .blocking)" true
call staff POST "/api/imports/$JOB/commit"; show .
check "commit of invalid import refused (409)" "$CODE" 409
call staff GET "/api/imports/$JOB"; show '{status}'
LZ=$(newlayer admin "АТ зелёные зоны" polygon open)
call admin POST "/api/imports?layerId=$LZ" -F "file=@$ROOT/demo-data/samples/green_zones_with_error.geojson"; JOB=$(echo "$BODY" | jq -r .jobId)
REQ=$(echo "$BODY" | jq -c '{sourceSrid: (.detectedSrid // 4326), mapping: .suggestedMapping, titleField: .suggestedTitleField}')
call admin POST "/api/imports/$JOB/validate" $(json "$(echo "$REQ" | jq -c '.makeValid=false')"); show '{blocking, total, valid, errors}'
check "self-intersection → blocking" "$(echo "$BODY" | jq -r .blocking)" true
CODE=$(curl -s -o "$OUT/errors.csv" -w '%{http_code}' -H "Authorization: Bearer ${TOK[admin]}" "$BASE/api/imports/$JOB/errors.csv")
echo "\$ curl '/api/imports/$JOB/errors.csv' [admin] → HTTP $CODE"; echo "  ↳ $(tr '\n' '|' < "$OUT/errors.csv" | cut -c1-300)"
check "errors CSV produced" "$(grep -c самопересечение "$OUT/errors.csv")" 1
call admin POST "/api/imports/$JOB/validate" $(json "$(echo "$REQ" | jq -c '.makeValid=true')"); show '{blocking, total, valid, warnings}'
check "with makeValid: not blocking, 2 valid" "$(echo "$BODY" | jq -c '[.blocking, .valid]')" '[false,2]'
call admin POST "/api/imports/$JOB/commit"
for i in $(seq 1 30); do call admin GET "/api/imports/$JOB" >/dev/null; [ "$(echo "$BODY" | jq -r .status)" = done ] && break; sleep 1; done
call anon GET /api/catalog/tree
check "imported draft NOT public before publish" "$(echo "$BODY" | jq --arg id "$LZ" '[.[].layers[] | select(.id==$id) | .publishedVersion] | .[0]')" null
call admin POST "/api/layers/$LZ/publish"; show '{status, publishedVersion}'
check "published after explicit action" "$(echo "$BODY" | jq -r .publishedVersion)" 1
say "  job statuses allowed by schema: uploaded, validated, invalid, queued, running, done, failed, cancelled (no 'quarantine')"

# ---------------------------------------------------------------------------------------------
fr "FR-DAT-05 — registries: layer list and discrepancy registry with search/filters"
call staff GET /api/my/layers; show '[.[] | {titleRu, status, lastImport: .lastImport.status}] | .[0:3]'
check "staff sees only own-org layers" "$(echo "$BODY" | jq '[.[].owner] | unique | length')" 1
call admin GET "/api/zoning/discrepancies?type=NO_ZONE"; show '[.[] | {objectTitle, type, status}]'
check "filter type=NO_ZONE → 3" "$(echo "$BODY" | jq length)" 3
call admin GET "/api/zoning/discrepancies?status=in_review"; show '[.[] | {objectTitle, status}]'
check "filter status=in_review → 1" "$(echo "$BODY" | jq length)" 1

# ---------------------------------------------------------------------------------------------
fr "FR-DAT-10 — metadata"
SET=$(lid settlements)
call anon GET "/api/catalog/layers/$SET"; show '.metadata'
check "metadata has abstract/updateFreq/contact" "$(echo "$BODY" | jq '.metadata | has("abstract") and has("updateFreq") and has("contact")')" true
call admin PATCH "/api/layers/$SET" $(json '{"description":"Обновлённая аннотация (АТ)"}'); show '{description}'
say "  no metadata editing endpoint (ISO 19115 elements: identification, extent, quality, distribution…) — only description/titles/access/colour"

# ---------------------------------------------------------------------------------------------
fr "FR-GIS-06 — internal layer via MVT; external WFS / WFS-T"
OBL=$(lid atd_oblasts)
CODE=$(curl -s -o "$OUT/t.pbf" -w '%{http_code}' "$BASE/api/tiles/$OBL/5/22/11.pbf"); echo "\$ curl '/api/tiles/$OBL/5/22/11.pbf' [anon] → HTTP $CODE, $(wc -c < "$OUT/t.pbf") bytes"
check "internal MVT tile non-empty" "$([ -s "$OUT/t.pbf" ] && echo y)" y
for p in "/wfs?service=WFS&request=GetCapabilities" "/api/wfs?service=WFS&request=GetCapabilities" "/geoserver/wfs"; do call anon GET "$p"; done
say "  no WFS client/server and no external-layer registration endpoint in the code"

# ---------------------------------------------------------------------------------------------
fr "FR-SRCH-01 — full-text search, ru/ky corpus"
for q in "бишк" "Бишкек" "Бишкеке" "чуй" "Чүй" "ОБ-0001" "Ош" "Каракол" "Нарын" "Tokmak" "кадастр" "Бишкек шаары"; do
  call anon GET "/api/search?q=$(jq_uri "$q")&limit=3"; show "{q: \"$q\", total, top: [.items[] | .title + \" / \" + .layerTitle]}"
done

fr "FR-SRCH-02 — spatial search: rectangle, polygon contour, results inside"
SET=$(lid settlements); DEV=$(lid current_development)
call anon POST /api/search/area $(json "{\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[74.5,42.8],[74.7,42.8],[74.7,42.95],[74.5,42.95],[74.5,42.8]]]},\"layers\":[\"$SET\",\"$DEV\"],\"limit\":500}"); show '{total, first: .items[0].title}'
check "rectangle: all results inside" "$(echo "$BODY" | jq '[.items[] | select(.bbox[2] < 74.5 or .bbox[0] > 74.7 or .bbox[3] < 42.8 or .bbox[1] > 42.95)] | length')" 0
RECT=$(echo "$BODY" | jq .total)
call anon POST /api/search/area $(json "{\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[74.5,42.8],[74.7,42.8],[74.5,42.95],[74.5,42.8]]]},\"layers\":[\"$DEV\"],\"limit\":500}"); show '{total}'
say "  triangle contour accepted by API: $(echo "$BODY" | jq .total) objects (rectangle: $RECT) — UI only offers a rectangle; no centre/buffer search"

# ---------------------------------------------------------------------------------------------
fr "FR-SRCH-05 / FR-PUB-02 — restricted data and the role matrix"
LR=$(newlayer admin "АТ служебный слой" point restricted)
call admin POST "/api/layers/$LR/features" $(json '{"geometry":{"type":"Point","coordinates":[74.6,42.87]},"attrs":{"name":"Секретныйобъект АТ"}}'); FR_=$(echo "$BODY" | jq -r .id)
call admin POST "/api/layers/$LR/publish" >/dev/null
for r in anon citizen staff admin; do
  call $r GET "/api/search?q=$(jq_uri 'Секретныйобъект')"; show '{total}'
  check "search restricted as $r" "$(echo "$BODY" | jq .total)" "$([ $r = anon ] || [ $r = citizen ] && echo 0 || echo 1)"
done
for r in anon citizen staff; do
  call $r GET "/api/features/$FR_"; check "restricted feature card as $r" "$CODE" "$([ $r = staff ] && echo 200 || echo 403)"
  a=(); [ $r != anon ] && a=(-H "Authorization: Bearer ${TOK[$r]}")
  CODE=$(curl -s -o /dev/null -w '%{http_code}' "${a[@]}" "$BASE/api/tiles/$LR/14/11587/6028.pbf")
  echo "\$ curl '/api/tiles/$LR/14/11587/6028.pbf' [$r] → HTTP $CODE"; check "restricted tile as $r" "$CODE" "$([ $r = staff ] && echo 200 || echo 403)"
  call $r GET "/api/catalog/layers/$LR"; check "restricted layer card as $r" "$CODE" "$([ $r = staff ] && echo 200 || echo 403)"
done
say "  role matrix (HTTP status):"
printf '  %-38s %6s %8s %6s %6s\n' endpoint anon citizen staff admin
for ep in "GET /api/catalog/tree" "GET /api/my/layers" "GET /api/zoning/discrepancies" "GET /api/admin/users" "GET /api/admin/audit" "POST /api/layers" "GET /api/imports/00000000-0000-0000-0000-000000000000"; do
  IFS=" " read -r M P <<< "$ep"; set -- "$M" "$P"; row=""
  for r in anon citizen staff admin; do
    a=(); [ $r != anon ] && a=(-H "Authorization: Bearer ${TOK[$r]}")
    c=$(curl -s -o /dev/null -w '%{http_code}' -X "$1" "$BASE$2" "${a[@]}" -H 'Content-Type: application/json' -d '{}'); [ "$1" = GET ] && c=$(curl -s -o /dev/null -w '%{http_code}' "$BASE$2" "${a[@]}")
    row="$row $(printf '%6s' "$c")"
  done
  printf '  %-38s%s\n' "$ep" "$row"
done
for r in anon citizen staff admin; do
  a=(); [ $r != anon ] && a=(-H "Authorization: Bearer ${TOK[$r]}")
  say "  catalog layers visible to $r: $(curl -s "$BASE/api/catalog/tree" "${a[@]}" | jq '[.[].layers[]] | length')"
done

# ---------------------------------------------------------------------------------------------
fr "FR-CFG-01 — layer style"
call admin PATCH "/api/layers/$LP" $(json '{"color":"#ff0000"}'); show '{style}'
check "style colour changed" "$(echo "$BODY" | jq -r .style.color)" "#ff0000"
say "  no min/max zoom (visibility scale) fields in catalog.layers or LayerPatch"

fr "FR-CFG-04 / FR-TER-02 — rules dictionary, change rule and re-run check"
call admin GET /api/zoning/territories; BSK=$(echo "$BODY" | jq -r '.[] | select(.title=="Бишкек") | .id')
call admin POST /api/zoning/checks $(json "{\"territoryId\":\"$BSK\"}"); show .
check "re-run: 117 checked, 29 discrepancies, 0 new" "$(echo "$BODY" | jq -c '[.objectsChecked, .discrepancies, .newDiscrepancies]')" '[117,29,0]'
call admin PUT /api/admin/zoning-rules $(json '[{"zoneType":"residential","useType":"agriculture","allowed":true}]'); show 'length'
call admin POST /api/zoning/checks $(json "{\"territoryId\":\"$BSK\"}"); show '{objectsChecked, discrepancies, byType}'
AFTER=$(echo "$BODY" | jq .discrepancies)
check "allowing agriculture in residential lowers count" "$([ "$AFTER" -lt 29 ] && echo lower)" lower
call admin PUT /api/admin/zoning-rules $(json '[{"zoneType":"residential","useType":"agriculture","allowed":false}]') >/dev/null
call admin POST /api/zoning/checks $(json "{\"territoryId\":\"$BSK\"}"); show '{discrepancies, byType}'
check "rule restored → 29 again" "$(echo "$BODY" | jq .discrepancies)" 29
call admin GET /api/admin/dictionaries; say "  generic dictionaries endpoint: HTTP $CODE"

# ---------------------------------------------------------------------------------------------
fr "FR-AUTH-01/02/03/06, FR-ADM-01 — login, employment check, block, journal"
call anon POST /api/auth/dev-login $(json '{"subject":"tunduk:citizen"}'); show '.user'
check "Түндүк (emulated) citizen → role authorized" "$(echo "$BODY" | jq -r .user.role)" authorized
call anon POST /api/auth/dev-login $(json '{"subject":"infocom:minzdrav"}'); show '.user'
check "Infocom ID (emulated) staff → org from employment registry" "$(echo "$BODY" | jq -r .user.orgName)" "Минздрав"
call anon POST /api/auth/dev-login $(json '{"subject":"infocom:former"}'); show .
check "is_active=false in registry → 403" "$CODE" 403
call admin GET /api/admin/users; show '[.[] | {displayName, role, status, org, position}]'
UID_=$(echo "$BODY" | jq -r '.[] | select(.extSubject=="infocom:minzdrav") | .id')
call admin PATCH "/api/admin/users/$UID_" $(json '{"status":"blocked"}'); show .
call anon POST /api/auth/dev-login $(json '{"subject":"infocom:minzdrav"}'); show .
check "blocked user cannot log in" "$CODE" 403
call admin PATCH "/api/admin/users/$UID_" $(json '{"status":"active","role":"staff"}') >/dev/null
call admin PATCH "/api/admin/users/$UID_" $(json '{"role":"admin"}'); show .
check "admin assigns role" "$(echo "$BODY" | jq -r .role)" admin
call admin PATCH "/api/admin/users/$UID_" $(json '{"role":"staff"}') >/dev/null
call admin POST /api/admin/users $(json '{"extSubject":"x"}'); say "  create user endpoint: HTTP $CODE"
call admin DELETE "/api/admin/users/$UID_"; say "  delete user endpoint: HTTP $CODE"
call admin GET "/api/admin/audit?limit=200"; show '[.[] | select(.action|test("login|user_update")) | {action, outcome, userName, details}] | .[0:6]'
check "journal: login with provider" "$(echo "$BODY" | jq '[.[] | select(.action=="login" and .details.provider!=null)] | length > 0')" true
check "journal: denied login" "$(echo "$BODY" | jq '[.[] | select(.action=="login_denied")] | length > 0')" true
check "journal: block/role change" "$(echo "$BODY" | jq '[.[] | select(.action=="user_update")] | length >= 3')" true
check "journal: logout event (expected absent)" "$(echo "$BODY" | jq '[.[] | select(.action|test("logout"))] | length')" 0

fr "FR-ADM-02 — usage statistics"
call admin GET /api/admin/stats; show .
call admin GET "/api/admin/stats?format=xlsx"; say "  content-type: $(echo "$BODY" | head -c 1 | od -c | head -1 | cut -c1-30) (JSON — no Excel export)"

# ---------------------------------------------------------------------------------------------
fr "FR-TER-01 — zoning + current development layers"
call anon GET "/api/catalog/layers/$(lid urban_zones)"; show '{titleRu, publishedVersion, bbox}'
call anon GET "/api/catalog/layers/$(lid current_development)"; show '{titleRu, publishedVersion, bbox}'

fr "FR-TER-03 / FR-TER-04 — registry record, status history, filters"
call admin GET "/api/zoning/discrepancies/$DID"; show '{objectTitle, typeLabel, responsibleOrg, statusLabel, history: [.history[] | {fromStatus, toStatus, byName, at}]}'
check "record has object/type/responsible" "$(echo "$BODY" | jq '(.objectTitle!=null) and (.typeLabel!=null) and (.responsibleOrg!=null)')" true
check "history entries have author+time" "$(echo "$BODY" | jq '[.history[] | select(.byName==null or .at==null)] | length')" 0
for f in "type=CROSSES_ZONES" "type=USE_NOT_ALLOWED" "status=new" "territoryId=$BSK" "territoryId=$BSK&type=NO_ZONE&status=new"; do
  call admin GET "/api/zoning/discrepancies?$f"; show '{n: length, types: ([.[].type]|unique), statuses: ([.[].status]|unique)}'
done

fr "FR-TER-05 / FR-RPT-01 — report for a territory, matches registry"
for fmt in xlsx pdf; do
  CODE=$(curl -s -o "$OUT/rep.$fmt" -w '%{http_code}' -H "Authorization: Bearer ${TOK[admin]}" "$BASE/api/zoning/report?format=$fmt&territoryId=$BSK")
  echo "\$ curl '/api/zoning/report?format=$fmt&territoryId=$BSK' [admin] → HTTP $CODE, $(wc -c < "$OUT/rep.$fmt") bytes"
done
CODE=$(curl -s -o "$OUT/rep_sel.xlsx" -w '%{http_code}' -H "Authorization: Bearer ${TOK[admin]}" "$BASE/api/zoning/report?format=xlsx&territoryId=$BSK&type=NO_ZONE")
echo "\$ curl '/api/zoning/report?format=xlsx&territoryId=$BSK&type=NO_ZONE' [admin] → HTTP $CODE (filtered selection)"
mkdir -p "$ROOT/docs/acceptance/evidence"; cp "$OUT/rep.xlsx" "$ROOT/docs/acceptance/evidence/zoning-report-bishkek.xlsx"; cp "$OUT/rep.pdf" "$ROOT/docs/acceptance/evidence/zoning-report-bishkek.pdf"; cp "$OUT/rep_sel.xlsx" "$ROOT/docs/acceptance/evidence/zoning-report-bishkek-NO_ZONE.xlsx"
call staff GET "/api/zoning/report?format=docx"; say "  format=docx → HTTP $CODE, content starts '$(head -c 4 "$OUT/body" | tr -dc 'A-Za-z%')' (falls back to XLSX; no DOCX/PPTX)"

fr "FR-TER-07 — create zone with permitted use, change boundary, history"
UZ=$(lid urban_zones)
call staff2 POST "/api/layers/$UZ/features" $(json '{"geometry":{"type":"Polygon","coordinates":[[[74.70,42.90],[74.71,42.90],[74.71,42.91],[74.70,42.90]]]},"attrs":{"zone_code":"АТ-З-01","zone_type":"recreation","zone_name":"Зона АТ","approval_doc":"Решение №1"}}'); show '{id, title, attributes: [.attributes[] | {label, value}]}'
FZ=$(echo "$BODY" | jq -r .id); check "zone created by Минархстрой" "$CODE" 200
call staff2 PUT "/api/features/$FZ" $(json '{"geometry":{"type":"Polygon","coordinates":[[[74.70,42.90],[74.72,42.90],[74.72,42.91],[74.70,42.90]]]}}')
check "zone boundary changed" "$CODE" 200
call staff2 GET "/api/features/$FZ/history"; show '[.[] | {op, author, at}]'
call staff2 GET "/api/catalog/layers/$UZ"; show '{draftVersion, publishedVersion, versions: [.versions[] | {version, author, createdAt, source}]}'

echo
echo "TOTAL CHECKS: $PASS passed, $FAIL failed"
