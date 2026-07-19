package io.github.hhagenbuch.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AgentStarterApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentStarterApplication.class, args);
    }
}
