package org.fuin.porelo.planner.store.github;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.TopicLabels;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;

/**
 * Merges a command whose item changed since it was judged: the command is written on the item as it
 * stands now when every change lies apart from the places it writes and read, and a change at one
 * of them is a conflict for the owner account. A place standing at its target already is done and
 * not written again. Wherever the item does not show a place whole, past the page GitHub gave, or
 * in a body whose parts cannot be told apart, the place is a conflict.
 */
final class CommandMerge {

    private CommandMerge() {}

    /** What a merge gives. */
    sealed interface Merged permits Write, Nothing, Conflict {}

    /**
     * The command to write on the item as it stands now.
     *
     * @param rebased the targets left to write
     * @param on the item it is written on, whose version the write is checked against
     */
    record Write(MergeTargets rebased, ItemView on) implements Merged {}

    /**
     * Every target stands already and no reason is left to write.
     *
     * @param on the item as it stands now
     */
    record Nothing(ItemView on) implements Merged {}

    /**
     * The places changed both by the command and since it was judged, for the owner account.
     *
     * @param places the places, never empty
     */
    record Conflict(SortedSet<Place> places) implements Merged {

        /** Copies the places; a conflict without one is invalid. */
        Conflict {
            if (places.isEmpty()) {
                throw new IllegalArgumentException("A conflict names a place.");
            }
            places = java.util.Collections.unmodifiableSortedSet(new TreeSet<>(places));
        }
    }

    /**
     * Merges a command onto its item as it stands now.
     *
     * @param targets what the command writes and the places its judgment read
     * @param judged the item as the command was judged on it
     * @param now the item as GitHub holds it now
     * @param reasonStands whether the comment keeping the command's reason stands on the item
     *     already, as the read before the write found it
     * @return the command to write, nothing to write, or the conflict
     */
    static Merged merge(
            final MergeTargets targets,
            final ItemView judged,
            final ItemView now,
            final boolean reasonStands) {
        if (!identity(judged).equals(identity(now)) || judged.archived() || now.archived()) {
            return new Conflict(new TreeSet<>(Set.of(Place.ITEM)));
        }
        final boolean story = targets.story();
        final SortedSet<Place> conflicts = new TreeSet<>();
        for (final Place place : targets.reads()) {
            if (blind(place, story, judged)
                    || blind(place, story, now)
                    || !Objects.equals(value(place, story, judged), value(place, story, now))) {
                conflicts.add(place);
            }
        }
        final MergeTargets.State state = targets.state();
        final Writes.Reason reason = targets.reason();
        boolean stateLeft = false;
        if (state != null) {
            final boolean moved =
                    !Objects.equals(
                            value(Place.STATE, story, judged), value(Place.STATE, story, now));
            if (blind(Place.STATE, story, judged) || blind(Place.STATE, story, now)) {
                conflicts.add(Place.STATE);
            } else if (stands(state, story, now)) {
                // A close another made is never given this command's reason.
                if (reason != null && !stands(state, story, judged)) {
                    conflicts.add(Place.STATE);
                }
            } else if (moved) {
                conflicts.add(Place.STATE);
            } else {
                stateLeft = true;
            }
        }
        final boolean reasonLeft =
                reason != null && !reasonStands && !conflicts.contains(Place.STATE);
        final String worker =
                left(
                        Place.WORKER,
                        targets.worker(),
                        targets.worker(),
                        story,
                        judged,
                        now,
                        conflicts);
        final String asked = targets.priority();
        @Nullable String option = null;
        if (asked != null) {
            try {
                option = IssuePriority.option(targets.id(), asked);
            } catch (final StoreException.NotSavable e) {
                // A priority GitHub has no option for is the owner account's to settle.
                conflicts.add(Place.PRIORITY);
            }
        }
        final String priority = left(Place.PRIORITY, option, asked, story, judged, now, conflicts);
        final String title =
                left(Place.TITLE, targets.title(), targets.title(), story, judged, now, conflicts);
        final List<String> topics = topics(targets, judged, now, conflicts);
        final Map<TextEdit.Part, String> written = new LinkedHashMap<>();
        final Set<TextEdit.Part> removed = EnumSet.noneOf(TextEdit.Part.class);
        body(targets, judged, now, conflicts, written, removed);
        if (!conflicts.isEmpty()) {
            return new Conflict(conflicts);
        }
        if (!stateLeft
                && !reasonLeft
                && worker == null
                && priority == null
                && title == null
                && topics == null
                && written.isEmpty()
                && removed.isEmpty()) {
            return new Nothing(now);
        }
        return new Write(
                new MergeTargets(
                        targets.id(),
                        story,
                        stateLeft || reasonLeft ? state : null,
                        reasonLeft ? reason : null,
                        worker,
                        priority,
                        topics,
                        title,
                        written,
                        removed,
                        targets.reads()),
                now);
    }

