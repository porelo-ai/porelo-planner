package org.fuin.porelo.planner.app;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.forge.github.Batch;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.GitHubGraphQl.FieldOption;
import org.fuin.porelo.forge.github.GitHubGraphQl.FieldValue;
import org.fuin.porelo.forge.github.GitHubGraphQl.PageInfo;
import org.fuin.porelo.forge.github.GitHubGraphQl.ProjectField;
import org.fuin.porelo.forge.github.GraphQlCost;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.github.CarryOverCalls;
import org.fuin.porelo.planner.store.github.FieldNames;
import org.fuin.porelo.planner.store.github.ProjectQueries;
import org.fuin.porelo.planner.store.github.ProjectWrites;
import org.jspecify.annotations.Nullable;

/** A GitHub project in memory, with the fields the planner writes, for the carry-over command. */
class CarryOverGitHub implements ProjectQueries, ProjectWrites, CarryOverCalls {

    /** The token the tests lend; invented. */
    static final String TOKEN = "github_pat_carry_command_test_0123456789";

    /** The time the tests' clock tells, at which every issue is made. */
    static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private static final GraphQlCost COST = new GraphQlCost(1, 4999, Instant.EPOCH);

    /** One issue as GitHub holds it. */
    static final class Held {
        final String repository;
        final int number;
        final long databaseId;
        final String title;
        String body;
        final List<String> labels;
        final List<Long> subs = new ArrayList<>();
        final List<Long> blockers = new ArrayList<>();
        final List<String> comments = new ArrayList<>();

        Held(
                final String repository,
                final int number,
                final long databaseId,
                final String title,
                final String body,
                final List<String> labels) {
            this.repository = repository;
            this.number = number;
            this.databaseId = databaseId;
            this.title = title;
            this.body = body;
            this.labels = List.copyOf(labels);
        }

        String nodeId() {
            return "I_" + databaseId;
        }

        Created made() {
            return new Created(nodeId(), databaseId, number, labels);
        }
    }

    final List<Held> issues = new ArrayList<>();
    final Map<String, String> items = new LinkedHashMap<>();
    final List<String> calls = new ArrayList<>();
    List<FieldOption> statuses = new ArrayList<>();

    /** The issue made, from 1, at which the call fails before GitHub takes it; 0 for none. */
    int failingIssue;

    /** Whether the failing issue is made all the same, only GitHub's answer lost. */
    boolean madeBeforeFailure;

    /** What a failing call says. */
    String failure = "GitHub is unreachable";

    private void call(final String token, final String what) {
        if (!TOKEN.equals(token)) {
            throw new AssertionError("a call without the lent token");
        }
        calls.add(what);
    }

    private Held held(final String repository, final int number) {
        return issues.stream()
                .filter(issue -> issue.repository.equals(repository) && issue.number == number)
                .findFirst()
                .orElseThrow();
    }

    private static ProjectField select(final String name, final List<String> options) {
        return new ProjectField(
                "F_" + name,
                name,
                "SINGLE_SELECT",
                options.stream()
                        .map(option -> new FieldOption("O_" + option, option, "GRAY", ""))
                        .toList());
    }

    @Override
    public GitHubGraphQl.ProjectFields projectFields(
            final String token,
            final String owner,
            final int number,
            final @Nullable String after) {
        call(token, "fields");
        final List<ProjectField> fields = new ArrayList<>();
        fields.add(new ProjectField("F_Status", FieldNames.STATUS, "SINGLE_SELECT", statuses));
        fields.add(
                select(FieldNames.PRIORITY, List.of("Highest", "High", "Medium", "Low", "Lowest")));
        fields.add(select(FieldNames.SIZE, List.of("XS", "S", "M", "L", "XL")));
        final List<String> risks = new ArrayList<>();
        for (final Risk risk : Risk.values()) {
            risks.add(risk.spelled());
        }
        fields.add(select(FieldNames.RISK, risks));
        fields.add(select(FieldNames.WORKER, List.of("Agent", "Manual")));
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
        call(token, "items");
        final List<GitHubGraphQl.ProjectItem> page = new ArrayList<>();
        items.forEach(
                (itemId, nodeId) -> {
                    final Held issue =
                            issues.stream()
                                    .filter(held -> held.nodeId().equals(nodeId))
                                    .findFirst()
                                    .orElseThrow();
                    page.add(
                            new GitHubGraphQl.ProjectItem(
                                    itemId,
                                    false,
                                    new GitHubGraphQl.BoardIssue(
                                            nodeId,
                                            issue.databaseId,
                                            issue.number,
                                            issue.title,
                                            issue.body,
                                            "OPEN",
                                            null,
                                            Instant.EPOCH,
                                            owner + "/" + issue.repository,
                                            issue.labels,
                                            null,
                                            new GitHubGraphQl.IssueRefs(
                                                    List.of(), new PageInfo(false, null)),
                                            new GitHubGraphQl.IssueRefs(
                                                    List.of(), new PageInfo(false, null))),
                                    List.of()));
                });
        return new GitHubGraphQl.ProjectItems(page, new PageInfo(false, null), COST, page.size());
    }

