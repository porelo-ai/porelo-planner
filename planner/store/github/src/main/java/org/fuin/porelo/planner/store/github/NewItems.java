package org.fuin.porelo.planner.store.github;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.fuin.porelo.forge.github.Batch;
import org.fuin.porelo.forge.github.Delivery;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Worker;
import org.jspecify.annotations.Nullable;

/**
 * One write that adds new issues, and a story above them, to the GitHub project: every part is
 * checked before the first call; then each issue is made, added to the project, made a sub-issue of
 * the story and given its field values. GitHub has no transaction, so a write that stops after the
 * first issue was made is {@link StoreException.PartlySaved}, naming what GitHub holds.
 */
final class NewItems {

    /**
     * How long before a call the look for an issue GitHub made without answering starts: GitHub
     * keeps whole seconds, and a clock running apart from GitHub's would miss the issue otherwise.
     */
    static final Duration CLOCK_SLACK = Duration.ofSeconds(60);

    /** The most pages of a repository's issues read to find one GitHub made without answering. */
    static final int MOST_FOUND_PAGES = 10;

    /** Anything that would break a title's or a line's one line. */
    private static final Pattern BREAKS = Pattern.compile("[\\p{Cntrl}\\u0085\\u2028\\u2029]");

    private final ProjectWrites github;

    private final AccessRefusals refusals;

    private final InstantSource clock;

    private final Snapshot current;

    /** The last rank given, by kind and priority, so the items of one write follow each other. */
    private final Map<String, Integer> ranks = new HashMap<>();

    /** The references of the issues GitHub holds from this write, in the order made. */
    private final List<String> made = new ArrayList<>();

    /** The repositories an issue was made in, or may have been made in, in order. */
    private final Set<String> written = new LinkedHashSet<>();

    /** Whether a call that makes an issue went without a clear answer, so GitHub may hold it. */
    private boolean sent;

    /**
     * Starts a write.
     *
     * @param github the calls the write is made of
     * @param refusals turns a failure into the store's refusal, scrubbed of the token
     * @param clock tells the time a call is sent at
     * @param current the snapshot the store holds
     */
    NewItems(
            final ProjectWrites github,
            final AccessRefusals refusals,
            final InstantSource clock,
            final Snapshot current) {
        this.github = github;
        this.refusals = refusals;
        this.clock = clock;
        this.current = current;
    }

    /** An issue to be made, with what it is given once GitHub made it. */
    static final class Planned {
        private final String repository;
        private final String title;
        private final String body;
        private final List<String> labels;
        private final List<Function<String, GitHubGraphQl.FieldValue>> values;
        private ProjectWrites.@Nullable Created created;
        private @Nullable String itemId;

        private Planned(
                final String repository,
                final String title,
                final String body,
                final List<String> labels,
                final List<Function<String, GitHubGraphQl.FieldValue>> values) {
            this.repository = repository;
            this.title = title;
            this.body = body;
            this.labels = List.copyOf(labels);
            this.values = List.copyOf(values);
        }

        /**
         * Answers its tracker reference, once GitHub made it.
         *
         * @return {@code <repository>#<number>}
         */
        String reference() {
            return repository + "#" + Objects.requireNonNull(created).number();
        }

        /**
         * Answers its database id, once GitHub made it.
         *
         * @return the id
         */
        long databaseId() {
            return Objects.requireNonNull(created).databaseId();
        }

        private ProjectWrites.Created made() {
            return Objects.requireNonNull(created);
        }
    }

