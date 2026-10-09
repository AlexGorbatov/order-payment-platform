package com.altronixsoft.opp.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.opp.payment.adapter.in.web.TestSupportController;
import com.altronixsoft.opp.payment.application.ConfirmTestPaymentService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/** With {@code platform.test-support.enabled=false} (the default) the test-support code does not exist at all. */
@TestPropertySource(properties = "platform.test-support.enabled=false")
class TestSupportDisabledIT extends AbstractPaymentIT {

    @Autowired
    ApplicationContext context;

    @Test
    @DisplayName("§8.5: no test-support beans exist when the flag is off")
    void noBeans() {
        assertThat(context.getBeansOfType(TestSupportController.class)).isEmpty();
        assertThat(context.getBeansOfType(ConfirmTestPaymentService.class)).isEmpty();
    }

    @Test
    @DisplayName("§8.5: the endpoint answers like any unknown path")
    void noEndpoint() {
        Reply reply = post(
                "/api/v1/test-support/payments/by-order/" + UUID.randomUUID() + "/confirm?scenario=success",
                TestKeycloak.tokenOf("customer1"));

        assertThat(reply.status()).isIn(404, 405);
        assertThat(reply.body()).doesNotContain("scenario");
    }

    @Test
    @DisplayName("the flag is off by default in application.yml, on only in the local and stripe-test profiles")
    void profilesDecideTheFlag() throws Exception {
        var base = new org.springframework.core.io.ClassPathResource("application.yml");
        var local = new org.springframework.core.io.ClassPathResource("application-local.yml");
        var stripeTest = new org.springframework.core.io.ClassPathResource("application-stripe-test.yml");
        assertThat(new String(base.getContentAsByteArray()))
                .containsPattern("test-support:[^\n]*\n(\\s*#[^\n]*\n)*\\s*enabled: false");
        assertThat(new String(local.getContentAsByteArray())).contains("enabled: true");
        assertThat(new String(stripeTest.getContentAsByteArray())).contains("enabled: true");
    }
}
