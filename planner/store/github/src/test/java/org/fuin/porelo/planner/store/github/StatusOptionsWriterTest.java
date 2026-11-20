package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.forge.github.Batch;
import org.fuin.porelo.forge.github.GitHubExceptions;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.GitHubGraphQl.BoardIssue;
import org.fuin.porelo.forge.github.GitHubGraphQl.FieldOption;
import org.fuin.porelo.forge.github.GitHubGraphQl.FieldValue;
import org.fuin.porelo.forge.github.GitHubGraphQl.IssueRef;
import org.fuin.porelo.forge.github.GitHubGraphQl.IssueRefs;
import org.fuin.porelo.forge.github.GitHubGraphQl.ItemValue;
import org.fuin.porelo.forge.github.GitHubGraphQl.PageInfo;
import org.fuin.porelo.forge.github.GitHubGraphQl.ProjectField;
import org.fuin.porelo.forge.github.GitHubGraphQl.ProjectItem;
import org.fuin.porelo.forge.github.GraphQlCost;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The project's Status options made the workflow's states without an item losing its Status. */
class StatusOptionsWriterTest {

    private static final String TOKEN = "github_pat_options_test_0123456789";

    private static final String OWNER = "example-org";

    private static final GraphQlCost COST = new GraphQlCost(1, 4999, Instant.EPOCH);

