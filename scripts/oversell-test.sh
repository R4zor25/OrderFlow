#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
source "$ROOT/scripts/scenario-common.sh"
initialize

SKU=${SKU:-CHAIR-002}
REQUESTS=${REQUESTS:-50}
EXPECTED_AVAILABLE=${EXPECTED_AVAILABLE:-10}
[[ "$SKU" =~ ^[A-Z0-9._-]{1,32}$ ]] || fail 'SKU must contain 1 to 32 uppercase letters, digits, dots, underscores or hyphens'
[[ "$REQUESTS" =~ ^[1-9][0-9]*$ && "$EXPECTED_AVAILABLE" =~ ^[1-9][0-9]*$ ]] || fail 'REQUESTS and EXPECTED_AVAILABLE must be positive integers'
(( REQUESTS > EXPECTED_AVAILABLE )) || fail 'REQUESTS must exceed EXPECTED_AVAILABLE'
TOKEN=$(token_for consumer.one)
before=$(sql_read "SELECT on_hand || ':' || reserved || ':' || (on_hand-reserved) FROM inventory.stock_item WHERE sku='$SKU'")
IFS=: read -r on_hand reserved available <<< "$before"
[[ "$available" == "$EXPECTED_AVAILABLE" ]] || fail "Expected $EXPECTED_AVAILABLE available units, found ${available:-missing} for $SKU; prepare an isolated fixture first"

body=$(jq -nc --arg sku "$SKU" '{lines:[{sku:$sku,quantity:1}]}')
pids=()
for (( i=1; i<=REQUESTS; i++ )); do
  request POST /api/v1/orders 202 "$WORK/order-$i.json" "$body" "oversell-$RUN_ID-$i" &
  pids+=($!)
done
failed=0
for pid in "${pids[@]}"; do wait "$pid" || failed=1; done
(( failed == 0 )) || fail 'At least one parallel HTTP request failed; no oversell verdict is possible'

confirmed=0
rejected=0
ids=()
deadline=$((SECONDS + TIMEOUT_SECONDS))
for (( i=1; i<=REQUESTS; i++ )); do
  id=$(order_id "$WORK/order-$i.json")
  ids+=("$id")
  while (( SECONDS < deadline )); do
    request GET "/api/v1/orders/$id" 200 "$WORK/result-$i.json"
    status=$(jq -er '.status' "$WORK/result-$i.json")
    [[ "$status" == PENDING ]] || break
    sleep 0.25
  done
  case "${status:-PENDING}" in
    CONFIRMED) confirmed=$((confirmed + 1)) ;;
    REJECTED) rejected=$((rejected + 1)) ;;
    *) fail "Order $id did not reach CONFIRMED or REJECTED before the shared deadline" ;;
  esac
  unset status
done
(( confirmed == EXPECTED_AVAILABLE && rejected == REQUESTS - EXPECTED_AVAILABLE )) || fail "Unexpected totals: confirmed=$confirmed rejected=$rejected"
unique=$(printf '%s\n' "${ids[@]}" | sort -u | wc -l | tr -d ' ')
[[ "$unique" == "$REQUESTS" ]] || fail 'Parallel requests did not create distinct orders'
after=$(sql_read "SELECT on_hand || ':' || reserved || ':' || (on_hand-reserved) FROM inventory.stock_item WHERE sku='$SKU'")
[[ "$after" == "$on_hand:$((reserved + confirmed)):0" ]] || fail "Stock totals disagree with confirmed orders: $after"
quoted=$(printf "'%s'::uuid," "${ids[@]}")
quoted=${quoted%,}
reservation_count=$(sql_read "SELECT count(*) || ':' || COALESCE(sum(quantity),0) FROM inventory.reservation WHERE order_id IN ($quoted) AND sku='$SKU' AND status='RESERVED'")
[[ "$reservation_count" == "$confirmed:$confirmed" ]] || fail "Reservation totals disagree: $reservation_count"
mismatches=$(sql_read "SELECT count(*) FROM orders.orders o LEFT JOIN inventory.reservation r ON r.order_id=o.id AND r.sku='$SKU' WHERE o.id IN ($quoted) AND ((o.status='CONFIRMED' AND (r.id IS NULL OR r.status<>'RESERVED' OR r.quantity<>1)) OR (o.status='REJECTED' AND r.id IS NOT NULL))")
[[ "$mismatches" == 0 ]] || fail 'Reservations do not match individual confirmed/rejected orders'
invalid=$(sql_read "SELECT count(*) FROM inventory.stock_item WHERE reserved < 0 OR reserved > on_hand")
[[ "$invalid" == 0 ]] || fail 'Stock invariant violated'
printf 'PASS: requests=%s confirmed=%s rejected=%s available=0 reservations=%s\n' "$REQUESTS" "$confirmed" "$rejected" "$reservation_count"
