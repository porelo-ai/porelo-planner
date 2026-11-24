package org.fuin.porelo.planner.app;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.fuin.porelo.planner.core.RefusedEventResave;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.CommandQueue;

/**
 * One project's command queue as the registry opens, recovers, resumes and drains it: the command
 * runner over the project's outbox. The registry calls {@link #open} once, before any group is
 * submitted, then {@link #fresh}, {@link #unqueue} and {@link #hold} for a run's groups, then
 * {@link #resumeAside} once the project is given, and {@link #drain} before the project is given
 * anew or forgotten. Each method is the outbox's or the runner's of the same name.
 */
interface ProjectQueue extends CommandQueue {

    /**
     * Opens the project's outbox and settles every command a stopped planner left begun, reading
     * the board only when one was.
     *
     * @param board reads the board the store holds now
     * @return each command settled, by id, with the state it took
     */
    Map<String, CommandOutbox.State> open(Supplier<Board> board);

    /**
     * Answers every group the outbox holds not written whole.
     *
     * @return the groups, each with all of its commands
     */
    List<CommandOutbox.Group> pending();

    /**
     * Tells whether none of a group's commands was begun, in this run or before.
     *
     * @param group the group's id
     * @return true when every command waits and none was begun before
     */
    boolean fresh(String group);

    /**
     * Removes a group none of whose commands was begun.
     *
     * @param group the group's id
     */
    void unqueue(String group);

    /**
     * Holds a group for the owner account: each command of it waiting or begun is held; those done
     * stay done, and one held already stays as it is.
     *
     * @param group the group's id
     * @param why why it is held
     */
    void hold(String group, Throwable why);

    /**
     * Runs the groups left waiting at open on one thread of their own.
     *
     * @return the thread
     */
    Thread resumeAside();

    /**
     * Refuses every new group, waits for the groups under way and the resumed ones to end, then
     * closes the outbox, even after the time given.
     *
     * @param within the most time to wait
     * @return true when every group under way ended in time; false leaves a command at the store,
     *     which a later drain waits for again
     */
    boolean drain(Duration within);

    /**
     * Answers the saves the outbox keeps for the owner account, a run's refused event saved again
     * through the given runs.
     *
     * @param runs the project's runs, which save a refused event again
     * @return the held saves
     */
    HeldSaves held(RefusedEventResave runs);
}
