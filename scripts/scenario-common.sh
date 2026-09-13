#!/usr/bin/env bash

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
require() { command -v "$1" >/dev/null 2>&1 || fail "Required command missing: $1"; }

initialize() {
  require curl
  require jq
  BASE_URL=${BASE_URL:-http://localhost:8082}
  BASE_URL=${BASE_URL%/}
  TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-60}
  [[ "$TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] || fail 'TIMEOUT_SECONDS must be a positive integer'
  SQL_MODE=${SQL_MODE:-compose}
  case "$SQL_MODE" in
    compose)
      require docker
      COMPOSE_FILE=${COMPOSE_FILE:-$ROOT/infra/docker-compose.yaml}
      COMPOSE_ENV_FILE=${COMPOSE_ENV_FILE:-$ROOT/infra/.env}
      COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-orderflow}
      ;;
    psql) require psql ;;
    *) fail 'SQL_MODE must be compose or psql' ;;
  esac
  WORK=$(mktemp -d "${TMPDIR:-/tmp}/orderflow-scenario.XXXXXX")
  trap 'rm -rf "$WORK"' EXIT
  RUN_ID="$(date +%s)-$$-$RANDOM"
}

sql_read() {
  local query=$1
  if [[ "$SQL_MODE" == compose ]]; then
    docker compose --env-file "$COMPOSE_ENV_FILE" -p "$COMPOSE_PROJECT_NAME" -f "$COMPOSE_FILE" \
      exec -T postgres psql -X -w -qAt -v ON_ERROR_STOP=1 -U orderflow -d orderflow \
      -c "BEGIN READ ONLY; SET LOCAL statement_timeout='5s'; $query; COMMIT;"
  else
    PGCONNECT_TIMEOUT=5 psql -X -w -qAt -v ON_ERROR_STOP=1 \
      -c "BEGIN READ ONLY; SET LOCAL statement_timeout='5s'; $query; COMMIT;"
  fi
}

request() {
  local method=$1 path=$2 expected=$3 output=$4 body=${5:-} key=${6:-} code
  local args=(-sS --connect-timeout 3 --max-time 15 -X "$method" -o "$output" -w '%{http_code}')
  if [[ -n "$body" ]]; then args+=(-H 'Content-Type: application/json' --data "$body"); fi
  if [[ -n "$key" ]]; then args+=(-H "Idempotency-Key: $key"); fi
  code=$(curl "${args[@]}" "$BASE_URL$path") || fail "HTTP transport failure: $method $path"
  [[ "$code" == "$expected" ]] || fail "Expected HTTP $expected, got $code: $method $path"
  jq -e . "$output" >/dev/null || fail "Invalid JSON: $method $path"
}

order_id() {
  local id
  id=$(jq -er '.id' "$1") || fail 'Order response has no id'
  [[ "$id" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] || fail 'Invalid order UUID'
  printf '%s' "$id"
}

await_order() {
  local id=$1 expected=$2 deadline=${3:-$((SECONDS + TIMEOUT_SECONDS))} status
  while (( SECONDS < deadline )); do
    request GET "/api/v1/orders/$id" 200 "$WORK/status-$id.json"
    status=$(jq -er '.status' "$WORK/status-$id.json")
    [[ "$status" == "$expected" ]] && return 0
    [[ "$status" == PENDING ]] || fail "Order $id: expected $expected, observed $status"
    sleep 0.25
  done
  fail "Timed out waiting for order $id to become $expected (last status: ${status:-unknown})"
}

await_sql() {
  local expected=$1 query=$2 deadline=$((SECONDS + TIMEOUT_SECONDS)) actual
  while (( SECONDS < deadline )); do
    actual=$(sql_read "$query") || fail 'SQL inspection failed'
    [[ "$actual" == "$expected" ]] && return 0
    sleep 0.25
  done
  fail "SQL condition timed out: expected $expected, observed ${actual:-empty}"
}

assert_json() { jq -e "$2" "$1" >/dev/null || fail "JSON assertion failed: $2"; }

await_notification() {
  local id=$1 type=$2 subject=$3 deadline messages
  await_sql 1 "SELECT count(*) FROM notifications.notification WHERE order_id='$id'::uuid AND type='$type'"
  if [[ -n "${MAILPIT_URL:-}" ]]; then
    deadline=$((SECONDS + TIMEOUT_SECONDS))
    while (( SECONDS < deadline )); do
      messages=$(curl -fsS --connect-timeout 3 --max-time 10 "${MAILPIT_URL%/}/api/v1/search?query=subject%3A$id") || fail 'Mailpit inspection failed'
      if jq -e --arg subject "$subject $id" '.messages | any(.Subject == $subject)' <<< "$messages" >/dev/null; then return 0; fi
      sleep 0.25
    done
    fail "Email not observed in Mailpit: $type $id"
  fi
}
