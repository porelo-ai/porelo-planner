package org.fuin.porelo.planner.app;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.git.LogText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs each board the store reads, with every fault it left out, once per revision and project, so
 * a person sees why a board was read with faults without a line per poll.
 */
final class BoardLog {

    private static final Logger LOG = LoggerFactory.getLogger(BoardLog.class);

    /** The revision last logged per project. */
    private static final Map<String, String> LOGGED = new ConcurrentHashMap<>();

    private BoardLog() {}

    /**
     * Logs a board the store read, unless its revision of that project was logged last.
     *
     * @param project the project's name
     * @param board the board read
     * @return the board
     */
    static Board read(final String project, final Board board) {
        final String revision = board.revision().value();
        if (revision.equals(LOGGED.put(project, revision))) {
            return board;
        }
        LOG.info(
                "Read project {} at revision {}: {} stories, {} issues, {} faults left out",
                LogText.of(project),
                LogText.of(revision),
                board.stories().size(),
                board.issues().size(),
                board.faults().size());
        for (final String fault : board.faults()) {
            LOG.info("Project {} left out: {}", LogText.of(project), LogText.of(fault));
        }
        return board;
    }
}