    @Override
    public GitHubGraphQl.IssueBoard issueBoard(
            final String token, final List<String> issueIds, final String projectId) {
        throw new AssertionError("a carry-over reads no issue by id");
    }

    @Override
    public Batch<String> setFieldValues(
            final String token, final String projectId, final List<FieldValue> values) {
        call(token, "values");
        return new Batch<>(
                values.stream().map(value -> Batch.Outcome.of(value.itemId())).toList(), null);
    }

    @Override
    public GitHubGraphQl.FieldOptions setFieldOptions(
            final String token, final String fieldId, final List<GitHubGraphQl.NewOption> options) {
        call(token, "options");
        statuses =
                options.stream()
                        .map(
                                option ->
                                        new FieldOption(
                                                "S_" + option.name(), option.name(), "GRAY", ""))
                        .toList();
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
        if (title != null) {
            throw new AssertionError("the carry-over leaves the title");
        }
        call(token, "body");
        held(repository, number).body = java.util.Objects.requireNonNull(body);
    }

    @Override
    public CommentsPage issueComments(
            final String token,
            final String owner,
            final String repository,
            final int number,
            final int page) {
        call(token, "comments");
        return new CommentsPage(List.copyOf(held(repository, number).comments), false);
    }

    @Override
    public void createIssueComment(
            final String token,
            final String owner,
            final String repository,
            final int number,
            final String body) {
        call(token, "comment");
        held(repository, number).comments.add(body);
    }

    @Override
    public Created createIssue(
            final String token,
            final String owner,
            final String repository,
            final String title,
            final String body,
            final List<String> labels) {
        call(token, "issue");
        final boolean fails = issues.size() + 1 == failingIssue;
        if (fails && !madeBeforeFailure) {
            throw new IllegalStateException(failure);
        }
        final int number =
                (int) issues.stream().filter(issue -> issue.repository.equals(repository)).count()
                        + 1;
        final Held made = new Held(repository, number, 100 + issues.size(), title, body, labels);
        issues.add(made);
        if (fails) {
            throw new IllegalStateException(failure);
        }
        return made.made();
    }

    @Override
    public FoundPage issuesCreatedSince(
            final String token,
            final String owner,
            final String repository,
            final Instant since,
            final int page) {
        call(token, "since");
        return new FoundPage(
                page == 1
                        ? issues.stream()
                                .filter(issue -> issue.repository.equals(repository))
                                // Every issue is made at the clock's time, as GitHub dates it.
                                .filter(issue -> !NOW.isBefore(since))
                                .map(issue -> new Found(issue.made(), issue.title, issue.body))
                                .toList()
                        : List.of(),
                false);
    }

    @Override
    public String body(
            final String token, final String owner, final String repository, final int number) {
        call(token, "issue read");
        return held(repository, number).body;
    }

    @Override
    public List<Long> subIssues(
            final String token, final String owner, final String repository, final int number) {
        call(token, "subs");
        return List.copyOf(held(repository, number).subs);
    }

    @Override
    public void addSubIssue(
            final String token,
            final String owner,
            final String repository,
            final int number,
            final long subIssueId) {
        call(token, "sub");
        held(repository, number).subs.add(subIssueId);
    }

    @Override
    public List<Long> blockedBy(
            final String token, final String owner, final String repository, final int number) {
        call(token, "blockers");
        return List.copyOf(held(repository, number).blockers);
    }

    @Override
    public void addBlockedBy(
            final String token,
            final String owner,
            final String repository,
            final int number,
            final long blockerId) {
        call(token, "blocker");
        held(repository, number).blockers.add(blockerId);
    }

    @Override
    public Batch<String> addProjectItems(
            final String token, final String projectId, final List<String> issueIds) {
        call(token, "item");
        issueIds.forEach(issueId -> items.put("PVTI_" + issueId, issueId));
        return new Batch<>(
                issueIds.stream().map(issueId -> Batch.Outcome.of("PVTI_" + issueId)).toList(),
                null);
    }

    /** Everything written to GitHub, as text. */
    String everything() {
        final StringBuilder all = new StringBuilder();
        issues.forEach(
                issue ->
                        all.append(issue.repository)
                                .append('#')
                                .append(issue.number)
                                .append(' ')
                                .append(issue.title)
                                .append(' ')
                                .append(issue.labels)
                                .append('\n')
                                .append(issue.body)
                                .append('\n')
                                .append(issue.comments)
                                .append('\n'));
        return all.toString();
    }
}
