package org.fuin.porelo.planner.core;

import org.jspecify.annotations.Nullable;

/** What is open at a run's place, as Porelo answers it, however it is reached. */
public interface PlaceReviews {

    /**
     * Answers what is open at a place for a role, and the place's decision.
     *
     * @param project the project's name
     * @param place the place
     * @param role the role asking, or null for an author
     * @param task the task whose run asks, as Start took its name; needed for its own pull request
     * @return the answer, which may say it is incomplete
     * @throws TaskRecords.Unanswered when Porelo gives no answer that can be trusted, naming why
     */
    ReviewOpen open(String project, RunPlace place, @Nullable RunRole role, @Nullable String task);

    /** Porelo refused the place by one of its typed refusals, such as PlaceUnreadable. */
    final class Refused extends TaskRecords.Unanswered {

        private static final long serialVersionUID = 1L;

        /** The refusal's name, as Porelo's contract names it. */
        private final String refusal;

        /** The refusal's reason where it gives one, such as NoSuchPush, or null. */
        private final @Nullable String reason;

        /**
         * @param refusal the refusal's name
         * @param reason its reason, or null
         */
        public Refused(final String refusal, final @Nullable String reason) {
            super(
                    "Porelo refused the place: "
                            + refusal
                            + (reason == null ? "" : " (" + reason + ")"));
            this.refusal = refusal;
            this.reason = reason;
        }

        /**
         * @return the refusal's name
         */
        public String refusal() {
            return refusal;
        }

        /**
         * @return its reason, or null
         */
        public @Nullable String reason() {
            return reason;
        }
    }
}
