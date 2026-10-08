#!/usr/bin/env bash
# Creates the application topics (architecture §9.1) and their retry and dead-letter topics (§7.4). Idempotent; auto-create is disabled on the broker.
set -euo pipefail

kafka_topics=/opt/kafka/bin/kafka-topics.sh

for topic in $TOPICS; do
    "$kafka_topics" --bootstrap-server "$BOOTSTRAP_SERVERS" --create --if-not-exists \
        --topic "$topic" \
        --partitions "$PARTITIONS" \
        --replication-factor "$REPLICATION_FACTOR" \
        --config retention.ms="$RETENTION_MS"
done

# Retry topics and the dead-letter topic of every application topic (architecture §7.4, ADR-0007). Spring Kafka
# creates missing ones itself, but creating them here sets the 14 days retention and keeps the broker's auto-create off.
for topic in $TOPICS; do
    for i in $(seq 0 $((RETRY_TOPICS - 1))); do
        "$kafka_topics" --bootstrap-server "$BOOTSTRAP_SERVERS" --create --if-not-exists \
            --topic "${topic}-retry-${i}" \
            --partitions "$PARTITIONS" \
            --replication-factor "$REPLICATION_FACTOR" \
            --config retention.ms="$DLT_RETENTION_MS"
    done
    "$kafka_topics" --bootstrap-server "$BOOTSTRAP_SERVERS" --create --if-not-exists \
        --topic "${topic}-dlt" \
        --partitions "$PARTITIONS" \
        --replication-factor "$REPLICATION_FACTOR" \
        --config retention.ms="$DLT_RETENTION_MS"
done

"$kafka_topics" --bootstrap-server "$BOOTSTRAP_SERVERS" --list | sort
