package com.altronixsoft.opp.platform.messaging.consumer;

import com.altronixsoft.opp.platform.messaging.inbox.InboxGuard;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/** A consuming service stand-in: a listener, its handler, its topic and method security (the service's duty). */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class MessagingTestConfig {

    @Bean
    NewTopic testTopic(@Value("${test.topic}") String topic) {
        return new NewTopic(topic, 1, (short) 1);
    }

    @Bean
    TestHandler testHandler(JdbcClient jdbc) {
        return new TestHandler(jdbc);
    }

    @Bean
    TestEventListener testEventListener(EventEnvelopeReader reader, InboxGuard inbox, TestHandler handler) {
        return new TestEventListener(reader, inbox, handler);
    }
}
