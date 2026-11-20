package org.fuin.porelo.planner.store.github;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.TokenSource;
import org.jspecify.annotations.Nullable;

/**
 * Makes the project's {@code Status} options the workflow's states, in order, without an item
 * losing its {@code Status}. GitHub gives every option a new id when the options are set and clears
 * every item's value, so the step reads every item's {@code Status} by name first, sets the
 * options, and writes every value back by its new option id. An option the workflow does not have,
 * held by an item or not, is kept at the end, never dropped: a value whose state the workflow no
 * longer has keeps its option, and so does an option a person added. Each item holding one is named
 * as a fault, without the option's name a person typed.
 *
 * <p>It runs only when called, at the carry-over or when the person confirms it after the workflow
 * changed, never on a read. It is idempotent: options that already stand as wanted are not set
 * again, and a failed batch is written again from a fresh read.
 */
public final class StatusOptionsWriter {

    /** How often the values still missing are read afresh and written again. */
    static final int MOST_ATTEMPTS = 3;

    /** The color an option gets that did not stand before. */
    static final String NEW_COLOR = "GRAY";

    private final ProjectQueries queries;

    private final ProjectWrites github;

    private final TokenSource tokens;

    private final AccessRefusals refusals;

    /**
     * What the step did.
     *
     * @param changed whether the options were set
     * @param faults one sentence per item whose {@code Status} is no state of the workflow, naming
     *     it by its repository and number, or a draft by its project item
     */
    public record Applied(boolean changed, List<String> faults) {

        /** Copies the faults. */
        public Applied {
            faults = List.copyOf(faults);
        }
    }

    /**
     * Makes the step.
     *
     * @param queries the project's reads
     * @param github the calls the step writes with
     * @param tokens the person's token, asked for once a step
     * @param refusals turns a failure into the store's refusal, scrubbed of the token
     */
    public StatusOptionsWriter(
            final ProjectQueries queries,
            final ProjectWrites github,
            final TokenSource tokens,
            final AccessRefusals refusals) {
        this.queries = queries;
        this.github = github;
        this.tokens = tokens;
        this.refusals = refusals;
    }

    /**
     * Sets the {@code Status} options to the workflow's states and writes every item's value back.
     *
     * @param config the project and its workflow
     * @return whether the options were set, and the items whose {@code Status} is no state of the
     *     workflow
     * @throws StoreException.PartlySaved when values stay missing after the last attempt, naming
     *     each item and the state it must be given
     * @throws StoreException.Unreadable when the project cannot be read whole, or its {@code
     *     Status} values or options changed while the step prepared the options, which then stay
     */
    public Applied apply(final ProjectConfig config) {
        final String token = tokens.token(config.forge());
        try {
            return apply(config, token);
        } catch (final StoreException e) {
            throw e;
        } catch (final RuntimeException e) {
            throw refusals.refusal(config.own(), e, token);
        }
    }

    private Applied apply(final ProjectConfig config, final String token) {
        final Read before = read(config, token);
        final GitHubGraphQl.ProjectField field = before.status();
        final List<String> states = config.workflow().states();
        final List<String> standing =
                field.options().stream().map(GitHubGraphQl.FieldOption::name).toList();
        final Set<String> wanted = new LinkedHashSet<>(states);
        // An option the workflow does not have stays: an item may still need it set by hand, or a
        // person added it.
        wanted.addAll(standing);
        wanted.addAll(before.names().values());
        final List<String> faults = new ArrayList<>();
        before.names()
                .forEach(
                        (itemId, name) -> {
                            if (!states.contains(name)) {
                                faults.add(
                                        before.where().get(itemId)
                                                + " holds a Status outside the workflow; kept as"
                                                + " an option at the end");
                            }
                        });
        if (standing.equals(List.copyOf(wanted))) {
            return new Applied(false, faults);
        }
        final Map<String, GitHubGraphQl.FieldOption> kept = new LinkedHashMap<>();
        field.options().forEach(option -> kept.putIfAbsent(option.name(), option));
        final List<GitHubGraphQl.NewOption> options = new ArrayList<>();
        for (final String name : wanted) {
            final GitHubGraphQl.FieldOption old = kept.get(name);
            // A kept option keeps the color and description a person gave it.
            options.add(
                    old == null
                            ? new GitHubGraphQl.NewOption(name, NEW_COLOR, "")
                            : new GitHubGraphQl.NewOption(
                                    name,
                                    orElse(old.color(), NEW_COLOR),
                                    orElse(old.description(), "")));
        }
        // A value a person set since the first read would be cleared and overwritten, so the
        // options are set only over what stands right now.
        final Read now = read(config, token);
        if (!now.equals(before)) {
            throw new StoreException.Unreadable(
                    config.own(),
                    "the Status values or options changed while the step read them, so its options"
                            + " stay; run it again");
        }
        final List<String> order = List.copyOf(wanted);
        try {
            github.setFieldOptions(token, field.id(), options);
        } catch (final RuntimeException e) {
            // GitHub may have set them without answering, so the values are written back anyway.
            writeBack(config, token, before, order);
            throw e;
        }
        writeBack(config, token, before, order);
        return new Applied(true, faults);
    }

