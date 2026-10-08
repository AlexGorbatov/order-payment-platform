package com.altronixsoft.opp.platform.messaging.deadletter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of dead-letter handling ({@code platform.dead-letters.*}).
 *
 * @param enabled master switch for persistence and the admin API
 * @param persister the listener that stores records of {@code *-dlt} topics
 * @param admin the {@code /admin/dead-letters} API
 */
@Validated
@ConfigurationProperties("platform.dead-letters")
public record DeadLetterProperties(
        @DefaultValue("true") boolean enabled,
        @Valid @DefaultValue Persister persister,
        @Valid @DefaultValue Admin admin) {

    /**
     * @param enabled run the persister in this instance
     * @param groupId consumer group of the persister; empty means {@code <spring.application.name>-dlt-persister}. All
     *     instances of a service share it, so each dead letter is stored once
     * @param topicPattern regular expression of the topics to persist
     * @param metadataRefresh how often the persister's consumer looks for new matching topics
     */
    public record Persister(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("") String groupId,
            @DefaultValue(".*-dlt") @NotBlank String topicPattern,

            @DefaultValue("5s") @NotNull @DurationMin(millis = 500)
            Duration metadataRefresh) {}

    /** @param enabled expose {@code /admin/dead-letters} (servlet applications with Spring Security only) */
    public record Admin(@DefaultValue("true") boolean enabled) {}
}