    /**
     * Plans a new issue in the workflow's first state: its body says what must be true and which
     * task proposed it, as the Git store's file does, and it takes the rank after the last issue of
     * its priority.
     *
     * @param issue what the person wrote
     * @param priority its priority, which its rank follows
     * @return the planned issue
     * @throws StoreException.NotSavable when the project cannot hold it
     */
    Planned issue(final NewIssue issue, final String priority) {
        final String repository = issue.repository();
        if (!current.config().repositories().contains(repository)) {
            throw new StoreException.NotSavable(
                    "", repository + " is no repository of the project");
        }
        line(issue.title(), "the title of a new issue");
        final String body = issueBody(issue);
        final List<Function<String, GitHubGraphQl.FieldValue>> values = new ArrayList<>();
        values.add(option(FieldNames.STATUS, current.config().workflow().states().get(0)));
        final String size = issue.size();
        if (size != null) {
            values.add(option(FieldNames.SIZE, size));
        }
        values.add(option(FieldNames.WORKER, issue.worker() == Worker.MANUAL ? "Manual" : "Agent"));
        values.add(option(FieldNames.PRIORITY, priority(priority)));
        values.add(number(FieldNames.RANK, rank(false, priority)));
        return planned(repository, issue.title().strip(), body, List.of(), values);
    }

    /**
     * Plans an idea's story in the project's own repository, labeled as a story, its gain and its
     * test in its body as a carried story holds them; it gets no Status, so its state follows its
     * issues'.
     *
     * @param idea the idea
     * @return the planned story
     * @throws StoreException.NotSavable when the project cannot hold it
     */
    Planned story(final NewIdea idea) {
        line(idea.title(), "the title of a new story");
        line(idea.gain(), "what a user gains");
        line(idea.test(), "the test of a new story");
        priority(idea.priority());
        final String body =
                "**What a user gains.** "
                        + idea.gain().strip()
                        + "\n\n**The test.** "
                        + idea.test().strip()
                        + "\n";
        final List<Function<String, GitHubGraphQl.FieldValue>> values = new ArrayList<>();
        values.add(option(FieldNames.PRIORITY, idea.priority()));
        values.add(number(FieldNames.RANK, rank(true, idea.priority())));
        return planned(
                current.config().own(),
                idea.title().strip(),
                body,
                List.of(IssueMapping.STORY_LABEL),
                values);
    }

    /**
     * Makes the issues, the story first, adds them to the project, makes each issue a sub-issue of
     * the story in their order, and sets their field values.
     *
     * @param token the person's token
     * @param story the story, or null when only issues are added
     * @param issues the issues
     */
    void write(final String token, final @Nullable Planned story, final List<Planned> issues) {
        final String projectId = current.config().projectId();
        if (projectId.isBlank()) {
            throw new StoreException.Unreadable(
                    current.config().own(), "the GitHub project's id was not read");
        }
        final List<Planned> all = new ArrayList<>();
        if (story != null) {
            all.add(story);
        }
        all.addAll(issues);
        for (final Planned planned : all) {
            planned.created = create(token, planned);
            made.add(planned.reference());
            written.add(planned.repository);
            if (planned == story && !planned.made().labels().contains(IssueMapping.STORY_LABEL)) {
                throw partly(
                        planned.repository,
                        "GitHub made "
                                + planned.reference()
                                + " without the label "
                                + IssueMapping.STORY_LABEL
                                + ", which only an account with push access sets, so it is no"
                                + " story");
            }
        }
        addToProject(token, projectId, all);
        if (story != null) {
            for (final Planned issue : issues) {
                try {
                    github.addSubIssue(
                            token,
                            current.config().project().owner(),
                            story.repository,
                            story.made().number(),
                            issue.made().databaseId());
                } catch (final RuntimeException e) {
                    throw failed(e, issue.repository, token);
                }
            }
        }
        setValues(token, projectId, all);
    }

