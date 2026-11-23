package org.fuin.porelo.planner.store.github;

import java.util.List;
import org.fuin.porelo.forge.github.model.IssueField;
import org.fuin.porelo.forge.github.model.IssueFieldOptionsInner;
import org.fuin.porelo.forge.github.model.IssueFieldValue;
import org.fuin.porelo.forge.github.model.IssueFieldValueSingleSelectOption;
import org.fuin.porelo.forge.github.model.IssuesCreateRequestIssueFieldValuesInner;

/** The organization's issue fields and values as a fake GitHub answers them. */
final class OrganizationFields {

    /** The id of the organization's issue field Priority. */
    static final long PRIORITY_ID = 11L;

    /** The options of the organization's Priority, in GitHub's order. */
    static final List<String> OPTIONS = List.of("Urgent", "High", "Medium", "Low");

    private OrganizationFields() {}

    /** The organization's issue fields: Priority alone. */
    static List<IssueField> fields() {
        return List.of(
                new IssueField(
                        PRIORITY_ID,
                        "IF_11",
                        FieldNames.PRIORITY,
                        "",
                        IssueField.DataTypeEnum.SINGLE_SELECT,
                        null,
                        OPTIONS.stream()
                                .map(
                                        name ->
                                                new IssueFieldOptionsInner(
                                                        optionId(name),
                                                        name,
                                                        null,
                                                        IssueFieldOptionsInner.ColorEnum.GRAY,
                                                        null,
                                                        null,
                                                        null))
                                .toList(),
                        null,
                        null));
    }

    /** An issue's Priority value holding the option named. */
    static IssueFieldValue priority(final String name) {
        return new IssueFieldValue(
                PRIORITY_ID,
                FieldNames.PRIORITY,
                "IFV_11",
                IssueFieldValue.DataTypeEnum.SINGLE_SELECT,
                optionId(name),
                new IssueFieldValueSingleSelectOption(optionId(name), name, "gray"),
                null);
    }

    /** The issue field values GitHub answers a create with: the Priority options sent. */
    static List<IssueFieldValue> answered(
            final List<IssuesCreateRequestIssueFieldValuesInner> sent) {
        return sent.stream()
                .filter(value -> value.fieldId() == PRIORITY_ID)
                .map(value -> priority((String) value.value()))
                .toList();
    }

    private static long optionId(final String name) {
        return 100L + Math.max(0, OPTIONS.indexOf(name));
    }
}
