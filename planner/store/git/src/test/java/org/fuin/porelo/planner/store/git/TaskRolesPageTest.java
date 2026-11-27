package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.fuin.porelo.planner.core.WorkRules;
import org.fuin.porelo.planner.store.Role;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Workflow;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The example and the roles table of the page on task roles, read as the planner reads them. */
@Tag("documents")
class TaskRolesPageTest {

    /** The page, from the repository's root. */
    private static final String PAGE = "doc/task-roles.md";

    private static final String OPEN = "```yaml\n";

    /** A workflow holding every state the example names, Finished its one closed state. */
    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Backlog", "Building", "Reviewing", "Reworking", "Review", "Finished"),
                    List.of(),
                    List.of("Finished"));

    private static String page() throws IOException {
        return Files.readString(root().resolve(PAGE));
    }

    /** The first fenced YAML block of the page. */
    private static String example() throws IOException {
        final String page = page();
        final int start = page.indexOf(OPEN);
        assertThat(start).as("%s holds a fenced yaml block", PAGE).isNotNegative();
        final int end = page.indexOf("\n```", start + OPEN.length());
        assertThat(end).as("the yaml block of %s closes", PAGE).isPositive();
        return page.substring(start + OPEN.length(), end + 1);
    }

    /** The cells of each row of the first table after the example, its header and rule left out. */
    private static List<List<String>> table() throws IOException {
        final String page = page();
        final String after =
                page.substring(page.indexOf("\n```", page.indexOf(OPEN) + OPEN.length()) + 4);
        final List<List<String>> rows = new ArrayList<>();
        boolean in = false;
        for (final String line : after.split("\n", -1)) {
            if (line.startsWith("|")) {
                in = true;
                final List<String> cells = new ArrayList<>();
                for (final String cell : line.substring(1, line.lastIndexOf('|')).split("\\|")) {
                    cells.add(cell.strip());
                }
                rows.add(cells);
            } else if (in) {
                break;
            }
        }
        assertThat(rows).as("the table after the example of %s", PAGE).hasSizeGreaterThan(2);
        assertThat(rows.get(0).get(0)).as("the table's first column").isEqualTo("Role");
        return rows.subList(2, rows.size());
    }

    /** The repository's root: the first directory upwards from the module holding mkdocs.yml. */
    private static Path root() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("mkdocs.yml"))) {
                return dir;
            }
        }
        throw new IllegalStateException(
                "no mkdocs.yml above " + Path.of("").toAbsolutePath() + ", so no " + PAGE);
    }

    private static Role role(
            final String name,
            final Role.End end,
            final boolean services,
            final Role.WriteAs writeAs,
            final Role.Right... rights) {
        return new Role(
                name,
                end,
                end == Role.End.PUSHES_WORK ? Role.Workspace.OWN : Role.Workspace.FOREIGN,
                false,
                services,
                writeAs,
                List.of(rights));
    }

    /** The word work.yml names an end kind by. */
    private static String word(final Role.End end) {
        return switch (end) {
            case PUSHES_WORK -> "push";
            case REPORTS_FINDINGS -> "findings";
            case GIVES_POINTS -> "points";
            case HANDS_BACK_RESULT -> "result";
        };
    }

    // Prevents a page that teaches a role the planner refuses or rules it drops, so a person
    // copying the example gives tasks rights they did not mean or starts no work at all.
    @Test
    void thePagesExampleLoadsCleanWithItsFourRolesAndItsRulesAreSound() throws Exception {
        final Work work = WorkReader.read(example());
        assertThat(work.faults()).as("the example loads clean").isEmpty();
        final Map<String, Role> roles = new LinkedHashMap<>();
        roles.put(
                "author",
                role(
                        "author",
                        Role.End.PUSHES_WORK,
                        true,
                        Role.WriteAs.AUTHOR,
                        Role.Right.PUSH,
                        Role.Right.REPLY));
        roles.put(
                "reviewer",
                role(
                        "reviewer",
                        Role.End.GIVES_POINTS,
                        false,
                        Role.WriteAs.REVIEW,
                        Role.Right.REPLY,
                        Role.Right.OPEN_THREADS));
        roles.put(
                "tester",
                role(
                        "tester",
                        Role.End.REPORTS_FINDINGS,
                        false,
                        Role.WriteAs.REVIEW,
                        Role.Right.OPEN_THREADS,
                        Role.Right.REPORT));
        roles.put(
                "screener",
                role(
                        "screener",
                        Role.End.GIVES_POINTS,
                        false,
                        Role.WriteAs.REVIEW,
                        Role.Right.REPLY,
                        Role.Right.OPEN_THREADS,
                        Role.Right.REJECT));
        assertThat(work.roles())
                .as("the four roles with their parts, also those no rule offers")
                .containsExactlyEntriesOf(roles);
        final Map<String, String> offered = new LinkedHashMap<>();
        for (final Map.Entry<String, WorkRule> rule : work.rules().entrySet()) {
            offered.put(rule.getKey(), rule.getValue().role().name());
        }
        final Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Building", "author");
        expected.put("Reviewing", "reviewer");
        expected.put("Reworking", "author");
        assertThat(offered)
                .as("each rule's role, in the file's order")
                .containsExactlyEntriesOf(expected);
        final WorkRules.Checked checked = WorkRules.check(WORKFLOW, work);
        assertThat(checked.faults()).as("every rule sound against the workflow").isEmpty();
        assertThat(checked.rules().keySet())
                .as("every rule kept")
                .containsExactly("Building", "Reviewing", "Reworking");
    }

    // Prevents a table that tells a person other parts than the example gives its roles.
    @Test
    void thePagesRolesTableShowsEachRoleAsTheExampleLoadsIt() throws Exception {
        final Map<String, Role> roles = WorkReader.read(example()).roles();
        final List<List<String>> rows = table();
        assertThat(rows.stream().map(row -> row.get(0)).toList())
                .as("the table's roles, in the example's order")
                .containsExactlyElementsOf(roles.keySet());
        for (final List<String> row : rows) {
            final Role role = roles.get(row.get(0));
            assertThat(row)
                    .as("the row of %s", row.get(0))
                    .containsExactly(
                            role.name(),
                            word(role.end()),
                            role.workspace().word(),
                            role.projectServices() ? "yes" : "no",
                            role.writeAs().word(),
                            role.rights().stream()
                                    .map(Role.Right::word)
                                    .collect(Collectors.joining(", ")));
        }
    }

    // Prevents the tests above from passing over a block they never read.
    @Test
    void aWordThePlannerDoesNotKnowInThePagesExampleIsAFault() throws Exception {
        final String example = example();
        assertThat(example).as("the author's end, once").containsOnlyOnce("end: push");
        final Work work = WorkReader.read(example.replace("end: push", "end: verdict"));
        assertThat(work.faults())
                .as("the role and each rule offering it")
                .containsExactly(
                        "stories/work.yml: role author: end 'verdict' is none of push, findings,"
                                + " points and result",
                        "stories/work.yml: Building: role 'author' did not load",
                        "stories/work.yml: Reworking: role 'author' did not load");
    }
}
