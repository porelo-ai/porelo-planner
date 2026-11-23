package org.fuin.porelo.planner.app.mock;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.util.List;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.Wait;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.Worker;
import org.jspecify.annotations.Nullable;

/**
 * How the mock board's file is read into issues and stories: by the constructors that take each
 * item's version over its own values, since the mock keeps no edit basis and its file names none.
 */
final class WithoutVersions {

    private WithoutVersions() {}

    /** An issue read without a version. */
    abstract static class IssueMixIn {
        @JsonCreator
        IssueMixIn(
                @JsonProperty("id") final String id,
                @JsonProperty("repository") final String repository,
                @JsonProperty("title") final String title,
                @JsonProperty("state") final String state,
                @JsonProperty("size") final @Nullable String size,
                @JsonProperty("blockedBy") final List<String> blockedBy,
                @JsonProperty("parent") final @Nullable String parent,
                @JsonProperty("link") final @Nullable URI link,
                @JsonProperty("text") final String text,
                @JsonProperty("worker") final Worker worker,
                @JsonProperty("risk") final Risk risk,
                @JsonProperty("estimate") final @Nullable Double estimate,
                @JsonProperty("minutes") final @Nullable Double minutes,
                @JsonProperty("priority") final @Nullable String priority,
                @JsonProperty("warnings") final List<Warning> warnings,
                @JsonProperty("waitsOn") final List<Wait> waitsOn) {}
    }

    /** A story read without a version. */
    abstract static class StoryMixIn {
        @JsonCreator
        StoryMixIn(
                @JsonProperty("id") final String id,
                @JsonProperty("title") final String title,
                @JsonProperty("state") final String state,
                @JsonProperty("stateGiven") final boolean stateGiven,
                @JsonProperty("priority") final String priority,
                @JsonProperty("size") final @Nullable String size,
                @JsonProperty("issues") final List<String> issues,
                @JsonProperty("description") final String description,
                @JsonProperty("link") final @Nullable URI link,
                @JsonProperty("issuesWhole") final boolean issuesWhole,
                @JsonProperty("warnings") final List<Warning> warnings) {}
    }
}