    /**
     * A target of one value left to write: none when it stands already, and a conflict when its
     * place moved to another value or cannot be seen whole.
     */
    private static @Nullable String left(
            final Place place,
            final @Nullable String shown,
            final @Nullable String target,
            final boolean story,
            final ItemView judged,
            final ItemView now,
            final Set<Place> conflicts) {
        if (shown == null) {
            return null;
        }
        if (blind(place, story, judged) || blind(place, story, now)) {
            conflicts.add(place);
            return null;
        }
        final Object held = value(place, story, now);
        if (shown.equals(held)) {
            return null;
        }
        if (!Objects.equals(value(place, story, judged), held)) {
            conflicts.add(place);
            return null;
        }
        return target;
    }

    /**
     * The topic labels left to write: each label the command changes takes its target, every other
     * the value it holds now; none when each changed label stands already.
     */
    private static @Nullable List<String> topics(
            final MergeTargets targets,
            final ItemView judged,
            final ItemView now,
            final Set<Place> conflicts) {
        final List<String> asked = targets.topics();
        if (asked == null) {
            return null;
        }
        final List<String> target = TopicLabels.topicOf(asked);
        final List<String> before = TopicLabels.topicOf(judged.labels());
        final List<String> held = TopicLabels.topicOf(now.labels());
        final boolean blind = labelsBlind(judged) || labelsBlind(now);
        final List<String> after = new ArrayList<>();
        boolean any = false;
        for (final String name : TopicLabels.NAMES) {
            final boolean wanted = target.contains(name);
            final boolean changed = wanted != before.contains(name);
            if (changed && blind) {
                conflicts.add(Place.label(name));
            }
            // One label holds one of two values, so now is either the judged one or the target.
            any |= changed && wanted != held.contains(name);
            if (changed ? wanted : held.contains(name)) {
                after.add(name);
            }
        }
        return any ? List.copyOf(after) : null;
    }

    /** Collects the body's parts left to write and remove into the two given. */
    private static void body(
            final MergeTargets targets,
            final ItemView judged,
            final ItemView now,
            final Set<Place> conflicts,
            final Map<TextEdit.Part, String> written,
            final Set<TextEdit.Part> removed) {
        if (targets.written().isEmpty() && targets.removed().isEmpty()) {
            return;
        }
        final List<Place> places = new ArrayList<>();
        targets.removed().forEach(part -> places.add(Place.body(part)));
        targets.written().keySet().forEach(part -> places.add(Place.body(part)));
        if (bodyBlind(judged) || bodyBlind(now)) {
            conflicts.addAll(places);
            return;
        }
        final BodySections before = BodySections.read(judged.body());
        final BodySections held = BodySections.read(now.body());
        for (final TextEdit.Part part : TextEdit.Part.values()) {
            final boolean removes = targets.removed().contains(part);
            final String text = targets.written().get(part);
            if (!removes && text == null) {
                continue;
            }
            final BodySections.Part on = BodySections.Part.of(part);
            final boolean stands;
            try {
                stands =
                        text == null
                                ? held.part(on).isEmpty()
                                : held.withEdited(on, text).text().equals(now.body());
            } catch (final IllegalArgumentException e) {
                conflicts.add(Place.body(part));
                continue;
            }
            if (stands) {
                continue;
            }
            if (!before.part(on).equals(held.part(on))) {
                conflicts.add(Place.body(part));
            } else if (text == null) {
                removed.add(part);
            } else {
                written.put(part, text);
            }
        }
        try {
            BodySections result = held;
            for (final TextEdit.Part part : removed) {
                result = result.without(BodySections.Part.of(part));
            }
            for (final Map.Entry<TextEdit.Part, String> part : written.entrySet()) {
                result = result.withEdited(BodySections.Part.of(part.getKey()), part.getValue());
            }
            result.text();
        } catch (final IllegalArgumentException e) {
            // Parts that each fit but together make the body too long, or its parts unclear.
            removed.forEach(part -> conflicts.add(Place.body(part)));
            written.keySet().forEach(part -> conflicts.add(Place.body(part)));
        }
    }

