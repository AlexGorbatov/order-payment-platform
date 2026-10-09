package com.altronixsoft.opp.payment.config;

import com.altronixsoft.opp.payment.application.ConfirmTestPaymentService;
import com.altronixsoft.opp.payment.application.IdGenerator;
import com.altronixsoft.opp.payment.application.PaymentGateway;
import com.altronixsoft.opp.payment.application.PaymentRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the test-support beans only when {@code platform.test-support.enabled=true} (the {@code local} and
 * {@code stripe-test} profiles). With the flag off (the default) neither the service nor the endpoint exists.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "platform.test-support", name = "enabled", havingValue = "true")
class TestSupportConfiguration {

    @Bean
    ConfirmTestPaymentService confirmTestPaymentService(
            PaymentRepository payments, PaymentGateway gateway, IdGenerator ids) {
        return new ConfirmTestPaymentService(payments, gateway, ids);
    }
}
