package org.fuin.porelo.planner.store.github;

import java.time.Instant;

/**
 * One comment of an issue, as the store reads it.
 *
 * @param id the comment's node id
 * @param body its text
 * @param createdAt when GitHub took it
 */
public record CommentView(String id, String body, Instant createdAt) {}
