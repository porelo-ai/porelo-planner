package org.fuin.porelo.planner.app;

import java.util.Map;

/**
 * Why a save is held, in one plain sentence for the person: the planner's fixed words for each
 * reason the outbox keeps, naming what the reason is about where there is something.
 */
final class HeldReasons {

    /**
     * The words of one reason.
     *
     * @param named the sentence naming its subject, at {@code %s}; the plain one for a reason whose
     *     subject is never shown
     * @param plain the sentence when there is no subject
     */
    record Words(String named, String plain) {

        /** Words for a reason whose subject is no name the person reads, never shown. */
        static Words unnamed(final String plain) {
            return new Words(plain, plain);
        }
    }

    /** The words of every reason the outbox keeps, by its kind. */
    static final Map<String, Words> WORDS =
            Map.ofEntries(
                    Map.entry(
                            "NoSuchStory",
                            new Words(
                                    "The story %s no longer exists.",
                                    "The story no longer exists.")),
                    Map.entry(
                            "NoSuchIssue",
                            new Words(
                                    "The issue %s no longer exists.",
                                    "The issue no longer exists.")),
                    Map.entry(
                            "WorkflowMissing",
                            new Words(
                                    "The project's workflow is missing in %s.",
                                    "The project's workflow is missing.")),
                    Map.entry(
                            "CredentialRefused",
                            new Words(
                                    "Your login was not accepted for %s.",
                                    "Your login was not accepted.")),
                    Map.entry(
                            "NoForgeAccess",
                            new Words(
                                    "Your login may not write to %s.",
                                    "Your login may not write there.")),
                    Map.entry(
                            "Unreachable",
                            new Words(
                                    "The server of %s could not be reached.",
                                    "The server could not be reached.")),
                    Map.entry(
                            "Unreadable",
                            new Words(
                                    "The answer for %s could not be read.",
                                    "The answer could not be read.")),
                    Map.entry(
                            "ItemsDirectoryRefused",
                            // Its subject is a folder of this computer.
                            Words.unnamed("The folder of the project's items cannot be used.")),
                    Map.entry(
                            "StaleRevision",
                            // Its subject is the board's revision, or that the board was not read.
                            Words.unnamed("Another save changed the project first.")),
                    Map.entry(
                            "Changed",
                            new Words(
                                    "%s was changed elsewhere since it was read.",
                                    "An item was changed elsewhere since it was read.")),
                    Map.entry(
                            "BasisIncomplete",
                            new Words(
                                    "The save did not say which version of %s it changes.",
                                    "The save did not say which version of an item it changes.")),
                    Map.entry(
                            "NotSavable",
                            new Words(
                                    "The change to %s is not allowed.",
                                    "The change is not allowed.")),
                    Map.entry(
                            "InStory",
                            new Words(
                                    "The issue %s already belongs to another story.",
                                    "The issue already belongs to another story.")),
                    Map.entry(
                            "Busy",
                            new Words(
                                    "The project %s was busy with another save.",
                                    "The project was busy with another save.")),
                    Map.entry(
                            "PartlySaved",
                            new Words(
                                    "Only part of the save may have been written to %s.",
                                    "Only part of the save may have been written.")),
                    Map.entry(
                            "LabelsNotKept",
                            new Words(
                                    "The labels of %s were not kept.",
                                    "The labels were not kept.")),
                    Map.entry(
                            "NotOffered",
                            new Words(
                                    "The save of %s failed for an unknown reason.",
                                    "The save failed for an unknown reason.")));

    /** The words for a kind without words of its own. */
    private static final Words UNKNOWN = java.util.Objects.requireNonNull(WORDS.get("NotOffered"));

    private HeldReasons() {}

    /**
     * The sentence for a reason.
     *
     * @param kind the reason's kind
     * @param subject what it is about, its words already cleaned; empty for nothing
     * @return the sentence; the one for an unknown reason when the kind has no words
     */
    static String of(final String kind, final String subject) {
        final Words words = WORDS.getOrDefault(kind, UNKNOWN);
        return subject.isEmpty() ? words.plain() : words.named().replace("%s", subject);
    }
}
