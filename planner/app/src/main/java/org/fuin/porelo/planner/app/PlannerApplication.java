package org.fuin.porelo.planner.app;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import org.fuin.porelo.planner.app.mock.MockMode;
import org.fuin.porelo.wire.LimitFactors;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ImportRuntimeHints;

/** Starts the planner. */
@SpringBootApplication
@ImportRuntimeHints(AnswerArrays.class)
public class PlannerApplication {

    /** The argument that serves the window's mock board instead of the person's stores. */
    public static final String MOCK = "--mock";

    /** The argument that ends the planner once its standard input closes, as the window's does. */
    public static final String END_WITH_INPUT = "--end-with-input";

    /** The exit code of a command line the planner refuses before it does anything. */
    static final int REFUSED_LINE = 64;

    /**
     * The command line after its limits line, and the factors that line names.
     *
     * @param limits the factors every time limit follows, all four at 1.0 without the option
     * @param rest the arguments after the option: a work command or the server's
     */
    record Launch(LimitFactors limits, List<String> rest) {

        /** Copies the arguments. */
        Launch {
            rest = List.copyOf(rest);
        }

        /**
         * Reads {@code --limits <line>} from the first two arguments, where Porelo puts it.
         *
         * @param args the command line
         * @return the factors and the rest
         * @throws IllegalArgumentException saying {@code --limits: <what is wrong>}: a bad line,
         *     the option anywhere but first, given twice, or as {@code --limits=<line>}
         */
        static Launch of(final String... args) {
            final LimitFactors.Leading leading = LimitFactors.leading(args);
            final List<String> rest = Arrays.asList(args).subList(leading.next(), args.length);
            for (final String arg : rest) {
                if (arg.equals(LimitFactors.OPTION) || arg.startsWith(LimitFactors.OPTION + "=")) {
                    throw new IllegalArgumentException(
                            LimitFactors.OPTION + ": " + misplaced(leading, arg));
                }
            }
            return new Launch(leading.factors(), rest);
        }

        /** What is wrong with the option found after the first two arguments. */
        private static String misplaced(final LimitFactors.Leading leading, final String arg) {
            if (leading.next() > 0) {
                return "is given twice";
            }
            return arg.equals(LimitFactors.OPTION)
                    ? "comes first, before the mode or command"
                    : "takes its line as the next argument, never after '='";
        }
    }

    /**
     * Reads the limits line and runs a work tool when the rest names one, before anything else.
     *
     * @param args the command line
     * @param out where a work tool's answer goes
     * @param err where a refusal of the line, and a work tool's faults, go
     * @return the exit code: {@value #REFUSED_LINE} for a refused line, or the work tool's; empty
     *     when the rest starts the planner
     */
    static OptionalInt work(final String[] args, final PrintStream out, final PrintStream err) {
        final Launch launch;
        try {
            launch = Launch.of(args);
        } catch (final IllegalArgumentException e) {
            err.println(e.getMessage());
            return OptionalInt.of(REFUSED_LINE);
        }
        final String[] rest = launch.rest().toArray(String[]::new);
        if (WorkCommands.named(rest)) {
            return OptionalInt.of(WorkCommands.run(launch.rest(), out, err, launch.limits()));
        }
        return OptionalInt.empty();
    }

    /**
     * Runs a work tool and exits with its code when the first argument after the limits line is a
     * work command's name; otherwise starts the planner and serves it until it is stopped. A
     * refused limits line ends the planner with {@value #REFUSED_LINE} before anything starts.
     *
     * @param args optionally {@code --limits <line>} first, then {@code issues}, {@code stories} or
     *     another work command and its arguments, or Spring Boot's command line arguments, such as
     *     {@code --server.port=0}, {@code --mock} for the mock mode and {@code --end-with-input} to
     *     end once the standard input closes
     */
    public static void main(final String[] args) {
        // Before Spring: a refused line or a repository's check starts no server and reads no
        // planner configuration.
        final OptionalInt worked = work(args, System.out, System.err);
        if (worked.isPresent()) {
            System.exit(worked.getAsInt());
        }
        final Launch launch = Launch.of(args);
        final ConfigurableApplicationContext planner =
                run(launch.limits(), launch.rest().toArray(String[]::new));
        if (launch.rest().contains(END_WITH_INPUT)) {
            InputEnd.watch(System.in, () -> SpringApplication.exit(planner), System::exit);
        }
    }

    /**
     * Starts the planner with every limit at its default, in the mock mode if the arguments name
     * {@code --mock}.
     *
     * @param args the command line arguments
     * @return the running planner
     */
    static ConfigurableApplicationContext run(final String... args) {
        return run(LimitFactors.DEFAULT, args);
    }

    /**
     * Starts the planner, in the mock mode if the arguments name {@code --mock}.
     *
     * @param limits the factors every time limit follows
     * @param args the command line arguments after the limits line
     * @return the running planner
     */
    static ConfigurableApplicationContext run(final LimitFactors limits, final String... args) {
        final SpringApplication application = new SpringApplication(PlannerApplication.class);
        final boolean mock = Arrays.asList(args).contains(MOCK);
        // Beans only the command line registers, so no profile or environment sets them.
        application.addInitializers(
                (ApplicationContextInitializer<ConfigurableApplicationContext>)
                        context -> {
                            context.getBeanFactory().registerSingleton("limitFactors", limits);
                            if (mock) {
                                context.getBeanFactory()
                                        .registerSingleton("mockMode", new MockMode());
                            }
                        });
        return application.run(
                Arrays.stream(args)
                        .filter(arg -> !MOCK.equals(arg) && !END_WITH_INPUT.equals(arg))
                        .toArray(String[]::new));
    }
}
