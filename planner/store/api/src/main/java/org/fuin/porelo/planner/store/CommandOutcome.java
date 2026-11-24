package org.fuin.porelo.planner.store;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What became of a command group: every command written, one refused with those before it written
 * and those after it unsent, or the whole group held unsent.
 */
public sealed interface CommandOutcome
        permits CommandOutcome.Done, CommandOutcome.Refused, CommandOutcome.Held {

    /**
     * Every command written.
     *
     * @param board the board after the last command, or null when the store could not answer one
     *     after every command was written: then read the board again
     * @param created what each create made, by the create's command id
     */
    record Done(@Nullable Board board, Map<String, Created> created) implements CommandOutcome {

        /** Copies the creates. */
        public Done {
            created = Map.copyOf(created);
        }
    }

    /**
     * One command refused: the commands before it written, each valid on its own version, and those
     * after it unsent, held by the refusal and never written silently.
     *
     * @param refused the command refused
     * @param cause the refusal or failure, as the store's call threw it, an {@link Error} included
     * @param mayHaveWritten false only when the store refused the command before writing any of it;
     *     otherwise part or all of it may stand at the store, and it is never sent again unseen
     * @param done the commands written before it, in order
     * @param unsent the commands after it, in order
     */
    record Refused(
            Command refused,
            Throwable cause,
            boolean mayHaveWritten,
            List<Command> done,
            List<Command> unsent)
            implements CommandOutcome {

        /** Copies the lists. */
        public Refused {
            done = List.copyOf(done);
            unsent = List.copyOf(unsent);
        }

        /**
         * Tells from the cause whether the refused command may have written.
         *
         * @param refused the command refused
         * @param cause what the store's call threw
         * @param done the commands written before it, in order
         * @param unsent the commands after it, in order
         */
        public Refused(
                final Command refused,
                final Throwable cause,
                final List<Command> done,
                final List<Command> unsent) {
            this(refused, cause, !StoreException.refusedBeforeWriting(cause), done, unsent);
        }
    }

    /**
     * The group held for the owner account, the rest of it unsent, such as when the project stayed
     * busy or the outbox could not be written; the commands before stand.
     *
     * @param done the commands written before it was held, in order; empty for none
     * @param unsent the group's commands not written, in order
     * @param why one sentence why
     */
    record Held(List<Command> done, List<Command> unsent, String why) implements CommandOutcome {

        /** Copies the commands. */
        public Held {
            done = List.copyOf(done);
            unsent = List.copyOf(unsent);
        }
    }

    /**
     * What a create made.
     *
     * @param item the new issue's or story's id
     * @param issues the issues made with a story, in order; empty for an issue
     */
    record Created(String item, List<String> issues) {

        /** Copies the issues. */
        public Created {
            issues = List.copyOf(issues);
        }
    }
}
