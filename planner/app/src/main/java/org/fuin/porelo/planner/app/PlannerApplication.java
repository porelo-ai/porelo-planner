package org.fuin.porelo.planner.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the planner. */
@SpringBootApplication
public class PlannerApplication {

    /**
     * Starts the planner and serves it until it is stopped.
     *
     * @param args Spring Boot's command line arguments, such as {@code --server.port=0}.
     */
    public static void main(final String[] args) {
        SpringApplication.run(PlannerApplication.class, args);
    }
}