    /**
     * Writes every value back that a fresh read shows missing, until none is or the attempts are
     * spent; a value a person set since stands. Fails naming every item and the state it must be
     * given when values stay missing, or when what stands can no longer be read.
     */
    private void writeBack(
            final ProjectConfig config,
            final String token,
            final Read before,
            final List<String> order) {
        final Map<String, String> names = before.names();
        try {
            for (int attempt = 1; attempt <= MOST_ATTEMPTS; attempt++) {
                final Read now = read(config, token);
                final Map<String, String> missing = missing(names, now);
                if (missing.isEmpty()) {
                    return;
                }
                final Map<String, String> optionIds = new LinkedHashMap<>();
                now.status()
                        .options()
                        .forEach(option -> optionIds.putIfAbsent(option.name(), option.id()));
                final List<GitHubGraphQl.FieldValue> values = new ArrayList<>();
                missing.forEach(
                        (itemId, name) -> {
                            final String optionId = optionIds.get(name);
                            if (optionId != null) {
                                values.add(
                                        GitHubGraphQl.FieldValue.option(
                                                itemId, now.status().id(), optionId));
                            }
                        });
                write(config, token, values);
            }
            final Map<String, String> lost = missing(names, read(config, token));
            if (!lost.isEmpty()) {
                throw lost(config, before, order, lost, "GitHub did not take them");
            }
        } catch (final StoreException.PartlySaved e) {
            throw e;
        } catch (final RuntimeException e) {
            throw lost(
                    config,
                    before,
                    order,
                    names,
                    "a call failed: " + refusals.refusal(config.own(), e, token).getMessage());
        }
    }

    /** The values a read shows missing of the items that held one, by item id. */
    private static Map<String, String> missing(final Map<String, String> names, final Read now) {
        final Map<String, String> missing = new LinkedHashMap<>();
        names.forEach(
                (itemId, name) -> {
                    if (now.present().contains(itemId) && !now.names().containsKey(itemId)) {
                        missing.put(itemId, name);
                    }
                });
        return missing;
    }

    /**
     * The refusal that names every item still to be given its state by hand: a state of the
     * workflow by its name, any other option by its place, so no name a person typed is repeated.
     */
    private static StoreException.PartlySaved lost(
            final ProjectConfig config,
            final Read before,
            final List<String> order,
            final Map<String, String> lost,
            final String why) {
        final List<String> states = config.workflow().states();
        final List<String> named = new ArrayList<>();
        lost.forEach(
                (itemId, name) ->
                        named.add(
                                before.where().get(itemId)
                                        + " must be set to "
                                        + (states.contains(name)
                                                ? name
                                                : "the option in place "
                                                        + (order.indexOf(name) + 1)
                                                        + " of Status")));
        return new StoreException.PartlySaved(
                config.own(),
                List.of(config.own()),
                "The Status options were set, but "
                        + lost.size()
                        + " values could not be confirmed written back ("
                        + why
                        + "); set them by hand: "
                        + String.join("; ", named));
    }

