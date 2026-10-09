package com.altronixsoft.opp.payment.domain;

/** The mapping from Stripe's {@code PaymentIntent.status} to {@link PaymentStatus} (architecture §5.2). */
public final class StripePaymentIntentStatus {

    private StripePaymentIntentStatus() {}

    /**
     * @throws StripeConfigurationException {@code requires_capture}: the account or the integration uses manual capture,
     *     which this platform does not
     * @throws UnknownStripeStatusException any other status the platform has no mapping for
     */
    public static PaymentStatus toPaymentStatus(String stripeStatus) {
        if (stripeStatus == null) {
            throw new UnknownStripeStatusException(null);
        }
        return switch (stripeStatus) {
            case "requires_payment_method", "requires_confirmation" -> PaymentStatus.REQUIRES_PAYMENT_METHOD;
            case "requires_action" -> PaymentStatus.REQUIRES_ACTION;
            case "processing" -> PaymentStatus.PROCESSING;
            case "succeeded" -> PaymentStatus.SUCCEEDED;
            case "canceled" -> PaymentStatus.CANCELED;
            case "requires_capture" ->
                throw new StripeConfigurationException(
                        "PaymentIntent status requires_capture: manual capture is not used by this platform");
            default -> throw new UnknownStripeStatusException(stripeStatus);
        };
    }
}