    private void addToProject(final String token, final String projectId, final List<Planned> all) {
        for (int from = 0; from < all.size(); from += GitHubGraphQl.MAX_BATCH) {
            final List<Planned> chunk =
                    all.subList(from, Math.min(all.size(), from + GitHubGraphQl.MAX_BATCH));
            final Batch<String> batch;
            try {
                batch =
                        github.addProjectItems(
                                token,
                                projectId,
                                chunk.stream().map(p -> p.made().nodeId()).toList());
            } catch (final RuntimeException e) {
                throw failed(e, chunk.get(0).repository, token);
            }
            for (int at = 0; at < chunk.size(); at++) {
                final Batch.Outcome<String> outcome =
                        at < batch.outcomes().size() ? batch.outcomes().get(at) : null;
                final Planned planned = chunk.get(at);
                if (outcome == null || !outcome.saved() || outcome.value() == null) {
                    throw partly(
                            planned.repository,
                            "GitHub did not add "
                                    + planned.reference()
                                    + " to the project"
                                    + refusedWords(outcome, token));
                }
                planned.itemId = outcome.value();
            }
        }
    }

    private void setValues(final String token, final String projectId, final List<Planned> all) {
        final List<GitHubGraphQl.FieldValue> values = new ArrayList<>();
        final List<Planned> owners = new ArrayList<>();
        for (final Planned planned : all) {
            final String itemId = Objects.requireNonNull(planned.itemId);
            for (final Function<String, GitHubGraphQl.FieldValue> value : planned.values) {
                values.add(value.apply(itemId));
                owners.add(planned);
            }
        }
        for (int from = 0; from < values.size(); from += GitHubGraphQl.MAX_BATCH) {
            final int to = Math.min(values.size(), from + GitHubGraphQl.MAX_BATCH);
            final Batch<String> batch;
            try {
                batch = github.setFieldValues(token, projectId, values.subList(from, to));
            } catch (final RuntimeException e) {
                throw failed(e, owners.get(from).repository, token);
            }
            for (int at = from; at < to; at++) {
                final int part = at - from;
                final Batch.Outcome<String> outcome =
                        part < batch.outcomes().size() ? batch.outcomes().get(part) : null;
                if (outcome == null || !outcome.saved()) {
                    final Planned planned = owners.get(at);
                    throw partly(
                            planned.repository,
                            "GitHub refused a field value of "
                                    + planned.reference()
                                    + refusedWords(outcome, token));
                }
            }
        }
    }

    /**
     * Makes an issue once. A call GitHub may have taken without an answer is never sent again
     * blindly: the person's issues made since are looked through for one of the same title and
     * body; one found is taken as made, none found makes it once more, several stop the write.
     */
    private ProjectWrites.Created create(final String token, final Planned planned) {
        final Instant since = clock.instant().minus(CLOCK_SLACK);
        try {
            return send(token, planned);
        } catch (final RuntimeException e) {
            switch (outcome(e)) {
                case REFUSED -> throw failed(e, planned.repository, token);
                case UNKNOWN -> {
                    unsure(planned);
                    throw unknownMade(planned, "GitHub's answer could not be read");
                }
                case UNANSWERED -> unsure(planned);
            }
        }
        final List<ProjectWrites.Found> found = madeSince(token, planned, since);
        if (found.size() == 1) {
            return found.get(0).issue();
        }
        if (found.size() > 1) {
            found.forEach(f -> made.add(planned.repository + "#" + f.issue().number()));
            written.add(planned.repository);
            throw partly(
                    planned.repository,
                    "GitHub took a call without answering and holds "
                            + found.size()
                            + " issues of the person with its title and body since, so none of"
                            + " them is taken as the new one");
        }
        try {
            return send(token, planned);
        } catch (final RuntimeException e) {
            if (outcome(e) != Outcome.REFUSED) {
                throw unknownMade(planned, "the call made once more got no clear answer either");
            }
            throw failed(e, planned.repository, token);
        }
    }

    private ProjectWrites.Created send(final String token, final Planned planned) {
        return github.createIssue(
                token,
                current.config().project().owner(),
                planned.repository,
                planned.title,
                planned.body,
                planned.labels);
    }

