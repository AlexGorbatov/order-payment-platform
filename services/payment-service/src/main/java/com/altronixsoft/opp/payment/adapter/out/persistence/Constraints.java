package com.altronixsoft.opp.payment.adapter.out.persistence;

import org.hibernate.exception.ConstraintViolationException;

/** Finds out which database constraint a failed statement violated. */
final class Constraints {

    private Constraints() {}

    /** The name of the violated constraint (or unique index), or {@code "unknown"} if the exception does not say. */
    static String violatedBy(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                return unquote(violation.getConstraintName());
            }
        }
        return "unknown";
    }

    /** PostgreSQL names are reported bare, some drivers and Hibernate versions quote or schema-qualify them. */
    private static String unquote(String name) {
        String bare = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
        return bare.replace("\"", "");
    }
}
