package org.fuin.porelo.planner.app;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The answers of the board reads, one record per schema of {@code openapi/planner.yaml}, field for
 * field.
 */
public final class Answers {

    private Answers() {}

    /**
     * The workflow.
     *
     * @param revision the board's revision
     * @param states each state and its kind
     * @param phases the board's columns
     * @param work the sound work rules
     * @param faults why rules were dropped or could not be read
     */
    public record Workflow(
            String revision,
            List<State> states,
            List<Phase> phases,
            List<WorkRule> work,
            List<String> faults) {}

    /**
     * The whole board, as an event of the changes stream carries it.
     *
     * @param revision the board's revision
     * @param states each state and its kind
     * @param phases the board's columns
     * @param work the sound work rules
     * @param faults why rules were dropped or could not be read
     * @param stories every story, without its description
     */
    public record Board(
            String revision,
            List<State> states,
            List<Phase> phases,
            List<WorkRule> work,
            List<String> faults,
            List<Story> stories) {}

    /**
     * The revision after a fetch.
     *
     * @param revision the board's revision
     */
    public record Refreshed(String revision) {}

    /**
     * The answer to a saved batch of moves.
     *
     * @param revision the board's revision after the save
     * @param moved every issue the batch moved, each once
     */
    public record Moved(String revision, List<String> moved) {}

    /**
     * A state.
     *
     * @param name its name
     * @param kind {@code WAITING}, {@code WORKING} or {@code CLOSED}
     */
    public record State(String name, String kind) {}

    /**
     * A phase.
     *
     * @param name its name
     * @param states its states
     */
    public record Phase(String name, List<String> states) {}

    /**
     * A work rule.
     *
     * @param state the working state
     * @param from the states work starts from
     * @param done the state work ends in
     * @param then the next working state, if any
     * @param blocked the working state findings lead to, if any
     * @param per {@code story} or {@code repository}
     * @param modes how a task may be started
     * @param order the order a task is given
     * @param models the model per agent, in order
     */
    public record WorkRule(
            String state,
            List<String> from,
            String done,
            @Nullable String then,
            @Nullable String blocked,
            String per,
            List<String> modes,
            String order,
            Map<String, String> models) {}

    /**
     * Stories.
     *
     * @param revision the board's revision
     * @param stories the stories asked for
     */
    public record StoryList(String revision, List<Story> stories) {}

    /**
     * One story.
     *
     * @param revision the board's revision
     * @param story the story with its description
     */
    public record StoryReply(String revision, StoryDetail story) {}

    /**
     * A story without its description.
     *
     * @param id its id
     * @param title its title
     * @param state its state
     * @param stateGiven whether its head states the state, rather than its issues giving it
     * @param priority its priority
     * @param link where a person reads it, if known
     * @param issues its issues, in its order
     * @param work its work, always null until the planner starts work
     */
    public record Story(
            String id,
            String title,
            String state,
            boolean stateGiven,
            String priority,
            @Nullable String link,
            List<Issue> issues,
            @Nullable Object work) {}

    /**
     * A story with its description.
     *
     * @param id its id
     * @param title its title
     * @param state its state
     * @param stateGiven whether its head states the state, rather than its issues giving it
     * @param priority its priority
     * @param link where a person reads it, if known
     * @param issues its issues, in its order
     * @param work its work, always null until the planner starts work
     * @param description its body
     */
    public record StoryDetail(
            String id,
            String title,
            String state,
            boolean stateGiven,
            String priority,
            @Nullable String link,
            List<Issue> issues,
            @Nullable Object work,
            String description) {}

    /**
     * Issues.
     *
     * @param revision the board's revision
     * @param issues the issues asked for
     */
    public record IssueList(String revision, List<Issue> issues) {}

    /**
     * One issue.
     *
     * @param revision the board's revision
     * @param issue the issue with its text
     */
    public record IssueReply(String revision, IssueDetail issue) {}

    /**
     * An issue.
     *
     * @param id its id
     * @param repository its repository's name
     * @param title its title
     * @param state its state
     * @param size its size, if it has one
     * @param blockedBy the issues it waits on
     * @param parent its parent, for a sub-issue
     * @param link where a person reads it, if known
     */
    public record Issue(
            String id,
            String repository,
            String title,
            String state,
            @Nullable String size,
            List<String> blockedBy,
            @Nullable String parent,
            @Nullable String link) {}

    /**
     * An issue with its text.
     *
     * @param id its id
     * @param repository its repository's name
     * @param title its title
     * @param state its state
     * @param size its size, if it has one
     * @param blockedBy the issues it waits on
     * @param parent its parent, for a sub-issue
     * @param link where a person reads it, if known
     * @param text its body
     * @param subIssues its sub-issues' ids
     * @param whatMustBeTrue its paragraph of that name, left out when it has none
     */
    public record IssueDetail(
            String id,
            String repository,
            String title,
            String state,
            @Nullable String size,
            List<String> blockedBy,
            @Nullable String parent,
            @Nullable String link,
            String text,
            List<String> subIssues,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String whatMustBeTrue) {}
}
