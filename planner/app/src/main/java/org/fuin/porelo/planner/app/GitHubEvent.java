package org.fuin.porelo.planner.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The fields of a workflow's event that decide which commits {@code check-commits --event} reads:
 * the runner's {@code GITHUB_*} variables and the event file {@code GITHUB_EVENT_PATH}, which
 * GitHub and Forgejo both write. The file is read as a JSON tree, so nothing is bound by
 * reflection.
 *
 * @param name the event's name, {@code GITHUB_EVENT_NAME}
 * @param ref the ref that ran, {@code GITHUB_REF}; empty where the runner names none
 * @param refType {@code branch} or {@code tag}, {@code GITHUB_REF_TYPE}; empty where none
 * @param sha the commit that ran, {@code GITHUB_SHA}
 * @param repository the repository that ran, {@code GITHUB_REPOSITORY}
 * @param server the server's address, {@code GITHUB_SERVER_URL}
 * @param before a push's start, the event's {@code before}; null for any other event
 * @param pullRequest a pull request's own fields; null for any other event
 */
record GitHubEvent(
        String name,
        String ref,
        String refType,
        String sha,
        String repository,
        String server,
        @Nullable String before,
        @Nullable PullRequest pullRequest) {

    /**
     * A pull request's fields.
     *
     * @param base its base commit
     * @param head its head commit
     * @param headRepository the repository its head lives in, {@code owner/name}
     * @param association the author's association with the repository; empty where the server names
     *     none, as Forgejo does
     * @param author the author's login
     */
    record PullRequest(
            String base, String head, String headRepository, String association, String author) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Reads the event from the runner's variables and the event file's text.
     *
     * @param environment the process's environment
     * @param text the event file's text
     * @return the event
     * @throws IOException naming the variable or field that is missing, or the file that is no JSON
     *     object
     */
    static GitHubEvent read(final Map<String, String> environment, final String text)
            throws IOException {
        final JsonNode event;
        try {
            event = JSON.readTree(text);
        } catch (JsonProcessingException ex) {
            throw new IOException("the event file is no JSON: " + ex.getOriginalMessage(), ex);
        }
        if (event == null || !event.isObject()) {
            throw new IOException("the event file holds no JSON object");
        }
        final String name = variable(environment, "GITHUB_EVENT_NAME");
        final String sha = variable(environment, "GITHUB_SHA");
        final String repository = variable(environment, "GITHUB_REPOSITORY");
        final String server = variable(environment, "GITHUB_SERVER_URL");
        final String ref = environment.getOrDefault("GITHUB_REF", "");
        final String refType = environment.getOrDefault("GITHUB_REF_TYPE", "");
        return switch (name) {
            case "push" ->
                    new GitHubEvent(
                            name,
                            ref,
                            refType,
                            sha,
                            repository,
                            server,
                            field(event, "/before", ""),
                            null);
            case "pull_request" ->
                    new GitHubEvent(
                            name,
                            ref,
                            refType,
                            sha,
                            repository,
                            server,
                            null,
                            new PullRequest(
                                    field(event, "/pull_request/base/sha", null),
                                    field(event, "/pull_request/head/sha", null),
                                    field(event, "/pull_request/head/repo/full_name", null),
                                    field(event, "/pull_request/author_association", ""),
                                    field(event, "/pull_request/user/login", null)));
            default -> new GitHubEvent(name, ref, refType, sha, repository, server, null, null);
        };
    }

    /**
     * A runner's variable, refused when it is missing or blank.
     *
     * @param environment the process's environment
     * @param name the variable's name
     * @return its value
     * @throws IOException naming the variable
     */
    private static String variable(final Map<String, String> environment, final String name)
            throws IOException {
        final String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IOException(name + " is not set; run this in a workflow's step");
        }
        return value;
    }

    /**
     * A text field of the event, its fallback where the field may be missing, or a refusal naming
     * it.
     *
     * @param event the event file's tree
     * @param pointer the field's JSON pointer
     * @param fallback the value of a missing or null field, or null when the field must be there
     * @return the field's text
     * @throws IOException naming the field
     */
    private static String field(
            final JsonNode event, final String pointer, final @Nullable String fallback)
            throws IOException {
        final JsonNode value = event.at(pointer);
        if (value.isTextual()) {
            return value.textValue();
        }
        if ((value.isMissingNode() || value.isNull()) && fallback != null) {
            return fallback;
        }
        throw new IOException("the event file names no " + pointer.substring(1).replace('/', '.'));
    }
}
