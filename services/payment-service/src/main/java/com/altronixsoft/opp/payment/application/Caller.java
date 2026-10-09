package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.Payment;
import java.util.Objects;

/**
 * Who is asking, as far as the use cases care: the authenticated subject (JWT {@code sub}, which is the
 * {@code customerId} of the payments they own) and whether they act as an administrator.
 */
public record Caller(String subject, boolean admin) {

    public Caller {
        Objects.requireNonNull(subject, "subject");
        if (subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
    }

    public static Caller customer(String subject) {
        return new Caller(subject, false);
    }

    public static Caller admin(String subject) {
        return new Caller(subject, true);
    }

    boolean owns(Payment payment) {
        return payment.customerId().equals(subject);
    }
}
