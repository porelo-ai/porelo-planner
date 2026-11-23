package org.fuin.porelo.planner.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.ProjectRepository;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.WorkRule;
import org.jspecify.annotations.Nullable;

/**
 * One task that discusses an idea with a person over every repository of the project, read only,
 * and hands back the idea in the planner's idea format; offering it records and writes nothing.
 *
 * @param agent the default agent, the first the first sound work rule names for ordinary work, as a
 *     discussion has no issue
 * @param model that agent's model
 * @param effort the effort that rule names for ordinary work, or null when it names none
 * @param mode the mode a conversation runs in
 * @param prompt the complete brief
 * @param basis where the agent, model and effort came from in words
 */
public record IdeaDiscussion(
        String agent,
        String model,
        @Nullable String effort,
        String mode,
        String prompt,
        String basis) {

    /** The name of the file the task hands back. */
    public static final String RESULT = "idea.json";

    /** What stands in the idea format where a project's repositories' names go. */
    private static final String REPOSITORIES = "[\"{repositories}\"]";

    /** The idea format, {@code idea-1.json}, a JSON schema, without the project's names. */
    private static final String TEMPLATE = template();

    private static final int MOST = 4000;

    private static final int PROMPT = 65536;

    /**
     * Offers the discussion of an idea.
     *
     * @param board the project's board
     * @param idea the person's first words of the idea
     * @return the offer
     * @throws WorkRefusal when the words are empty or too long, or no sound work rule offers an
     *     agent
     */
    public static IdeaDiscussion offer(final Board board, final String idea) {
        if (idea.isBlank() || idea.codePointCount(0, idea.length()) > MOST) {
            throw WorkRefusal.invalidSettings();
        }
        if (board.repositories().isEmpty()) {
            throw WorkRefusal.noDiscussion("The project names no repository to discuss.");
        }
        for (final Map.Entry<String, WorkRule> rule :
                WorkRules.check(board.workflow(), board.work()).rules().entrySet()) {
            final Choice first = Choice.of(rule.getKey(), rule.getValue(), Risk.ORDINARY);
            if (named(first.agent()) && named(first.model())) {
                final String prompt = brief(board, idea);
                if (prompt.getBytes(StandardCharsets.UTF_8).length > PROMPT) {
                    throw WorkRefusal.noDiscussion("The brief exceeds 65536 UTF-8 bytes.");
                }
                return new IdeaDiscussion(
                        first.agent(),
                        first.model(),
                        first.effort(),
                        "AGENT",
                        prompt,
                        first.basis());
            }
        }
        throw WorkRefusal.noDiscussion("The project has no sound work rule that offers an agent.");
    }

    /**
     * The idea format for a project: {@code idea-1.json}, a JSON schema that allows only the
     * project's repositories by name.
     *
     * @param repositories the names of the project's repositories, each checked as one
     * @return the format
     */
    public static String format(final List<String> repositories) {
        // Each name was checked as a repository's name, so it needs no escaping in JSON.
        return TEMPLATE.replace(
                REPOSITORIES,
                repositories.stream()
                        .map(name -> "\"" + name + "\"")
                        .collect(Collectors.joining(", ", "[", "]")));
    }

    private static boolean named(final String name) {
        return !name.isBlank() && name.length() <= 128;
    }

    private static String brief(final Board board, final String idea) {
        final StringBuilder places = new StringBuilder();
        for (final ProjectRepository repository : board.repositories()) {
            places.append("- `")
                    .append(repository.name())
                    .append("`, read at `/workspace/")
                    .append(repository.folder())
                    .append("/`\n");
        }
        final String fence = fence(idea);
        return "Discuss an idea with the person and, once the person agrees, write it down as one"
                + " story and an issue for each repository it concerns.\n\n"
                + "The idea in the person's first words:\n"
                + fence
                + "\n"
                + idea
                + "\n"
                + fence
                + "\n\nThe project's repositories are here, each by the name an issue's"
                + " repository carries and the folder it is read at, read only; read what the"
                + " discussion needs and change nothing:\n"
                + places
                + "\nAsk the person until the story's title, what a user gains, the test that shows"
                + " it works and its priority are clear, and which repositories it concerns. For"
                + " each issue ask whether an agent or the person works it, and write `Agent` or"
                + " `Manual` as the person answers; never choose it yourself. Give a size only when"
                + " it is known.\n\n"
                + "When the person agrees, write the idea as this task's result, "
                + RESULT
                + ", at most 1 MiB of JSON valid against this schema:\n```json\n"
                + format(board.names())
                + "```\n";
    }

    private static String fence(final String text) {
        int longest = 0;
        int run = 0;
        for (int index = 0; index < text.length(); index++) {
            run = text.charAt(index) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return "`".repeat(Math.max(3, longest + 1));
    }

    private static String template() {
        final String template;
        try (InputStream in =
                Objects.requireNonNull(
                        IdeaDiscussion.class.getResourceAsStream("idea-1.json"),
                        "idea-1.json is not on the class path")) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("idea-1.json cannot be read", e);
        }
        if (template.indexOf(REPOSITORIES) < 0
                || template.indexOf(REPOSITORIES) != template.lastIndexOf(REPOSITORIES)) {
            throw new IllegalStateException("idea-1.json does not name its repositories once");
        }
        return template;
    }
}