    /** The workflow after a change: Done renamed to Finished, Backlog added. */
    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Idea", "Analysis", "Backlog", "Finished"),
                    List.of(
                            new Workflow.Phase(
                                    "All", List.of("Idea", "Analysis", "Backlog", "Finished"))),
                    List.of("Finished"));

    private static final ProjectConfig CONFIG =
            new ProjectConfig(
                    URI.create("https://github.com"),
                    new ProjectRef(OWNER, 7),
                    "c0ffee0",
                    WORKFLOW,
                    new Work(Map.of(), List.of()),
                    List.of("example-lib"),
                    Map.of(),
                    Map.of(),
                    "PVT_7");

    /**
     * GitHub's side: the field Status and every item's value. Setting the options gives every
     * option a new id and clears every value, as measured on GitHub.
     */
    private static final class GitHub implements ProjectQueries, ProjectWrites {
        private List<FieldOption> options = new ArrayList<>();
        private final Map<String, @Nullable BoardIssue> items = new LinkedHashMap<>();
        private final Map<String, String> values = new LinkedHashMap<>();
        private final List<Integer> batches = new ArrayList<>();
        private int optionsSet;

        /**
         * Which setFieldValues call fails after taking the first ten values, from 1; 0 for none.
         */
        private int failing;

        /** Whether every setFieldValues call fails before taking any value. */
        private boolean refusingAll;

        /** What every setFieldValues call throws before taking any value, or null. */
        private @Nullable RuntimeException refusal;

        /** What a person does right after the step's first read, or null. */
        private @Nullable Runnable afterFirstRead;

        /** What happens on GitHub right after the options are set, or null. */
        private @Nullable Runnable afterOptions;

        /** Whether setFieldOptions sets the options and then fails without answering. */
        private boolean optionsUnanswered;

        /** Whether the items' pages say another follows without moving their cursor. */
        private boolean stuckCursor;

        private int reads;

        private GitHub options(final String... names) {
            for (final String name : names) {
                options.add(new FieldOption("O0_" + name, name, "GREEN", "kept " + name));
            }
            return this;
        }

        private GitHub item(final int number, final @Nullable String status) {
            final String itemId = "PVTI_" + number;
            items.put(itemId, issue(number));
            if (status != null) {
                values.put(itemId, optionId(status));
            }
            return this;
        }

        private GitHub draft(final String status) {
            items.put("PVTI_draft", null);
            values.put("PVTI_draft", optionId(status));
            return this;
        }

        private String optionId(final String name) {
            return options.stream()
                    .filter(option -> option.name().equals(name))
                    .findFirst()
                    .orElseThrow()
                    .id();
        }

        /** Every item's Status by name, null for none. */
        private Map<String, @Nullable String> statuses() {
            final Map<String, @Nullable String> names = new LinkedHashMap<>();
            for (final String itemId : items.keySet()) {
                final String optionId = values.get(itemId);
                names.put(
                        itemId,
                        optionId == null
                                ? null
                                : options.stream()
                                        .filter(option -> option.id().equals(optionId))
                                        .findFirst()
                                        .map(FieldOption::name)
                                        .orElse(null));
            }
            return names;
        }

        private StatusOptionsWriter writer() {
            return new StatusOptionsWriter(this, this, origin -> TOKEN, AccessRefusals.DEFAULT);
        }

        @Override
        public GitHubGraphQl.ProjectFields projectFields(
                final String token,
                final String owner,
                final int number,
                final @Nullable String after) {
            assertThat(token).as("the token of the read").isEqualTo(TOKEN);
            return new GitHubGraphQl.ProjectFields(
                    "PVT_7",
                    "Plan",
                    Instant.EPOCH,
                    List.of(
                            new ProjectField(
                                    "F_status", FieldNames.STATUS, "SINGLE_SELECT", options),
                            new ProjectField("F_id", FieldNames.ID, "TEXT", List.of())),
                    new PageInfo(false, null),
                    COST);
        }

        @Override
        public GitHubGraphQl.ProjectItems projectItems(
                final String token,
                final String owner,
                final int number,
                final @Nullable String after) {
            final List<String> ids = new ArrayList<>(items.keySet());
            final int from = after == null ? 0 : Integer.parseInt(after);
            final int to = Math.min(ids.size(), from + ItemsBoardReader.PAGE_SIZE);
            final List<ProjectItem> page = new ArrayList<>();
            for (final String itemId : ids.subList(from, to)) {
                final String optionId = values.get(itemId);
                final List<ItemValue> held = new ArrayList<>();
                if (optionId != null) {
                    final String name =
                            options.stream()
                                    .filter(option -> option.id().equals(optionId))
                                    .findFirst()
                                    .orElseThrow()
                                    .name();
                    held.add(new ItemValue("F_status", optionId, name, null, null));
                }
                page.add(new ProjectItem(itemId, false, items.get(itemId), held));
            }
            final boolean more = to < ids.size();
            if (stuckCursor) {
                return new GitHubGraphQl.ProjectItems(page, new PageInfo(true, "0"), COST);
            }
            if (!more && reads++ == 0 && afterFirstRead != null) {
                afterFirstRead.run();
            }
            return new GitHubGraphQl.ProjectItems(
                    page, new PageInfo(more, more ? Integer.toString(to) : null), COST);
        }

        @Override
        public GitHubGraphQl.FieldOptions setFieldOptions(
                final String token,
                final String fieldId,
                final List<GitHubGraphQl.NewOption> given) {
            assertThat(fieldId).as("the field whose options are set").isEqualTo("F_status");
            optionsSet++;
            options =
                    new ArrayList<>(
                            given.stream()
                                    .map(
                                            option ->
                                                    new FieldOption(
                                                            "O" + optionsSet + "_" + option.name(),
                                                            option.name(),
                                                            option.color(),
                                                            option.description()))
                                    .toList());
            values.clear();
            if (afterOptions != null) {
                afterOptions.run();
            }
            if (optionsUnanswered) {
                throw new IllegalStateException("GitHub took the options without answering");
            }
            return new GitHubGraphQl.FieldOptions(options, null);
        }

        @Override
        public GitHubGraphQl.IssueBoard issueBoard(
                final String token, final List<String> issueIds, final String projectId) {
            throw new AssertionError("the step reads the whole project, not issues by id");
        }

        @Override
        public Batch<String> setFieldValues(
                final String token, final String projectId, final List<FieldValue> given) {
            batches.add(given.size());
            if (refusal != null) {
                throw refusal;
            }
            if (refusingAll) {
                throw new IllegalStateException("GitHub refused the mutation");
            }
            final List<Batch.Outcome<String>> outcomes = new ArrayList<>();
            for (int at = 0; at < given.size(); at++) {
                if (batches.size() == failing && at == 10) {
                    // GitHub keeps the parts it took before a call that never answers.
                    throw new IllegalStateException("GitHub took the call without answering");
                }
                final FieldValue value = given.get(at);
                values.put(value.itemId(), java.util.Objects.requireNonNull(value.optionId()));
                outcomes.add(Batch.Outcome.of(value.itemId()));
            }
            return new Batch<>(outcomes, null);
        }

        @Override
        public void updateIssue(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final @Nullable String body,
                final @Nullable Opening opening) {
            throw new AssertionError("the step writes no issue");
        }

        @Override
        public CommentsPage issueComments(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final int page) {
            throw new AssertionError("the step reads no comment");
        }

        @Override
        public void createIssueComment(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final String body) {
            throw new AssertionError("the step writes no comment");
        }
    }

    private static BoardIssue issue(final int number) {
        final IssueRefs none = new IssueRefs(List.<IssueRef>of(), new PageInfo(false, null));
        return new BoardIssue(
                "I_" + number,
                1L,
                number,
                "Title " + number,
                "",
                "OPEN",
                null,
                Instant.EPOCH,
                OWNER + "/example-lib",
                List.of(),
                null,
                none,
                none);
    }

    /** 120 items holding the old states in turn, one without a Status and one draft. */
    private static GitHub project() {
        final GitHub github = new GitHub().options("Idea", "Analysis", "Done");
        final List<String> old = List.of("Idea", "Analysis", "Done");
        for (int number = 1; number <= 120; number++) {
            github.item(number, old.get(number % 3));
        }
        return github.item(121, null).draft("Analysis");
    }

    @Test
    void newOptionsForAChangedWorkflowLeaveEveryItemItsStatusByName() {
        // Harm: every issue's state wiped when the workflow changes, so the whole board falls back
        // to Idea, or a state the workflow dropped silently lost.
        final GitHub github = project();
        final Map<String, @Nullable String> before = github.statuses();

        final StatusOptionsWriter.Applied applied = github.writer().apply(CONFIG);

        assertThat(applied.changed()).as("the options were set").isTrue();
        assertThat(github.options)
                .as("the workflow's states in order, the dropped one kept at the end")
                .extracting(FieldOption::name)
                .containsExactly("Idea", "Analysis", "Backlog", "Finished", "Done");
        assertThat(github.options.get(4).color())
                .as("the color a person gave the kept option")
                .isEqualTo("GREEN");
        assertThat(github.statuses())
                .as("every item's Status by name, none without one that had one")
                .isEqualTo(before);
        assertThat(github.batches)
                .as("the values written back, in batches GitHub takes")
                .allSatisfy(size -> assertThat(size).as("one batch").isLessThanOrEqualTo(50));
        assertThat(applied.faults())
                .as("one fault per item holding the dropped state")
                .hasSize(40)
                .allSatisfy(
                        fault ->
                                assertThat(fault)
                                        .as("a fault")
                                        .startsWith("Item example-org/example-lib#")
                                        .contains("Status outside the workflow"));
    }

    @Test
    void aBatchThatFailedIsWrittenAgainFromAFreshRead() {
        // Harm: the items of a batch GitHub took without answering left without their state.
        final GitHub github = project();
        github.failing = 2;
        final Map<String, @Nullable String> before = github.statuses();

        github.writer().apply(CONFIG);

        assertThat(github.statuses())
                .as("every item's Status after the failed batch was written again")
                .isEqualTo(before);
        assertThat(github.optionsSet).as("the options set once").isEqualTo(1);
    }

    @Test
    void optionsThatStandAsWantedAreNotSetAgain() {
        // Harm: a second run clearing every value again for nothing, at the risk of losing them.
        final GitHub github = project();
        github.writer().apply(CONFIG);
        final int batches = github.batches.size();

        final StatusOptionsWriter.Applied again = github.writer().apply(CONFIG);

        assertThat(again.changed()).as("the second run set the options").isFalse();
        assertThat(github.optionsSet).as("the options set in both runs").isEqualTo(1);
        assertThat(github.batches.size()).as("values written by the second run").isEqualTo(batches);
        assertThat(again.faults()).as("the dropped state still named").hasSize(40);
    }

    @Test
    void valuesGitHubNeverTakesBackAreNamedForAPersonToSet() {
        // Harm: items left without their state and nobody told which state each held.
        final GitHub github = new GitHub().options("Idea", "Done").item(1, "Done").item(2, "Idea");
        github.refusingAll = true;

        assertThatThrownBy(() -> github.writer().apply(CONFIG))
                .as("a write-back GitHub refuses each time")
                .isInstanceOf(StoreException.PartlySaved.class)
                .hasMessageContaining(
                        "Item example-org/example-lib#1 must be set to the option in place 5 of"
                                + " Status")
                .hasMessageContaining("Item example-org/example-lib#2 must be set to Idea")
                .hasMessageNotContaining(TOKEN);
    }

    @Test
    void anOptionOutsideTheWorkflowStaysWhileAnItemMustBeSetToItByHand() {
        // Harm: a person told to set an item to an option the next run removed, so the item's
        // state is lost for good.
        final GitHub github = new GitHub().options("Idea", "Done").item(1, "Done").item(2, "Idea");
        github.refusingAll = true;
        assertThatThrownBy(() -> github.writer().apply(CONFIG))
                .as("a write-back GitHub refuses each time")
                .isInstanceOf(StoreException.PartlySaved.class);
        github.refusingAll = false;

        github.writer().apply(CONFIG);

        assertThat(github.options)
                .as("the options after the next run, the one to set by hand in place 5")
                .extracting(FieldOption::name)
                .containsExactly("Idea", "Analysis", "Backlog", "Finished", "Done");
    }

    @Test
    void anOptionAPersonAddedThatNoItemHoldsIsKeptAtTheEnd() {
        // Harm: an option a person made on GitHub deleted by the planner.
        final GitHub github =
                new GitHub().options("Blocked", "Idea", "Done").item(1, "Done").item(2, "Idea");

        github.writer().apply(CONFIG);

        assertThat(github.options)
                .as("the workflow's states, then the options outside it in their order")
                .extracting(FieldOption::name)
                .containsExactly("Idea", "Analysis", "Backlog", "Finished", "Blocked", "Done");
    }

    @Test
    void aValueAPersonSetsBeforeTheOptionsAreSetStopsTheStep() {
        // Harm: a Status a person set while the step ran overwritten with the one it held before.
        final GitHub github = project();
        github.afterFirstRead = () -> github.values.put("PVTI_1", github.optionId("Idea"));

        assertThatThrownBy(() -> github.writer().apply(CONFIG))
                .as("the step after a person set a value")
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageContaining("changed");
        assertThat(github.optionsSet).as("the options set").isZero();
        assertThat(github.statuses().get("PVTI_1"))
                .as("the value the person set")
                .isEqualTo("Idea");
    }

    @Test
    void aValueAPersonSetsAfterTheOptionsAreSetStands() {
        // Harm: a Status a person set right after the options changed overwritten with the old one.
        final GitHub github = project();
        github.afterOptions = () -> github.values.put("PVTI_1", github.optionId("Backlog"));

        github.writer().apply(CONFIG);

        assertThat(github.statuses().get("PVTI_1"))
                .as("the value the person set")
                .isEqualTo("Backlog");
    }

    @Test
    void optionsGitHubSetWithoutAnsweringStillGetEveryValueBack() {
        // Harm: every item's Status cleared when GitHub took the options but its answer was lost.
        final GitHub github = project();
        github.optionsUnanswered = true;
        final Map<String, @Nullable String> before = github.statuses();

        assertThatThrownBy(() -> github.writer().apply(CONFIG))
                .as("the step whose options call was not answered")
                .isInstanceOf(StoreException.class);
        assertThat(github.statuses()).as("every item's Status by name").isEqualTo(before);
    }

    @Test
    void aProjectThatCannotBeReadWholeKeepsItsOptions() {
        // Harm: values written back over part of the project, the rest cleared for good.
        final GitHub github = project();
        github.stuckCursor = true;

        assertThatThrownBy(() -> github.writer().apply(CONFIG))
                .as("the step over a project read in part")
                .isInstanceOf(StoreException.Unreadable.class);
        assertThat(github.optionsSet).as("the options set").isZero();
    }

    @Test
    void anItemRemovedAfterTheOptionsAreSetIsNotMissed() {
        // Harm: a step that set every value it could reported as failed, sending a person to set
        // an item that no longer stands.
        final GitHub github = project();
        github.afterOptions = () -> github.items.remove("PVTI_2");

        final StatusOptionsWriter.Applied applied = github.writer().apply(CONFIG);

        assertThat(applied.changed()).as("the options were set").isTrue();
    }

    @Test
    void optionsInAnotherOrderAreSetInTheWorkflowsOrder() {
        // Harm: the board's columns in an order the workflow does not have.
        final GitHub github =
                new GitHub()
                        .options("Analysis", "Idea", "Backlog", "Finished")
                        .item(1, "Idea")
                        .item(2, "Analysis");
        final Map<String, @Nullable String> before = github.statuses();

        final StatusOptionsWriter.Applied applied = github.writer().apply(CONFIG);

        assertThat(applied.changed()).as("the options were set").isTrue();
        assertThat(github.options)
                .as("the options in the workflow's order")
                .extracting(FieldOption::name)
                .containsExactly("Idea", "Analysis", "Backlog", "Finished");
        assertThat(github.statuses()).as("every item's Status by name").isEqualTo(before);
    }

    @Test
    void anOptionStandingTwiceBecomesOneAndItsItemsKeepIt() {
        // Harm: an item holding the second of two options of one name losing its Status.
        final GitHub github = new GitHub().options("Idea", "Analysis", "Backlog", "Finished");
        github.options.add(new FieldOption("O0_Idea_twice", "Idea", "RED", ""));
        github.item(1, "Analysis");
        github.items.put("PVTI_2", issue(2));
        github.values.put("PVTI_2", "O0_Idea_twice");
        final Map<String, @Nullable String> before = github.statuses();

        github.writer().apply(CONFIG);

        assertThat(github.options)
                .as("one option per name")
                .extracting(FieldOption::name)
                .containsExactly("Idea", "Analysis", "Backlog", "Finished");
        assertThat(github.statuses()).as("every item's Status by name").isEqualTo(before);
    }

    @Test
    void aDraftIsNamedByItsProjectItemAndNoOptionNameAPersonTypedIsRepeated() {
        // Harm: a person unable to tell which draft to set, or words a person typed into an
        // option's name repeated where the planner's own messages go.
        final GitHub github =
                new GitHub().options("Idea", "secret-typed").item(1, "secret-typed").draft("Idea");

        final StatusOptionsWriter.Applied applied = github.writer().apply(CONFIG);
        assertThat(applied.faults())
                .as("the faults")
                .singleElement()
                .asString()
                .doesNotContain("secret-typed")
                .contains("Item example-org/example-lib#1");

        final GitHub refusing =
                new GitHub().options("Idea", "secret-typed").item(1, "secret-typed").draft("Idea");
        refusing.refusingAll = true;
        assertThatThrownBy(() -> refusing.writer().apply(CONFIG))
                .as("a write-back GitHub refuses each time")
                .isInstanceOf(StoreException.PartlySaved.class)
                .hasMessageContaining(
                        "Project item PVTI_draft without an issue must be set to Idea")
                .hasMessageContaining(
                        "Item example-org/example-lib#1 must be set to the option in place 5 of"
                                + " Status")
                .hasMessageNotContaining("secret-typed");
    }

    @Test
    void aRefusalOfAccessOrASpentRateLimitIsNotTriedAgain() {
        // Harm: calls spent against a rate limit or a refused token, and the person told GitHub
        // did not take the values instead of why.
        for (final RuntimeException refusal :
                List.of(
                        GitHubExceptions.rateLimited(
                                403, "rate limit exceeded", java.time.Duration.ofMinutes(5)),
                        GitHubExceptions.refused(401, "Bad credentials"))) {
            final GitHub github =
                    new GitHub().options("Idea", "Done").item(1, "Done").item(2, "Idea");
            github.refusal = refusal;

            assertThatThrownBy(() -> github.writer().apply(CONFIG))
                    .as("a write-back GitHub refuses with %s", refusal.getMessage())
                    .isInstanceOf(StoreException.PartlySaved.class)
                    .hasMessageNotContaining("did not take them")
                    .hasMessageContaining("Item example-org/example-lib#2 must be set to Idea");
            assertThat(github.batches).as("the write-back calls").hasSize(1);
        }
    }
}