    /** The person's issues made since a moment with the planned title and body. */
    private List<ProjectWrites.Found> madeSince(
            final String token, final Planned planned, final Instant since) {
        final List<ProjectWrites.Found> found = new ArrayList<>();
        for (int page = 1; page <= MOST_FOUND_PAGES; page++) {
            final ProjectWrites.FoundPage read;
            try {
                read =
                        github.issuesCreatedSince(
                                token,
                                current.config().project().owner(),
                                planned.repository,
                                since,
                                page);
            } catch (final RuntimeException e) {
                throw unknownMade(
                        planned,
                        "the look for it failed: "
                                + refusals.refusal(planned.repository, e, token).getMessage());
            }
            for (final ProjectWrites.Found issue : read.issues()) {
                if (!known(planned.repository + "#" + issue.issue().number())
                        && issue.title().equals(planned.title)
                        && plain(issue.body()).equals(plain(planned.body))) {
                    found.add(issue);
                }
            }
            if (!read.more()) {
                return found;
            }
        }
        throw unknownMade(
                planned, "the person made more issues since than are read to find it among them");
    }

    /** A body as GitHub may hand it back: its line endings and outer blank space aside. */
    private static String plain(final String body) {
        return body.replace("\r\n", "\n").strip();
    }

    /** What a failed call that makes an issue tells of the issue. */
    private enum Outcome {
        /** Never sent, or refused by GitHub's answer: no issue was made. */
        REFUSED,
        /** Sent, and no answer came: GitHub may have made it, and the person's issues tell. */
        UNANSWERED,
        /** Answered by a failure on GitHub's side or in words that could not be read. */
        UNKNOWN
    }

    private static Outcome outcome(final RuntimeException e) {
        if (e instanceof GitHubException failure) {
            if (failure.delivery() == Delivery.SENT_UNANSWERED) {
                return Outcome.UNANSWERED;
            }
            final boolean refused =
                    failure.delivery() == Delivery.NOT_SENT
                            || failure.status() >= 400 && failure.status() < 500;
            return refused ? Outcome.REFUSED : Outcome.UNKNOWN;
        }
        return e instanceof StoreException ? Outcome.REFUSED : Outcome.UNKNOWN;
    }

    /** Notes that GitHub may hold an issue of this write in a repository. */
    private void unsure(final Planned planned) {
        sent = true;
        written.add(planned.repository);
    }

    /** Whether an issue stood on the board before this write or was made earlier in it. */
    private boolean known(final String reference) {
        if (made.contains(reference)) {
            return true;
        }
        for (final ItemView item : current.items().values()) {
            if (reference.equals(item.repository() + "#" + item.number())) {
                return true;
            }
        }
        return false;
    }

    private StoreException.PartlySaved unknownMade(final Planned planned, final String why) {
        return partly(
                planned.repository,
                "GitHub gave no clear answer to a call, so whether it made an issue in "
                        + planned.repository
                        + " is unknown; "
                        + why);
    }

    /**
     * The refusal of a call that failed: as it is while nothing was made yet, else partly saved.
     */
    private StoreException failed(
            final RuntimeException e, final String repository, final String token) {
        final StoreException refusal = refusals.refusal(repository, e, token);
        if (made.isEmpty() && !sent) {
            return refusal;
        }
        return partly(repository, "A call failed after a part was made: " + refusal.getMessage());
    }

    private StoreException.PartlySaved partly(final String repository, final String reason) {
        return new StoreException.PartlySaved(
                repository,
                List.copyOf(written),
                reason
                        + (made.isEmpty()
                                ? ""
                                : "; GitHub holds " + String.join(", ", made) + " from this save"));
    }

    private static String refusedWords(
            final Batch.@Nullable Outcome<String> outcome, final String token) {
        final String error = outcome == null ? null : outcome.error();
        return error == null ? "" : ": " + Scrubbed.text(error, List.of(token));
    }

    private Planned planned(
            final String repository,
            final String title,
            final String body,
            final List<String> labels,
            final List<Function<String, GitHubGraphQl.FieldValue>> values) {
        if (body.length() > BodySections.LONGEST) {
            throw new StoreException.NotSavable(
                    "", "the body of a new issue is longer than GitHub takes");
        }
        return new Planned(repository, title, body, labels, values);
    }

