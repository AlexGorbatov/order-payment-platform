package com.altronixsoft.opp.payment.application;

/**
 * Port: makes the outcome of a reconciliation run observable (architecture §13): {@code reconciliation.checked},
 * {@code reconciliation.drift{from,to}}, {@code reconciliation.failures}. Called for scheduled and manual runs alike.
 */
public interface ReconciliationRecorder {

    void record(ReconciliationSummary summary);
}