    /** Whether the item stands in the state already. */
    private static boolean stands(
            final MergeTargets.State state, final boolean story, final ItemView item) {
        if (state.open()) {
            return item.open()
                    && (story
                            || Objects.equals(
                                    item.fields().get(FieldNames.STATUS), state.status()));
        }
        return !item.open() && IssueMapping.rejecting(item.stateReason()) == state.notPlanned();
    }

    /** Whether the item does not show the place whole. */
    private static boolean blind(final Place place, final boolean story, final ItemView item) {
        return switch (place.kind()) {
            case ITEM, TITLE, PARENT -> false;
            case STATE -> !story && fieldsBlind(item);
            case WORKER, PRIORITY, FIELD -> fieldsBlind(item);
            case LABEL -> labelsBlind(item);
            case BODY -> place.name() != null && bodyBlind(item);
            case BLOCKERS ->
                    item.unseenBlockers() > 0 || item.blockerCount() > item.blockedByIds().size();
            case SUB_ISSUES ->
                    item.subIssueCount() > item.subIssueIds().size() || story && bodyBlind(item);
        };
    }

    /** The place's value on the item, compared between the judged item and the item now. */
    private static @Nullable Object value(
            final Place place, final boolean story, final ItemView item) {
        final String name = place.name();
        return switch (place.kind()) {
            case ITEM -> identity(item);
            case STATE ->
                    story
                            ? Arrays.asList(item.open(), item.stateReason())
                            : Arrays.asList(
                                    item.fields().get(FieldNames.STATUS),
                                    item.open(),
                                    item.stateReason());
            case WORKER -> item.fields().get(FieldNames.WORKER);
            case PRIORITY -> item.fields().get(FieldNames.PRIORITY);
            case FIELD -> item.fields().get(Objects.requireNonNull(name));
            case LABEL -> TopicLabels.topicOf(item.labels()).contains(name);
            case TITLE -> item.title();
            case BODY ->
                    name == null
                            ? item.body()
                            : BodySections.read(item.body())
                                    .part(BodySections.Part.of(TextEdit.Part.valueOf(name)));
            case BLOCKERS ->
                    Arrays.asList(
                            item.blockedByIds().stream().sorted().toList(),
                            item.unseenBlockers(),
                            item.blockerCount());
            case PARENT -> item.parentIssueId();
            case SUB_ISSUES ->
                    story
                            ? Arrays.asList(
                                    item.subIssueIds(),
                                    BodySections.read(item.body()).section(IssueMapping.ALSO_NEEDS))
                            : item.subIssueIds();
        };
    }

    /** What makes the item the same one: its ids, repository, number and issue type. */
    private static List<@Nullable Object> identity(final ItemView item) {
        return Arrays.asList(
                item.itemId(), item.issueId(), item.repository(), item.number(), item.issueType());
    }

    private static boolean fieldsBlind(final ItemView item) {
        return item.unseenValues() > 0 || item.partial();
    }

    private static boolean labelsBlind(final ItemView item) {
        return item.unseenLabels() > 0 || item.partial();
    }

    /** Whether the body is too long to read or its parts cannot be told apart. */
    private static boolean bodyBlind(final ItemView item) {
        try {
            return BodySections.read(item.body()).ambiguity().isPresent();
        } catch (final IllegalArgumentException e) {
            return true;
        }
    }
}
