package org.fuin.porelo.planner.store.github;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.model.IssueField;
import org.fuin.porelo.forge.github.model.IssueFieldValue;
import org.fuin.porelo.forge.github.model.IssuesCreateRequestIssueFieldValuesInner;
import org.fuin.porelo.forge.github.model.IssuesUpdateRequestIssueFieldValuesInner;
import org.fuin.porelo.planner.store.StoreException;
import org.jspecify.annotations.Nullable;

/**
 * The organization's issue field Priority and its translation to the planner's words: Highest is
 * Urgent on GitHub, High, Medium and Low keep their words, and Lowest has no option there.
 */
final class IssuePriority {

    /** The refusal of a priority GitHub's issue field cannot hold, in fixed words. */
    static final String NO_OPTION = "a priority on GitHub is Highest, High, Medium or Low";

    private IssuePriority() {}

    /**
     * The planner's word for an option of the field.
     *
     * @param option the option's name, or null for none
     * @return the planner's priority, or null for none or an option the planner does not know
     */
    static @Nullable String read(final @Nullable String option) {
        return switch (option) {
            case "Urgent" -> "Highest";
            case "High", "Medium", "Low" -> option;
            case null, default -> null;
        };
    }

    /**
     * The field's option for a planner's priority.
     *
     * @param id the item the priority is for, named in a refusal
     * @param priority the planner's priority
     * @return the option's name
     * @throws StoreException.NotSavable for Lowest, none or a word the planner does not know
     */
    static String option(final String id, final @Nullable String priority) {
        return switch (priority) {
            case "Highest" -> "Urgent";
            case "High", "Medium", "Low" -> priority;
            case null, default -> throw new StoreException.NotSavable(id, NO_OPTION);
        };
    }

    /** The option of the field an issue holds as a board read gives it, or null for none. */
    static @Nullable String held(final GitHubGraphQl.BoardIssue issue) {
        return issue.fieldOptions().stream()
                .filter(value -> FieldNames.PRIORITY.equals(value.field()))
                .map(GitHubGraphQl.IssueFieldOption::option)
                .findFirst()
                .orElse(null);
    }

    /** The option of the field among an issue's values as REST gives them, or null for none. */
    static @Nullable String held(final List<IssueFieldValue> values) {
        return values.stream()
                .filter(value -> FieldNames.PRIORITY.equals(value.issueFieldName()))
                .filter(value -> value.dataType() == IssueFieldValue.DataTypeEnum.SINGLE_SELECT)
                .map(IssueFieldValue::singleSelectOption)
                .filter(Objects::nonNull)
                .map(value -> value.name())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * The organization's single-select issue field Priority among its issue fields.
     *
     * @throws StoreException.NotSavable when the organization has none
     */
    static IssueField field(final String id, final List<IssueField> fields) {
        return fields.stream()
                .filter(
                        field ->
                                FieldNames.PRIORITY.equals(field.name())
                                        && field.dataType() == IssueField.DataTypeEnum.SINGLE_SELECT
                                        && field.id() != null)
                .findFirst()
                .orElseThrow(
                        () ->
                                new StoreException.NotSavable(
                                        id,
                                        "the organization has no single-select issue field Priority"));
    }

    /** The planner's priorities GitHub's field can hold, the highest first: it has no Lowest. */
    private static final List<String> HOLDABLE = List.of("Highest", "High", "Medium", "Low");

    /**
     * The priorities the organization's issue field Priority holds, the highest first: those of
     * Highest, High, Medium and Low whose option the field offers.
     *
     * @param fields the organization's issue fields
     * @return the priorities; none when the organization has no single-select field Priority
     */
    static List<String> offered(final List<IssueField> fields) {
        final IssueField field =
                fields.stream()
                        .filter(
                                each ->
                                        FieldNames.PRIORITY.equals(each.name())
                                                && each.dataType()
                                                        == IssueField.DataTypeEnum.SINGLE_SELECT
                                                && each.id() != null)
                        .findFirst()
                        .orElse(null);
        if (field == null || field.options() == null) {
            return List.of();
        }
        final List<String> offered =
                field.options().stream()
                        .map(option -> option.name())
                        .filter(Objects::nonNull)
                        .toList();
        return HOLDABLE.stream()
                .filter(priority -> offered.contains(option("", priority)))
                .toList();
    }

    /**
     * The board's fault when the organization's issue fields could not be read, in fixed words.
     *
     * @param cause what kept them from being read, in fixed words
     * @return the fault
     */
    static String unread(final String cause) {
        return "The organization's issue field Priority could not be read ("
                + cause
                + "), so no story's priority is offered to set";
    }

    /** Refuses an option the organization's field does not offer. */
    static void accepts(final String id, final IssueField field, final String option) {
        if (field.options() == null
                || field.options().stream().noneMatch(value -> option.equals(value.name()))) {
            throw new StoreException.NotSavable(
                    id, "the organization's issue field Priority has no requested option");
        }
    }

    /** The issue field values a new issue is made with: its priority alone. */
    static List<IssuesCreateRequestIssueFieldValuesInner> create(
            final IssueField field, final String priority) {
        final String option = option("", priority);
        accepts("", field, option);
        return List.of(new IssuesCreateRequestIssueFieldValuesInner(field.id(), option));
    }

    /**
     * The issue field values an update sends to set a priority: since GitHub replaces every value
     * of the issue, each other value it holds is sent back as it stands.
     *
     * @param id the item, named in a refusal
     * @param field the organization's field Priority
     * @param held every issue field value the issue holds
     * @param priority the planner's priority
     * @return the values to send, or null when the issue holds that option already
     * @throws StoreException.NotSavable for a priority GitHub cannot hold, or a value of another
     *     field that cannot be sent back
     */
    static @Nullable List<IssuesUpdateRequestIssueFieldValuesInner> update(
            final String id,
            final IssueField field,
            final List<IssueFieldValue> held,
            final String priority) {
        final String option = option(id, priority);
        if (option.equals(held(held))) {
            return null;
        }
        accepts(id, field, option);
        final List<IssuesUpdateRequestIssueFieldValuesInner> values = new ArrayList<>();
        for (final IssueFieldValue value : held) {
            if (Objects.requireNonNull(field.id()).equals(value.issueFieldId())) {
                continue;
            }
            final Object sent = writable(id, value);
            values.add(
                    new IssuesUpdateRequestIssueFieldValuesInner(
                            value.issueFieldId(), sent, null, null, null));
        }
        values.add(
                new IssuesUpdateRequestIssueFieldValuesInner(field.id(), option, null, null, null));
        return List.copyOf(values);
    }

    private static Object writable(final String id, final IssueFieldValue value) {
        if (value.issueFieldId() != null && value.dataType() != null) {
            final Object raw = value.value();
            switch (value.dataType()) {
                case SINGLE_SELECT -> {
                    final var selected = value.singleSelectOption();
                    if (selected != null && selected.name() != null) {
                        return selected.name();
                    }
                }
                case TEXT, DATE -> {
                    if (raw instanceof String text) {
                        return text;
                    }
                }
                case NUMBER -> {
                    if (raw instanceof Number number && Double.isFinite(number.doubleValue())) {
                        return number;
                    }
                }
                default -> {}
            }
        }
        throw new StoreException.NotSavable(
                id, "an issue field cannot be preserved while changing its priority");
    }
}
