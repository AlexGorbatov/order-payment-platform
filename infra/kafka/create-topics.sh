#!/usr/bin/env bash
# Creates the application topics (architecture §9.1). Idempotent; auto-create is disabled on the broker.
set -euo pipefail

kafka_topics=/opt/kafka/bin/kafka-topics.sh

for topic in $TOPICS; do
    "$kafka_topics" --bootstrap-server "$BOOTSTRAP_SERVERS" --create --if-not-exists \
        --topic "$topic" \
        --partitions "$PARTITIONS" \
        --replication-factor "$REPLICATION_FACTOR" \
        --config retention.ms="$RETENTION_MS"
done

for topic in $TOPICS; do
    "$kafka_topics" --bootstrap-server "$BOOTSTRAP_SERVERS" --describe --topic "$topic"
done
