#!/usr/bin/env bash
# Republishes one dead-lettered record to its source topic; consumers deduplicate events up to 14 days old.
set -euo pipefail

usage() {
  echo "usage: $0 <topic>.<group>.DLT <partition> <offset>" >&2
  exit 2
}

[[ $# -eq 3 ]] || usage
dlt=$1
partition=$2
offset=$3
[[ "$dlt" =~ ^(.+)\.[a-z0-9-]+\.DLT$ ]] || usage
source_topic=${BASH_REMATCH[1]}
[[ "$partition" =~ ^[0-9]+$ && "$offset" =~ ^[0-9]+$ ]] || usage

root=$(cd "$(dirname "$0")/.." && pwd)
read -r -a kafka_exec <<< "${KAFKA_EXEC:-docker compose -f $root/infra/docker-compose.yaml exec -T kafka}"
bootstrap=${KAFKA_BOOTSTRAP:-localhost:9092}

record=$("${kafka_exec[@]}" /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server "$bootstrap" \
  --topic "$dlt" --partition "$partition" --offset "$offset" --max-messages 1 --timeout-ms 10000 \
  --formatter-property print.key=true --formatter-property key.separator=$'\t' 2>/dev/null || true)
if [[ -z "$record" ]]; then
  echo "no record at $dlt partition $partition offset $offset" >&2
  exit 1
fi
printf '%s\n' "$record" | "${kafka_exec[@]}" /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$bootstrap" --topic "$source_topic" \
  --reader-property parse.key=true --reader-property key.separator=$'\t'
echo "replayed $dlt partition $partition offset $offset to $source_topic"
