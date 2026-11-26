package org.fuin.porelo.planner.app;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.fuin.porelo.planner.core.RefusedEventResave;
import org.fuin.porelo.planner.store.CommandOutcome;
import org.fuin.porelo.planner.store.Writes;

/**
 * The held save the mock mode gives a project on request, for the window's tests: a window's save
 * that would change the priority of an issue of the mock board, tried three times, the last held
 * with the forge's answer and its request id. Its resend and its drop write nothing; a project
 * given anew or reset keeps none.
 */
public final class MockHeldSaves {

    /** The held save's id. */
    public static final String SAVE = "0b1d5a7e-4c2f-4e8a-9d3b-6f1e2a7c5d90";

    /** The issue of the mock board it would change. */
    public static final String ISSUE = "PJ58"; // not-a-citation: a mock board id

    /** The priority it would write. */
    public static final String PRIORITY = "Low";

    /** The request id the forge gave its last try. */
    public static final String REQUEST_ID = "C0DE:1234:5678:9ABC:DEF0";

    private static final Instant FIRST = Instant.parse("2026-11-26T09:00:00Z");

    private final Projects projects;

    private final Set<String> held = ConcurrentHashMap.newKeySet();

    /**
     * Makes the mock's held saves.
     *
     * @param projects the projects given; one retired, as a reset of the mock board retires every
     *     project, keeps no held save
     */
    public MockHeldSaves(final Projects projects) {
        this.projects = projects;
        projects.onRetired(held::remove);
    }

    /**
     * Gives a project the held save, until it is resent, dropped, the project given anew or the
     * mock board reset.
     *
     * @param project the project's name
     * @return false for a project not given
     */
    public boolean hold(final String project) {
        try {
            projects.given(project);
        } catch (final Refusal notGiven) {
            return false;
        }
        held.add(project);
        return true;
    }

    /**
     * Answers a project's held saves: the one given, or none.
     *
     * @param project the project's name
     * @return its held saves
     */
    HeldSaves of(final String project) {
        return new HeldSaves() {
            @Override
            public List<CommandOutbox.Group> groups() {
                return held.contains(project) ? List.of(group(project)) : List.of();
            }

            @Override
            public CommandOutcome resend(final String group) {
                taken(project, group);
                return new CommandOutcome.Done(null, Map.of());
            }

            @Override
            public void drop(final String group) {
                taken(project, group);
            }

            @Override
            public RefusedEventResave runs() {
                return HeldSaves.NO_RUNS;
            }
        };
    }

    private void taken(final String project, final String group) {
        if (!SAVE.equals(group) || !held.remove(project)) {
            throw new IllegalStateException("The project keeps no group " + group + ".");
        }
    }

    private CommandOutbox.Group group(final String project) {
        final Projects.Given given = projects.given(project);
        final String branch = given.branch();
        final CommandOutbox.Why held = new CommandOutbox.Why("Unreachable", "", "");
        return new CommandOutbox.Group(
                new CommandOutbox.Source(given.repository(), branch == null ? "" : branch),
                SAVE,
                List.of(
                        new CommandOutbox.Told(
                                SAVE + "/0",
                                "write",
                                ISSUE,
                                CommandOutbox.State.HELD,
                                held.failure(),
                                List.of(),
                                priority())),
                false,
                "",
                List.of(
                        new CommandOutbox.Try(
                                FIRST,
                                CommandOutbox.State.REFUSED,
                                new CommandOutbox.Why("Unreachable", "", ""),
                                CommandOutbox.Answer.NONE),
                        new CommandOutbox.Try(
                                FIRST.plusSeconds(300),
                                CommandOutbox.State.REFUSED,
                                new CommandOutbox.Why("CredentialRefused", "", ""),
                                new CommandOutbox.Answer(401, "Bad credentials", "")),
                        new CommandOutbox.Try(
                                FIRST.plusSeconds(600),
                                CommandOutbox.State.HELD,
                                held,
                                new CommandOutbox.Answer(
                                        502, "Server Error; Try again later", REQUEST_ID))));
    }

    private static CommandOutbox.Changes priority() {
        final Map<String, Writes.Reason> none = Map.of();
        return new CommandOutbox.Changes(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(ISSUE, PRIORITY),
                none,
                none,
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                false,
                List.of(),
                List.of(),
                "",
                List.of());
    }
}
