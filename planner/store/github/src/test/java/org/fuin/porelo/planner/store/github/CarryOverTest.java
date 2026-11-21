package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.fuin.porelo.forge.github.Batch;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.GitHubGraphQl.BoardIssue;
import org.fuin.porelo.forge.github.GitHubGraphQl.FieldOption;
import org.fuin.porelo.forge.github.GitHubGraphQl.FieldValue;
import org.fuin.porelo.forge.github.GitHubGraphQl.IssueRefs;
import org.fuin.porelo.forge.github.GitHubGraphQl.ItemValue;
import org.fuin.porelo.forge.github.GitHubGraphQl.PageInfo;
import org.fuin.porelo.forge.github.GitHubGraphQl.ProjectField;
import org.fuin.porelo.forge.github.GitHubGraphQl.ProjectItem;
import org.fuin.porelo.forge.github.GraphQlCost;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.StageEntry;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A Markdown project carried over to a GitHub project, against GitHub in memory. */
class CarryOverTest {

    private static final String TOKEN = "github_pat_carry_over_test_0123456789";

    private static final String OWNER = "porelo-ai";

    private static final String OWN = "porelo-project";

    private static final String PLANNER = "porelo-planner";

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private static final GraphQlCost COST = new GraphQlCost(1, 4999, Instant.EPOCH);

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Backlog", "Implementing", "Finished", "Rejected"),
                    List.of(),
                    List.of("Finished", "Rejected"));

    private static final ProjectConfig CONFIG =
            new ProjectConfig(
                    URI.create("https://github.com"),
                    new ProjectRef(OWNER, 7),
                    "carry-over",
                    WORKFLOW,
                    new Work(Map.of(), List.of()),
                    List.of(OWN, PLANNER),
                    Map.of(),
                    Map.of());

    private static final Map<String, String> WEBS =
            Map.of(
                    OWN, "https://github.com/porelo-ai/porelo-project",
                    PLANNER, "https://github.com/porelo-ai/porelo-planner");

    /** The ids carried, none of which may stand anywhere on GitHub. */
    private static final List<String> CARRIED = List.of("ST1", "ST2", "PL10", "PL11", "PL11.1");

    // ---- the Markdown project ----

    /** A title that names no id, so an old id found on GitHub came from elsewhere. */
    private static String title(final String id) {
        return "The work of " + id.toLowerCase(java.util.Locale.ROOT).replace('.', '-');
    }

    private static Issue issue(
            final String id,
            final String state,
            final List<String> blockedBy,
            final Function<String, String> titles) {
        final int dot = id.indexOf('.');
        return new Issue(
                id,
                PLANNER,
                titles.apply(id),
                state,
                "S",
                blockedBy,
                dot < 0 ? null : id.substring(0, dot),
                null,
                "# " + id);
    }

    private static Story story(final String id, final boolean given, final List<String> issues) {
        return new Story(id, title(id), "Backlog", given, "High", "M", issues, "", null, true);
    }

    private static Board board(final Function<String, String> titles) {
        return new Board(
                new Revision("one"),
                WORKFLOW,
                List.of(
                        story("ST1", true, List.of("PL10", "PL11.1", "PL9")),
                        story("ST2", false, List.of("PL10"))),
                List.of(
                        issue("PL9", "Finished", List.of(), titles),
                        issue("PL10", "Backlog", List.of("PL9", "PL11"), titles),
                        issue("PL11", "Implementing", List.of(), titles),
                        issue("PL11.1", "Backlog", List.of(), titles)),
                new Work(Map.of(), List.of()),
                List.of(OWN, PLANNER),
                List.of());
    }

    private static StageEntry stage(final int n, final String kind, final double minutes) {
        return new StageEntry(
                "carry:" + kind + ":" + n,
                kind,
                "done",
                "agent",
                "opus",
                "high",
                minutes,
                1,
                Map.of("claude_input_tokens", 100L));
    }

    private static Function<CarryOverPlan.References, CarryOverPlan> planner(
            final Function<String, String> titles) {
        final Board board = board(titles);
        final Map<String, CarryOverPlan.IssueSource> sources = new LinkedHashMap<>();
        for (final Issue issue : CarryOverPlan.carried(board)) {
            final boolean staged = issue.id().equals("PL11");
            sources.put(
                    issue.id(),
                    new CarryOverPlan.IssueSource(
                            issue,
                            "issues/" + issue.id() + "-X.md",
                            "Medium",
                            3,
                            "It covers the work.",
                            "## Plan\n\nBuilt on [PL9](README.md#pl9) and"
                                    + " [PL11](README.md#pl11).",
                            staged
                                    ? List.of(stage(1, "implement", 10), stage(1, "review", 5))
                                    : List.of(),
                            0,
                            staged ? 25.0 : null));
        }
        final Map<String, String> texts =
                Map.of(
                        "ST1",
                        "**What a user gains.** It needs"
                                + " [PL11](https://github.com/porelo-ai/porelo-planner/blob/main/"
                                + "issues/README.md#pl11).",
                        "ST2",
                        "**What a user gains.** Gain.");
        return references -> CarryOverPlan.of(board, sources, texts, WEBS, references);
    }

    // ---- GitHub in memory ----

    /** One issue as GitHub holds it. */
    private static final class Held {
        final String nodeId;
        final long databaseId;
        final String repository;
        final int number;
        final String title;
        String body;
        final List<String> labels;
        final List<Long> subs = new ArrayList<>();
        final List<Long> blockers = new ArrayList<>();
        final List<String> comments = new ArrayList<>();

        Held(
                final String nodeId,
                final long databaseId,
                final String repository,
                final int number,
                final String title,
                final String body,
                final List<String> labels) {
            this.nodeId = nodeId;
            this.databaseId = databaseId;
            this.repository = repository;
            this.number = number;
            this.title = title;
            this.body = body;
            this.labels = new ArrayList<>(labels);
        }

        ProjectWrites.Created made() {
            return new ProjectWrites.Created(nodeId, databaseId, number, labels);
        }

        ProjectWrites.Found found() {
            return new ProjectWrites.Found(made(), title, body);
        }
    }

    /** Thrown where a call is cut off, as a run that stops there. */
    private static final class Cut extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Cut() {
            super("the connection was lost");
        }
    }

    private static class GitHub implements ProjectQueries, ProjectWrites, CarryOverCalls {
        final List<Held> issues = new ArrayList<>();
        final Map<String, String> items = new LinkedHashMap<>();
        final Map<String, Map<String, String>> values = new LinkedHashMap<>();
        final List<String> written = new ArrayList<>();
        List<FieldOption> statuses = new ArrayList<>();
        int optionsSet;
        boolean labelsDropped;
        boolean withoutRisk;

        /** The write call, from 1, that is cut off; 0 for none. */
        int cutAt;

        /** Whether the cut write still takes effect on GitHub, its answer lost. */
        boolean takenBeforeCut;

        private int writes;

        /**
         * Refusals as rate limited still to answer the next making of an issue, taking no effect.
         */
        int issuesRateLimited;

        /** Counts a write and cuts it where told; answers whether it takes effect. */
        private void write(final String what) {
            if (what.startsWith("issue ") && issuesRateLimited > 0) {
                issuesRateLimited--;
                throw org.fuin.porelo.forge.github.GitHubExceptions.rateLimited(
                        403, "secondary rate limit", java.time.Duration.ofSeconds(30));
            }
            writes++;
            if (writes == cutAt && !takenBeforeCut) {
                throw new Cut();
            }
            written.add(what);
        }

        private void after() {
            if (writes == cutAt) {
                throw new Cut();
            }
        }

        private Held held(final String repository, final int number) {
            return issues.stream()
                    .filter(issue -> issue.repository.equals(repository) && issue.number == number)
                    .findFirst()
                    .orElseThrow();
        }

        private static ProjectField select(
                final String id, final String name, final String... options) {
            final List<FieldOption> held = new ArrayList<>();
            for (final String option : options) {
                held.add(new FieldOption(id + "_" + option, option, "GRAY", ""));
            }
            return new ProjectField(id, name, "SINGLE_SELECT", held);
        }

        @Override
        public GitHubGraphQl.ProjectFields projectFields(
                final String token,
                final String owner,
                final int number,
                final @Nullable String after) {
            assertThat(token).as("the token").isEqualTo(TOKEN);
            final List<ProjectField> fields = new ArrayList<>();
            fields.add(new ProjectField("F_status", FieldNames.STATUS, "SINGLE_SELECT", statuses));
            fields.add(
                    select(
                            "F_priority",
                            FieldNames.PRIORITY,
                            "Highest",
                            "High",
                            "Medium",
                            "Low",
                            "Lowest"));
            fields.add(select("F_size", FieldNames.SIZE, "XS", "S", "M", "L", "XL"));
            final List<String> risks = new ArrayList<>();
            for (final Risk risk : Risk.values()) {
                risks.add(risk.spelled());
            }
            if (withoutRisk) {
                risks.removeFirst();
            }
            fields.add(select("F_risk", FieldNames.RISK, risks.toArray(String[]::new)));
            fields.add(select("F_worker", FieldNames.WORKER, "Agent", "Manual"));
            for (final String name :
                    List.of(
                            FieldNames.RANK,
                            FieldNames.ESTIMATE,
                            FieldNames.MINUTES,
                            FieldNames.VM_MINUTES,
                            FieldNames.TOKENS,
                            FieldNames.STAGES)) {
                fields.add(new ProjectField("F_" + name, name, "NUMBER", List.of()));
            }
            return new GitHubGraphQl.ProjectFields(
                    "PVT_7", "Plan", Instant.EPOCH, fields, new PageInfo(false, null), COST);
        }

        @Override
        public GitHubGraphQl.ProjectItems projectItems(
                final String token,
                final String owner,
                final int number,
                final @Nullable String after) {
            final List<ProjectItem> page = new ArrayList<>();
            items.forEach(
                    (itemId, nodeId) -> {
                        final Held issue =
                                issues.stream()
                                        .filter(i -> i.nodeId.equals(nodeId))
                                        .findFirst()
                                        .orElseThrow();
                        final List<ItemValue> held = new ArrayList<>();
                        final String status = values.getOrDefault(itemId, Map.of()).get("F_status");
                        if (status != null) {
                            statuses.stream()
                                    .filter(option -> option.id().equals(status))
                                    .findFirst()
                                    .ifPresent(
                                            option ->
                                                    held.add(
                                                            new ItemValue(
                                                                    "F_status",
                                                                    status,
                                                                    option.name(),
                                                                    null,
                                                                    null)));
                        }
                        page.add(
                                new ProjectItem(
                                        itemId,
                                        false,
                                        new BoardIssue(
                                                issue.nodeId,
                                                issue.databaseId,
                                                issue.number,
                                                issue.title,
                                                issue.body,
                                                "OPEN",
                                                null,
                                                Instant.EPOCH,
                                                OWNER + "/" + issue.repository,
                                                issue.labels,
                                                null,
                                                new IssueRefs(List.of(), new PageInfo(false, null)),
                                                new IssueRefs(
                                                        List.of(), new PageInfo(false, null))),
                                        held));
                    });
            return new GitHubGraphQl.ProjectItems(
                    page, new PageInfo(false, null), COST, page.size());
        }

        @Override
        public GitHubGraphQl.IssueBoard issueBoard(
                final String token, final List<String> issueIds, final String projectId) {
            throw new AssertionError("a carry-over reads no issue by id");
        }

        @Override
        public Batch<String> setFieldValues(
                final String token, final String projectId, final List<FieldValue> given) {
            write("values " + given.size());
            for (final FieldValue value : given) {
                values.computeIfAbsent(value.itemId(), any -> new TreeMap<>())
                        .put(
                                value.fieldId(),
                                value.optionId() != null
                                        ? value.optionId()
                                        : value.number() != null
                                                ? value.number().toString()
                                                : String.valueOf(value.text()));
            }
            after();
            return new Batch<>(
                    given.stream().map(value -> Batch.Outcome.of(value.itemId())).toList(), null);
        }

        @Override
        public GitHubGraphQl.FieldOptions setFieldOptions(
                final String token,
                final String fieldId,
                final List<GitHubGraphQl.NewOption> given) {
            write("options");
            optionsSet++;
            statuses =
                    new ArrayList<>(
                            given.stream()
                                    .map(
                                            option ->
                                                    new FieldOption(
                                                            "S" + optionsSet + "_" + option.name(),
                                                            option.name(),
                                                            option.color(),
                                                            option.description()))
                                    .toList());
            values.values().forEach(held -> held.remove("F_status"));
            after();
            return new GitHubGraphQl.FieldOptions(statuses, null);
        }

        @Override
        public void removeSubIssue(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final long subIssueId) {
            throw new AssertionError("the carry-over takes no link out");
        }

        @Override
        public void reprioritizeSubIssue(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final long subIssueId,
                final @Nullable Long afterId,
                final @Nullable Long beforeId) {
            throw new AssertionError("the carry-over takes no link out");
        }

        @Override
        public void removeBlockedBy(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final long blockerId) {
            throw new AssertionError("the carry-over takes no link out");
        }

        @Override
        public void updateIssue(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final @Nullable String title,
                final @Nullable String body,
                final @Nullable Opening opening) {
            assertThat(title).as("the carry-over leaves the title").isNull();
            write("body " + repository + "#" + number);
            held(repository, number).body = java.util.Objects.requireNonNull(body);
            after();
        }

        @Override
        public CommentsPage issueComments(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final int page) {
            return new CommentsPage(
                    page == 1 ? held(repository, number).comments : List.of(), false);
        }

        @Override
        public void createIssueComment(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final String body) {
            write("comment " + repository + "#" + number);
            held(repository, number).comments.add(body);
            after();
        }

        @Override
        public Created createIssue(
                final String token,
                final String owner,
                final String repository,
                final String title,
                final String body,
                final List<String> labels) {
            assertThat(owner).as("the owner").isEqualTo(OWNER);
            write("issue " + repository);
            final int number =
                    (int)
                                    issues.stream()
                                            .filter(issue -> issue.repository.equals(repository))
                                            .count()
                            + 1;
            final Held made =
                    new Held(
                            "I_" + (issues.size() + 1),
                            1000 + issues.size() + 1,
                            repository,
                            number,
                            title,
                            body,
                            labelsDropped ? List.of() : labels);
            issues.add(made);
            after();
            return made.made();
        }

        @Override
        public FoundPage issuesCreatedSince(
                final String token,
                final String owner,
                final String repository,
                final Instant since,
                final int page) {
            return new FoundPage(
                    page == 1
                            ? issues.stream()
                                    .filter(issue -> issue.repository.equals(repository))
                                    .map(Held::found)
                                    .toList()
                            : List.of(),
                    false);
        }

        @Override
        public String body(
                final String token, final String owner, final String repository, final int number) {
            return held(repository, number).body;
        }

        @Override
        public List<Long> subIssues(
                final String token, final String owner, final String repository, final int number) {
            return List.copyOf(held(repository, number).subs);
        }

        @Override
        public void addSubIssue(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final long subIssueId) {
            write("sub " + repository + "#" + number);
            held(repository, number).subs.add(subIssueId);
            after();
        }

        @Override
        public List<Long> blockedBy(
                final String token, final String owner, final String repository, final int number) {
            return List.copyOf(held(repository, number).blockers);
        }

        @Override
        public void addBlockedBy(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final long blockerId) {
            write("blocker " + repository + "#" + number);
            held(repository, number).blockers.add(blockerId);
            after();
        }

        @Override
        public Batch<String> addProjectItems(
                final String token, final String projectId, final List<String> issueIds) {
            assertThat(issueIds).as("the issues added at once").hasSize(1);
            final String issueId = issueIds.getFirst();
            write("item " + issueId);
            final String itemId =
                    items.entrySet().stream()
                            .filter(item -> item.getValue().equals(issueId))
                            .map(Map.Entry::getKey)
                            .findFirst()
                            .orElse("PVTI_" + issueId);
            items.put(itemId, issueId);
            after();
            return new Batch<>(List.of(Batch.Outcome.of(itemId)), null);
        }

        /** Everything GitHub holds, as text, to compare two runs and search for an old id. */
        String everything() {
            final StringBuilder all = new StringBuilder();
            for (final Held issue : issues) {
                all.append(issue.repository)
                        .append('#')
                        .append(issue.number)
                        .append(' ')
                        .append(issue.title)
                        .append(' ')
                        .append(issue.labels)
                        .append("\nbody: ")
                        .append(issue.body)
                        .append("\nsubs: ")
                        .append(issue.subs)
                        .append(" blockers: ")
                        .append(issue.blockers)
                        .append("\ncomments: ")
                        .append(issue.comments)
                        .append('\n');
            }
            items.forEach(
                    (itemId, nodeId) -> {
                        final Map<String, String> held =
                                new TreeMap<>(values.getOrDefault(itemId, Map.of()));
                        final String status = held.get("F_status");
                        if (status != null) {
                            // An option's id changes whenever the options are set; its name stays.
                            held.put("F_status", status.substring(status.indexOf('_') + 1));
                        }
                        all.append(itemId).append(' ').append(held).append('\n');
                    });
            all.append("statuses: ").append(statuses.stream().map(FieldOption::name).toList());
            return all.toString();
        }
    }

    /** The map as its file keeps it, read back on every save as a run again would. */
    private static final class File implements CarryOver.Journal {
        byte @Nullable [] saved;

        @Override
        public void save(final CarryOverMap map) {
            saved = map.json();
        }

        CarryOverMap map() {
            return saved == null
                    ? CarryOverMap.begin("/project", OWNER + "/7", NOW)
                    : CarryOverMap.read(saved);
        }
    }

    private static CarryOver.Outcome run(final GitHub github, final File file) {
        return run(github, file, CarryOverTest::title);
    }

    private static CarryOver.Outcome run(
            final GitHub github, final File file, final Function<String, String> titles) {
        return new CarryOver(github, github, github, origin -> TOKEN, () -> NOW)
                .run(CONFIG, planner(titles), file.map(), file);
    }

    // ---- the tests ----

    @Test
    void everyItemIsMadeWithItsFieldsAndEveryOldIdRewrittenToItsReference() {
        // Harm: a reference left pointing at an old id, or an item carried without a part of it.
        final GitHub github = new GitHub();
        final CarryOver.Outcome outcome = run(github, new File());
        assertThat(outcome.whole()).as("whether the run is whole: %s", outcome.failures()).isTrue();
        assertThat(outcome.references())
                .as("the references")
                .containsExactly(
                        Map.entry("ST1", "porelo-project#1"),
                        Map.entry("ST2", "porelo-project#2"),
                        Map.entry("PL10", "porelo-planner#1"),
                        Map.entry("PL11", "porelo-planner#2"),
                        Map.entry("PL11.1", "porelo-planner#3"));
        final Held st1 = github.held(OWN, 1);
        final Held st2 = github.held(OWN, 2);
        final Held pl10 = github.held(PLANNER, 1);
        final Held pl11 = github.held(PLANNER, 2);
        final Held part = github.held(PLANNER, 3);
        assertThat(st1.labels).as("a story's labels").containsExactly(IssueMapping.STORY_LABEL);
        assertThat(pl10.labels).as("an issue's labels").isEmpty();
        assertThat(st1.subs).as("ST1's sub-issues").containsExactly(pl10.databaseId);
        assertThat(pl11.subs).as("PL11's sub-issues").containsExactly(part.databaseId);
        assertThat(st2.subs).as("ST2's sub-issues").isEmpty();
        assertThat(pl10.blockers).as("PL10's dependencies").containsExactly(pl11.databaseId);
        assertThat(st1.body)
                .as("ST1's body")
                .isEqualTo(
                        "**What a user gains.** It needs porelo-planner#2.\n\n## Also needs\n\n"
                                + "- porelo-planner#3\n");
        assertThat(st2.body)
                .as("ST2's body")
                .isEqualTo("**What a user gains.** Gain.\n\n## Also needs\n\n- porelo-planner#1\n");
        assertThat(pl10.body)
                .as("PL10's body")
                .isEqualTo(
                        "It covers the work.\n\n## Plan\n\nBuilt on PL9 and porelo-planner#2.\n");
        assertThat(pl11.comments).as("PL11's stage comments").hasSize(2);
        assertThat(pl11.comments.get(0)).as("the first comment").contains("implement: done");
        assertThat(pl11.comments.get(1)).as("the last comment").contains("review: done");
        assertThat(github.values.get("PVTI_" + pl11.nodeId))
                .as("PL11's values")
                .containsEntry("F_Minutes", "15.0")
                .containsEntry("F_Stages", "2.0")
                .containsEntry("F_Estimate", "25.0")
                .containsEntry("F_Tokens", "200.0")
                .containsEntry("F_priority", "F_priority_Medium");
        assertThat(github.values.get("PVTI_" + st2.nodeId))
                .as("the values of a story that follows its issues")
                .doesNotContainKey("F_status");
        assertThat(github.statuses)
                .extracting(FieldOption::name)
                .as("the Status options")
                .containsExactlyElementsOf(WORKFLOW.states());
    }

    @Test
    void aRunWhoseIssueIsRefusedAsRateLimitedWaitsAndEndsWholeWithTheIssueMadeOnce() {
        // Harm: an unattended carry-over stopped by a refusal it could wait out, or an issue
        // made twice when its call is made again.
        final GitHub whole = new GitHub();
        run(whole, new File());
        final GitHub github = new GitHub();
        github.issuesRateLimited = 1;
        final List<java.time.Duration> slept = new ArrayList<>();
        final List<String> lines = new ArrayList<>();
        final CarryOverPace pace = new CarryOverPace(() -> NOW, slept::add, lines::add);
        final File file = new File();
        final CarryOver.Outcome outcome =
                new CarryOver(
                                pace.queries(github),
                                pace.writes(github),
                                pace.calls(github),
                                origin -> TOKEN,
                                () -> NOW)
                        .run(CONFIG, planner(CarryOverTest::title), file.map(), file);
        assertThat(outcome.whole()).as("whether the run is whole: %s", outcome.failures()).isTrue();
        assertThat(github.everything()).as("GitHub after the run").isEqualTo(whole.everything());
        assertThat(slept).as("the waits").contains(java.time.Duration.ofMinutes(1));
        assertThat(lines)
                .as("the lines said")
                .singleElement()
                .asString()
                .contains("GitHub refused making an issue in")
                .doesNotContain(TOKEN);
    }

    @Test
    void noOldIdStandsAnywhereOnGitHub() {
        // Harm: an old id kept on GitHub, which nothing may name after the cut-over.
        final GitHub github = new GitHub();
        run(github, new File());
        final String all = github.everything();
        for (final String id : CARRIED) {
            assertThat(all)
                    .as("what GitHub holds")
                    .doesNotContainPattern(
                            "(?<![A-Za-z0-9.])" + java.util.regex.Pattern.quote(id) + "(?![0-9])");
        }
    }

    @Test
    void aRunStoppedAtAnyWriteAndRunAgainMakesNothingTwice() {
        // Harm: a run that stopped doubles items, sub-issues, dependencies or comments when run
        // again, or leaves one out.
        final GitHub whole = new GitHub();
        run(whole, new File());
        final String expected = whole.everything();
        final int calls = whole.written.size();
        assertThat(calls).as("the writes of a whole run").isGreaterThan(20);
        for (final boolean taken : List.of(true, false)) {
            for (int cut = 1; cut <= calls; cut++) {
                final GitHub github = new GitHub();
                github.cutAt = cut;
                github.takenBeforeCut = taken;
                final File file = new File();
                assertThatThrownBy(() -> run(github, file))
                        .as("the run cut at write %d", cut)
                        .isInstanceOf(CarryOver.Stopped.class);
                github.cutAt = 0;
                final CarryOver.Outcome again = run(github, file);
                assertThat(again.whole()).as("the run again after write %d", cut).isTrue();
                assertThat(github.everything())
                        .as("GitHub after a cut at write %d (%s) and a run again", cut, taken)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void anIssueTheMapHoldsIsNeverTakenForAnotherPendingItemOfTheSameTitleAndBody() {
        // Harm: a run again takes one item's issue for another item with the same title and first
        // body, so the second is never made and its second pass overwrites the first.
        final Function<String, String> twins = id -> title(id.equals("PL11.1") ? "PL11" : id);
        final GitHub whole = new GitHub();
        run(whole, new File(), twins);
        final String expected = whole.everything();
        final int calls = whole.written.size();
        for (final boolean taken : List.of(true, false)) {
            for (int cut = 1; cut <= calls; cut++) {
                final GitHub github = new GitHub();
                github.cutAt = cut;
                github.takenBeforeCut = taken;
                final File file = new File();
                assertThatThrownBy(() -> run(github, file, twins))
                        .as("the run cut at write %d", cut)
                        .isInstanceOf(CarryOver.Stopped.class);
                github.cutAt = 0;
                final CarryOver.Outcome again = run(github, file, twins);
                assertThat(again.whole()).as("the run again after write %d", cut).isTrue();
                assertThat(github.everything())
                        .as("GitHub after a cut at write %d (%s) and a run again", cut, taken)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void aProjectHoldingItemsWithoutAMapIsRefusedBeforeAnythingIsWritten() {
        // Harm: a second run without the first's map doubles every item.
        final GitHub github = new GitHub();
        run(github, new File());
        final int issues = github.issues.size();
        final int writes = github.written.size();
        assertThatThrownBy(() -> run(github, new File()))
                .as("a run without the map over a project holding items")
                .isInstanceOf(CarryOver.Stopped.class)
                .hasMessageContaining("no map of an earlier run");
        assertThat(github.issues).as("the issues").hasSize(issues);
        assertThat(github.written).as("the writes").hasSize(writes);
    }

    @Test
    void anItemTheMapDoesNotHoldStopsARunAgain() {
        // Harm: a run again over a project a person added to takes that item for its own.
        final GitHub github = new GitHub();
        final File file = new File();
        github.cutAt = 3;
        assertThatThrownBy(() -> run(github, file))
                .as("the first run, stopped")
                .isInstanceOf(CarryOver.Stopped.class);
        github.cutAt = 0;
        github.issues.add(new Held("I_other", 9999, PLANNER, 99, "Other", "", List.of()));
        github.items.put("PVTI_other", "I_other");
        final int writes = github.written.size();
        assertThatThrownBy(() -> run(github, file))
                .as("a run again over a project holding another item")
                .isInstanceOf(CarryOver.Stopped.class)
                .hasMessageContaining("porelo-ai/porelo-planner#99");
        assertThat(github.written).as("the writes").hasSize(writes);
    }

    @Test
    void aPendingItemFoundTwiceStopsTheRunNamingIt() {
        // Harm: a guess between two issues of one item carries the wrong one and leaves a double.
        final GitHub github = new GitHub();
        final File file = new File();
        github.cutAt = 2;
        github.takenBeforeCut = true;
        assertThatThrownBy(() -> run(github, file))
                .as("the first run, stopped")
                .isInstanceOf(CarryOver.Stopped.class);
        github.cutAt = 0;
        final Held made = github.issues.getFirst();
        github.issues.add(
                new Held("I_twin", 9999, made.repository, 2, made.title, made.body, made.labels));
        assertThatThrownBy(() -> run(github, file))
                .as("a run again finding two issues for a pending item")
                .isInstanceOf(CarryOver.Stopped.class)
                .hasMessageContaining("ST1 of porelo-project may have been made more than once")
                .hasMessageContaining("porelo-project#1, porelo-project#2");
    }

    @Test
    void aStoryMadeWithoutItsLabelStopsTheRun() {
        // Harm: a story without its label is read back as an issue.
        final GitHub github = new GitHub();
        github.labelsDropped = true;
        assertThatThrownBy(() -> run(github, new File()))
                .as("a run whose story lost its label")
                .isInstanceOf(CarryOver.Stopped.class)
                .hasMessageContaining("without the label story");
        assertThat(github.items).as("the items on the project").isEmpty();
    }

    @Test
    void aProjectWithoutAnOptionTheItemsNeedIsRefusedBeforeAnythingIsWritten() {
        // Harm: a run that fails on the first field write leaves an issue made and half carried.
        final GitHub github = new GitHub();
        github.withoutRisk = true;
        assertThatThrownBy(() -> run(github, new File()))
                .as("a run over a project lacking a Risk option")
                .isInstanceOf(CarryOver.Stopped.class)
                .hasMessageContaining("Risk has no option");
        assertThat(github.written).as("the writes").isEmpty();
    }

    @Test
    void aBodyAPersonChangedAfterTheFirstPassIsLeftAndNamed() {
        // Harm: a run again overwrites what a person wrote since the first pass.
        final GitHub github = new GitHub();
        final File file = new File();
        // Cut at the first write of the second pass, the first sub-issue of ST1.
        final GitHub whole = new GitHub();
        run(whole, new File());
        github.cutAt = whole.written.indexOf("sub porelo-project#1") + 1;
        assertThatThrownBy(() -> run(github, file))
                .as("the first run, stopped")
                .isInstanceOf(CarryOver.Stopped.class);
        github.cutAt = 0;
        github.held(OWN, 1).body = "A person's words.";
        final CarryOver.Outcome again = run(github, file);
        assertThat(again.whole()).as("whether the run is whole").isFalse();
        assertThat(again.failures())
                .as("the items left")
                .singleElement()
                .asString()
                .startsWith("ST1 (porelo-project#1): its body was changed");
        assertThat(github.held(OWN, 1).body).as("ST1's body").isEqualTo("A person's words.");
        assertThat(github.held(PLANNER, 2).comments).as("another item's comments").hasSize(2);
    }

    @Test
    void aBodyAPersonChangedStillGetsItsLinksAndStageComments() {
        // Harm: a person's edit to one body leaves its sub-issues, dependencies and stage comments
        // unwritten, so its stage numbers stand apart from its comments on every read.
        final GitHub github = new GitHub();
        final File file = new File();
        final GitHub whole = new GitHub();
        run(whole, new File());
        github.cutAt = whole.written.indexOf("sub porelo-project#1") + 1;
        assertThatThrownBy(() -> run(github, file))
                .as("the first run, stopped")
                .isInstanceOf(CarryOver.Stopped.class);
        github.cutAt = 0;
        final Held pl11 = github.held(PLANNER, 2);
        pl11.body = "A person's words.";
        final CarryOver.Outcome again = run(github, file);
        assertThat(again.failures())
                .as("the items left")
                .singleElement()
                .asString()
                .startsWith("PL11 (porelo-planner#2): its body was changed");
        assertThat(pl11.body).as("PL11's body").isEqualTo("A person's words.");
        assertThat(pl11.subs)
                .as("PL11's sub-issues")
                .containsExactly(github.held(PLANNER, 3).databaseId);
        assertThat(pl11.comments).as("PL11's stage comments").hasSize(2);
        assertThat(github.held(PLANNER, 1).blockers)
                .as("the dependency on PL11")
                .containsExactly(pl11.databaseId);
    }

    @Test
    void theTokenNeverStandsInAStop() {
        // Harm: a token shown in a refusal ends up in a terminal's scrollback or a log.
        final GitHub github =
                new GitHub() {
                    @Override
                    public Created createIssue(
                            final String token,
                            final String owner,
                            final String repository,
                            final String title,
                            final String body,
                            final List<String> labels) {
                        throw new IllegalStateException("refused for " + token);
                    }
                };
        assertThatThrownBy(() -> run(github, new File()))
                .as("a run whose call to GitHub fails naming the token")
                .isInstanceOf(CarryOver.Stopped.class)
                .message()
                .doesNotContain(TOKEN)
                .contains(Scrubbed.MARK);
    }
}
