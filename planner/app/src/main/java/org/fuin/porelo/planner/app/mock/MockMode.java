package org.fuin.porelo.planner.app.mock;

/**
 * Present as a bean only when the planner was started with {@code --mock}; no profile, property or
 * environment variable can put it there.
 */
public record MockMode() {}
