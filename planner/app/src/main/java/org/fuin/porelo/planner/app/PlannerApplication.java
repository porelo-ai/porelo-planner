package org.fuin.porelo.planner.app;

import java.util.Arrays;
import org.fuin.porelo.planner.app.mock.MockMode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Starts the planner. */
@SpringBootApplication
public class PlannerApplication {

    /** The argument that serves the window's mock board instead of the person's stores. */
    public static final String MOCK = "--mock";

    /**
     * Runs a work tool and exits with its code when the first argument is {@code issues} or {@code
     * stories}; otherwise starts the planner and serves it until it is stopped.
     *
     * @param args {@code issues} or {@code stories} and their arguments, or Spring Boot's command
     *     line arguments, such as {@code --server.port=0}, and {@code --mock} for the mock mode
     */
    public static void main(final String[] args) {
        // Before Spring: a repository's check starts no server and reads no planner configuration.
        if (WorkCommands.named(args)) {
            System.exit(WorkCommands.run(Arrays.asList(args), System.out, System.err));
        }
        run(args);
    }

    /**
     * Starts the planner, in the mock mode if the arguments name {@code --mock}.
     *
     * @param args the command line arguments
     * @return the running planner
     */
    static ConfigurableApplicationContext run(final String... args) {
        final SpringApplication application = new SpringApplication(PlannerApplication.class);
        if (Arrays.asList(args).contains(MOCK)) {
            // A bean only this argument registers, so no profile or environment turns the mode on.
            application.addInitializers(
                    (ApplicationContextInitializer<ConfigurableApplicationContext>)
                            context ->
                                    context.getBeanFactory()
                                            .registerSingleton("mockMode", new MockMode()));
        }
        return application.run(
                Arrays.stream(args).filter(arg -> !MOCK.equals(arg)).toArray(String[]::new));
    }
}
