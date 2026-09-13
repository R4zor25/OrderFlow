#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
source "$ROOT/scripts/scenario-common.sh"
initialize

consumer=11111111-1111-4111-8111-111111111111
business=44444444-4444-4444-8444-444444444444
available=$(sql_read "SELECT on_hand-reserved FROM inventory.stock_item WHERE sku='CHAIR-001'")
[[ "$available" =~ ^[0-9]+$ ]] && (( available >= 21 )) || fail 'Smoke fixture needs at least 21 available CHAIR-001 units'

body=$(jq -nc --arg customer "$consumer" '{customerId:$customer,lines:[{sku:"CHAIR-001",quantity:1}]}')
request POST /api/v1/orders 202 "$WORK/consumer.json" "$body" "smoke-$RUN_ID-consumer"
consumer_order=$(order_id "$WORK/consumer.json")
assert_json "$WORK/consumer.json" '.status == "PENDING" and .currency == "EUR" and .totalNet == 99.90 and .totalVat == 26.97 and .totalGross == 126.87'
request POST /api/v1/orders 202 "$WORK/replay.json" "$body" "smoke-$RUN_ID-consumer"
jq -e -s '.[0] == .[1]' "$WORK/consumer.json" "$WORK/replay.json" >/dev/null || fail 'Idempotent replay returned a different response'
await_order "$consumer_order" CONFIRMED
await_sql '1:RESERVED' "SELECT quantity || ':' || status FROM inventory.reservation WHERE order_id='$consumer_order'::uuid AND sku='CHAIR-001'"
await_notification "$consumer_order" OrderConfirmed 'Order confirmed'
printf 'PASS: B2C pricing, confirmation and idempotent replay\n'

body=$(jq -nc --arg customer "$business" '{customerId:$customer,lines:[{sku:"CHAIR-001",quantity:20}]}')
request POST /api/v1/orders 202 "$WORK/business.json" "$body" "smoke-$RUN_ID-business"
business_order=$(order_id "$WORK/business.json")
assert_json "$WORK/business.json" '.totalNet == 1498.00 and .totalVat == 404.46 and .totalGross == 1902.46'
await_order "$business_order" CONFIRMED
assert_json "$WORK/status-$business_order.json" '.lines | length == 1 and .[0].unitPriceNet == 74.90 and .[0].quantity == 20'
await_notification "$business_order" OrderConfirmed 'Order confirmed'
printf 'PASS: B2B GOLD quantity tier and confirmation\n'

insufficient_available=$(sql_read "SELECT on_hand-reserved FROM inventory.stock_item WHERE sku='CHAIR-003'")
[[ "$insufficient_available" =~ ^[0-9]+$ ]] && (( insufficient_available < 1000000 )) || fail 'Invalid insufficient-stock fixture'
body=$(jq -nc --arg customer "$consumer" --argjson quantity "$((insufficient_available + 1))" '{customerId:$customer,lines:[{sku:"CHAIR-003",quantity:$quantity}]}')
request POST /api/v1/orders 202 "$WORK/rejected.json" "$body" "smoke-$RUN_ID-rejected"
rejected_order=$(order_id "$WORK/rejected.json")
await_order "$rejected_order" REJECTED
await_sql 0 "SELECT count(*) FROM inventory.reservation WHERE order_id='$rejected_order'::uuid"
await_notification "$rejected_order" OrderRejected 'Order rejected'
printf 'PASS: insufficient stock rejects without a reservation\n'

for id in "$consumer_order" "$business_order"; do
  request POST "/api/v1/orders/$id/cancel" 200 "$WORK/cancel-$id.json"
  assert_json "$WORK/cancel-$id.json" '.status == "CANCELLED"'
  await_sql RELEASED "SELECT status FROM inventory.reservation WHERE order_id='$id'::uuid AND sku='CHAIR-001'"
  await_notification "$id" OrderCancelled 'Order cancelled'
done
await_sql "$available" "SELECT on_hand-reserved FROM inventory.stock_item WHERE sku='CHAIR-001'"
printf 'PASS: cancellation releases both reservations and restores available stock\n'
printf 'PASS: smoke scenarios complete (notifications persisted; Mailpit checked when configured)\n'
