#!/usr/bin/env bash
# Republishes one dead-lettered record to its source topic; consumers deduplicate within 14 days of the original publish.
set -euo pipefail

usage() {
  echo "usage: $0 <topic>.<group>.DLT <partition> <offset>  (group: order-service, inventory-service, notification-service)" >&2
  exit 2
}

[[ $# -eq 3 ]] || usage
dlt=$1
partition=$2
offset=$3
[[ "$dlt" =~ ^(.+)\.(order-service|inventory-service|notification-service)\.DLT$ ]] || usage
source_topic=${BASH_REMATCH[1]}
[[ "$partition" =~ ^[0-9]+$ && "$offset" =~ ^[0-9]+$ ]] || usage

root=$(cd "$(dirname "$0")/.." && pwd)
if [[ -n "${KAFKA_EXEC:-}" ]]; then
  # A plain word list; quoted arguments are not supported in the override.
  read -r -a kafka_exec <<< "$KAFKA_EXEC"
else
  kafka_exec=(docker compose -f "$root/infra/docker-compose.yaml" exec -T kafka)
fi
bootstrap=${KAFKA_BOOTSTRAP:-localhost:9092}

errors=$(mktemp)
trap 'rm -f "$errors"' EXIT
if ! record=$("${kafka_exec[@]}" /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server "$bootstrap" \
    --topic "$dlt" --partition "$partition" --offset "$offset" --max-messages 1 --timeout-ms 10000 \
    --formatter-property print.key=true --formatter-property key.separator=$'\t' 2> "$errors"); then
  echo "reading $dlt partition $partition offset $offset failed:" >&2
  cat "$errors" >&2
  exit 1
fi
# Anything but one keyed line (tool noise, a multi-line value) must not reach a business topic.
if [[ -z "$record" || "$record" == *$'\n'* || "$record" != *$'\t'* ]]; then
  echo "no single keyed record at $dlt partition $partition offset $offset" >&2
  cat "$errors" >&2
  exit 1
fi
printf '%s\n' "$record" | "${kafka_exec[@]}" /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$bootstrap" --topic "$source_topic" \
  --reader-property parse.key=true --reader-property key.separator=$'\t'
echo "replayed $dlt partition $partition offset $offset to $source_topic"
