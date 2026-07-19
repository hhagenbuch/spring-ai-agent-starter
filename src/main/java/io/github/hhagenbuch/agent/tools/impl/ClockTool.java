package io.github.hhagenbuch.agent.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hhagenbuch.agent.tools.AgentTool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Current date/time — the canonical example of grounding a model in reality. */
@Component
public class ClockTool implements AgentTool {

    @Override
    public String name() {
        return "clock";
    }

    @Override
    public String description() {
        return "Returns the current date and time, optionally in a given IANA time zone (default UTC).";
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode zone = properties.putObject("zone");
        zone.put("type", "string");
        zone.put("description", "IANA zone id, e.g. 'America/New_York'. Defaults to UTC.");
        return schema;
    }

    @Override
    public Mono<String> execute(JsonNode input) {
        return Mono.fromSupplier(() -> {
            String zone = input.path("zone").asText("UTC");
            return ZonedDateTime.now(ZoneId.of(zone))
                    .format(DateTimeFormatter.RFC_1123_DATE_TIME);
        });
    }
}