    /**
     * A new issue's body: what must be true as its lead's paragraph followed by the task that
     * proposed it, or, for a text the lead cannot hold, the task's paragraph followed by a section
     * of its own; refused in fixed words when even a section cannot hold the text.
     */
    private static String issueBody(final NewIssue issue) {
        final String what;
        try {
            what = BodySections.read("").withWhatMustBeTrue(issue.mustBeTrue()).text();
        } catch (final IllegalArgumentException e) {
            throw new StoreException.NotSavable(
                    "",
                    "what must be true holds a heading of the first or second level or leaves a"
                            + " code block open");
        }
        final String task = issue.task();
        final String proposed = "Proposed by Porelo task `" + task + "`.";
        final String body;
        if (task == null) {
            body = what;
        } else if (BodySections.read(what)
                .section(BodySections.WHAT_MUST_BE_TRUE_SECTION)
                .isPresent()) {
            body = proposed + "\n\n" + what;
        } else {
            body = what.strip() + "\n\n" + proposed + "\n";
        }
        if (BodySections.read(body).ambiguity().isPresent()) {
            throw new StoreException.NotSavable(
                    "", "what must be true would leave the new issue's sections unclear");
        }
        return body;
    }

    private static String priority(final String priority) {
        if (!Story.PRIORITIES.contains(priority)) {
            throw new StoreException.NotSavable("", "a priority is Highest to Lowest");
        }
        return priority;
    }

    private static void line(final String text, final String what) {
        if (text.isBlank() || BREAKS.matcher(text).find()) {
            throw new StoreException.NotSavable(
                    "", what + " is blank or holds a line break or a control character");
        }
    }

    private Function<String, GitHubGraphQl.FieldValue> option(
            final String field, final String name) {
        final String fieldId = fieldId(field);
        final String optionId =
                current.config().optionIds().getOrDefault(field, Map.of()).get(name);
        if (optionId == null) {
            throw new StoreException.NotSavable(
                    "", "the project's field " + field + " has no option " + name);
        }
        return itemId -> GitHubGraphQl.FieldValue.option(itemId, fieldId, optionId);
    }

    private Function<String, GitHubGraphQl.FieldValue> number(
            final String field, final double value) {
        final String fieldId = fieldId(field);
        return itemId -> GitHubGraphQl.FieldValue.number(itemId, fieldId, value);
    }

    private String fieldId(final String field) {
        final String fieldId = current.config().fieldIds().get(field);
        if (fieldId == null) {
            throw new StoreException.NotSavable("", "the project has no field " + field);
        }
        return fieldId;
    }

    /** The rank after the last item of a kind and priority, and after those this write gave. */
    private int rank(final boolean story, final String priority) {
        final String key = story + "\n" + priority;
        final int next =
                Math.min(
                        ranks.computeIfAbsent(key, k -> highest(story, priority)) + 1,
                        IssueMapping.MOST_RANK);
        ranks.put(key, next);
        return next;
    }

    /** The highest rank an item of a kind and priority holds, 0 when none holds one. */
    private int highest(final boolean story, final String priority) {
        int most = 0;
        for (final ItemView item : current.items().values()) {
            if (item.labels().contains(IssueMapping.STORY_LABEL) != story) {
                continue;
            }
            final String given = item.fields().getOrDefault(FieldNames.PRIORITY, "");
            final String its = Story.PRIORITIES.contains(given) ? given : "Medium";
            if (!its.equals(priority)) {
                continue;
            }
            try {
                final double rank =
                        Double.parseDouble(item.fields().getOrDefault(FieldNames.RANK, ""));
                if (Double.isFinite(rank) && rank >= 1 && rank <= IssueMapping.MOST_RANK) {
                    most = Math.max(most, (int) rank);
                }
            } catch (final NumberFormatException e) {
                // A rank no number sorts last and gives none to follow.
            }
        }
        return most;
    }
}
