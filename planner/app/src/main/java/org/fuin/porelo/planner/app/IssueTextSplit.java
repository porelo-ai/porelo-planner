package org.fuin.porelo.planner.app;

import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.porelo.planner.core.AgentReads;
import org.fuin.porelo.planner.core.Edits;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.github.BodySections;

/**
 * Splits an issue's text into the parts setIssueText writes, with the one reader that writes them,
 * so a part is read as it is written.
 */
final class IssueTextSplit implements AgentReads.Splitter {

    /** The shared splitter. */
    static final IssueTextSplit INSTANCE = new IssueTextSplit();

    private IssueTextSplit() {}

    @Override
    public AgentReads.Split split(final String text) {
        final BodySections sections;
        try {
            sections = BodySections.read(text);
        } catch (final BodySections.TooLong e) {
            return AgentReads.Split.ambiguous("The text is longer than a body holds.");
        }
        final var ambiguity = sections.ambiguity();
        if (ambiguity.isPresent()) {
            return AgentReads.Split.ambiguous(ambiguity.get());
        }
        final Map<String, String> parts = new LinkedHashMap<>();
        for (final TextEdit.Part part : TextEdit.Part.values()) {
            if (part.of() == TextEdit.Kind.ISSUE) {
                sections.part(BodySections.Part.of(part))
                        .ifPresent(held -> parts.put(Edits.member(part), held));
            }
        }
        return AgentReads.Split.of(parts);
    }
}