    /**
     * Writes the values in batches of at most {@link GitHubGraphQl#MAX_BATCH}; a batch GitHub
     * refused, in part or whole, or did not answer is left to the next fresh read. A refusal of the
     * person's access or a spent rate limit goes through: another attempt cannot pass it.
     */
    private void write(
            final ProjectConfig config,
            final String token,
            final List<GitHubGraphQl.FieldValue> values) {
        for (int from = 0; from < values.size(); from += GitHubGraphQl.MAX_BATCH) {
            final List<GitHubGraphQl.FieldValue> chunk =
                    values.subList(from, Math.min(values.size(), from + GitHubGraphQl.MAX_BATCH));
            try {
                github.setFieldValues(token, config.projectId(), chunk);
            } catch (final GitHubException e) {
                if (e.rateLimited() || e.status() == 401 || e.status() == 403) {
                    throw e;
                }
                // Its outcome is unknown; the next fresh read tells what stands.
            } catch (final RuntimeException e) {
                // Its outcome is unknown; the next fresh read tells what stands.
            }
        }
    }

    /**
     * Every item of the project with its {@code Status} by name, and the field.
     *
     * @param status the field {@code Status}
     * @param names each item's {@code Status} by name, by its item id, for the items that hold one
     * @param present every item's id
     * @param where how a fault names each item: its repository and number, or a draft by its
     *     project item
     */
    private record Read(
            GitHubGraphQl.ProjectField status,
            Map<String, String> names,
            Set<String> present,
            Map<String, String> where) {}

    private Read read(final ProjectConfig config, final String token) {
        GitHubGraphQl.@Nullable ProjectField status = null;
        @Nullable String after = null;
        for (int page = 1; ; page++) {
            final GitHubGraphQl.ProjectFields fields =
                    queries.projectFields(
                            token, config.project().owner(), config.project().number(), after);
            for (final GitHubGraphQl.ProjectField field : fields.fields()) {
                if (FieldNames.STATUS.equals(field.name())) {
                    status = field;
                }
            }
            after = next(config, fields.pageInfo(), after, page);
            if (after == null) {
                break;
            }
        }
        if (status == null || !"SINGLE_SELECT".equals(status.dataType())) {
            throw new StoreException.Unreadable(
                    config.own(), "the project has no single-select field Status");
        }
        final Map<String, String> names = new LinkedHashMap<>();
        final Set<String> present = new LinkedHashSet<>();
        final Map<String, String> where = new LinkedHashMap<>();
        after = null;
        for (int page = 1; ; page++) {
            final GitHubGraphQl.ProjectItems items =
                    queries.projectItems(
                            token, config.project().owner(), config.project().number(), after);
            for (final GitHubGraphQl.ProjectItem item : items.items()) {
                if (!present.add(item.id())) {
                    continue;
                }
                final GitHubGraphQl.BoardIssue issue = item.issue();
                where.put(
                        item.id(),
                        issue == null
                                ? "Project item " + item.id() + " without an issue"
                                : "Item " + issue.repository() + "#" + issue.number());
                for (final GitHubGraphQl.ItemValue value : item.values()) {
                    final String name = value.name();
                    if (status.id().equals(value.fieldId()) && name != null && !name.isEmpty()) {
                        names.put(item.id(), name);
                    }
                }
            }
            after = next(config, items.pageInfo(), after, page);
            if (after == null) {
                break;
            }
        }
        return new Read(status, names, present, where);
    }

    private static @Nullable String next(
            final ProjectConfig config,
            final GitHubGraphQl.PageInfo info,
            final @Nullable String cursor,
            final int page) {
        if (!Boolean.TRUE.equals(info.hasNextPage())) {
            return null;
        }
        final String end = info.endCursor();
        if (page >= ItemsBoardReader.MOST_PAGES
                || end == null
                || end.isEmpty()
                || end.equals(cursor)) {
            // Writing back over a part of the project would clear the rest's values for good.
            throw new StoreException.Unreadable(
                    config.own(), "the project could not be read whole, so its options stay");
        }
        return end;
    }

    private static String orElse(final @Nullable String text, final String otherwise) {
        return text == null ? otherwise : text;
    }
}
