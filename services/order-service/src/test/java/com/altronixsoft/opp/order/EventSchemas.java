package com.altronixsoft.opp.order;

import com.networknt.schema.Error;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** The JSON Schemas shipped in event-contracts ({@code classpath:schemas/}), to check what the service publishes. */
final class EventSchemas {

    private static final String BASE = "https://schemas.altronixsoft.com/opp/events/";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaIdResolvers(resolvers -> resolvers.mapPrefix(BASE, "classpath:schemas/"))
                    .schemaRegistryConfig(SchemaRegistryConfig.builder()
                            .formatAssertionsEnabled(true)
                            .build()));

    private EventSchemas() {}

    /** The violations of {@code json} against the schema of {@code eventType} version 1; empty when valid. */
    static List<Error> validate(String eventType, String json) {
        return REGISTRY.getSchema(SchemaLocation.of(BASE + eventType + ".v1.json"))
                .validate(JSON.readTree(json));
    }
}
