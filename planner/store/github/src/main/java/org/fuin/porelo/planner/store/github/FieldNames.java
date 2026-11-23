package org.fuin.porelo.planner.store.github;

import java.util.Map;

/** The project field names and the organization's issue field Priority. */
public final class FieldNames {

    /** The kinds of field GitHub offers that the store uses. */
    public enum Kind {
        /** One option of a fixed list. */
        SINGLE_SELECT,
        /** A number. */
        NUMBER
    }

    /** The state, GitHub's own field, its options the workflow's states. */
    public static final String STATUS = "Status";

    /** The organization's issue field for an issue's or story's priority. */
    public static final String PRIORITY = "Priority";

    /** The size, XS to XL. */
    public static final String SIZE = "Size";

    /** How much harm the change can do. */
    public static final String RISK = "Risk";

    /** Who works it, an agent or a person. */
    public static final String WORKER = "Worker";

    /** The order within a priority. */
    public static final String RANK = "Rank";

    /** The last estimate, in minutes. */
    public static final String ESTIMATE = "Estimate";

    /** The measured minutes of its stages. */
    public static final String MINUTES = "Minutes";

    /** The minutes its stages waited on a machine. */
    public static final String VM_MINUTES = "VM minutes";

    /** The new tokens its stages spent. */
    public static final String TOKENS = "Tokens";

    /** How many stages it went through. */
    public static final String STAGES = "Stages";

    /** Every project field the store uses, by name, with its kind. */
    public static final Map<String, Kind> ALL =
            Map.ofEntries(
                    Map.entry(STATUS, Kind.SINGLE_SELECT),
                    Map.entry(SIZE, Kind.SINGLE_SELECT),
                    Map.entry(RISK, Kind.SINGLE_SELECT),
                    Map.entry(WORKER, Kind.SINGLE_SELECT),
                    Map.entry(RANK, Kind.NUMBER),
                    Map.entry(ESTIMATE, Kind.NUMBER),
                    Map.entry(MINUTES, Kind.NUMBER),
                    Map.entry(VM_MINUTES, Kind.NUMBER),
                    Map.entry(TOKENS, Kind.NUMBER),
                    Map.entry(STAGES, Kind.NUMBER));

    private FieldNames() {}
}
