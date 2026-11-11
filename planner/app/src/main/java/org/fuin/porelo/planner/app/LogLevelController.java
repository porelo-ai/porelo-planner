package org.fuin.porelo.planner.app;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The level of the planner's log, as the window's settings set it. */
@RestController
public class LogLevelController {

    private final LogLevel level;

    /**
     * Makes the controller.
     *
     * @param level the level in force
     */
    public LogLevelController(final LogLevel level) {
        this.level = level;
    }

    /**
     * Answers the level in force.
     *
     * @return the level
     */
    @GetMapping("/log/level")
    public Level get() {
        return new Level(level.level());
    }

    /**
     * Sets the level, at once and from the next start too.
     *
     * @param chosen the level the person chose
     * @return the level now in force
     */
    @PutMapping("/log/level")
    public Level put(@RequestBody final Level chosen) {
        return new Level(level.set(chosen.level()));
    }

    /**
     * A level of the log.
     *
     * @param level one of ERROR, WARN, INFO and DEBUG
     */
    public record Level(@Nullable String level) {}
}
