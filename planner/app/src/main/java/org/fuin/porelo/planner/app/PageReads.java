package org.fuin.porelo.planner.app;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.fuin.porelo.planner.core.AgentReads;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Ids;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Measures complete wire answers while the plain-Java pager selects whole items. */
@Component
final class PageReads {

    private final JsonMapper json;

    /** Uses the HTTP mapper so escaping and the response envelope count toward the same bound. */
    PageReads(final JsonMapper json) {
        this.json = json;
    }

    /** Supplies the default mapper for controllers constructed outside the application context. */
    static PageReads plain() {
        return new PageReads(JsonMapper.builder().build());
    }

    /** Reads repeated filters without splitting names at commas. */
    static AgentReads.Filters filters(
            final HttpServletRequest request, final boolean priority, final String project) {
        try {
            return new AgentReads.Filters(
                    values(request, "state"),
                    priority ? values(request, "priority") : List.of(),
                    values(request, "repository"));
        } catch (final WorkRefusal refusal) {
            throw Refusal.invalidName(
                    String.valueOf(refusal.getMessage()), Map.of("project", project));
        }
    }

    /**
     * Refuses a malformed {@code limit} or {@code after} before any board is read, naming the
     * project as a page's own refusal does.
     *
     * @param request the request
     * @param project the project's checked name
     */
    static void checkPage(final HttpServletRequest request, final String project) {
        try {
            limit(request);
            scalar(request, "after");
        } catch (final WorkRefusal refusal) {
            if (refusal.name().equals("InvalidName")) {
                throw Refusal.invalidName(
                        String.valueOf(refusal.getMessage()), Map.of("project", project));
            }
            throw refusal;
        }
    }

    /**
     * Selects and serializes one page against the canonical identity of its project and selection.
     */
    <T, R> R read(
            final Board board,
            final String project,
            final String operation,
            final AgentReads.Filters filters,
            final List<String> words,
            final HttpServletRequest request,
            final List<T> items,
            final Function<AgentReads.Page<T>, R> answer) {
        final String identity =
                json.writeValueAsString(
                        List.of(
                                project,
                                operation,
                                filters.states(),
                                filters.priorities(),
                                filters.repositories(),
                                words.stream().distinct().sorted().toList()));
        try {
            final AgentReads.Page<T> page =
                    AgentReads.page(
                            board.revision().value(),
                            digest(identity),
                            items,
                            limit(request),
                            scalar(request, "after"),
                            candidate -> json.writeValueAsString(answer.apply(candidate)).length());
            return answer.apply(page);
        } catch (final WorkRefusal refusal) {
            if (refusal.name().equals("InvalidName")) {
                throw Refusal.invalidName(
                        String.valueOf(refusal.getMessage()), Map.of("project", project));
            }
            throw refusal;
        }
    }

    private static String digest(final String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException failure) {
            throw new IllegalStateException("The page selection cannot be identified.", failure);
        }
    }

    private static int limit(final HttpServletRequest request) {
        final @Nullable String value = scalar(request, "limit");
        try {
            return value == null ? AgentReads.DEFAULT_LIMIT : Integer.parseInt(value);
        } catch (final NumberFormatException failure) {
            throw WorkRefusal.invalidPage();
        }
    }

    private static @Nullable String scalar(final HttpServletRequest request, final String key) {
        final String @Nullable [] values = request.getParameterValues(key);
        if (values == null) return null;
        if (values.length != 1 || values[0].isBlank()) throw WorkRefusal.invalidPage();
        return values[0];
    }

    private static List<String> values(final HttpServletRequest request, final String key) {
        final String @Nullable [] values = request.getParameterValues(key);
        if (values == null) return List.of();
        if (values.length > 200) throw WorkRefusal.invalidPage();
        for (final String value : values) {
            if (value.isBlank() || value.length() > 200) throw WorkRefusal.invalidPage();
            if (key.equals("repository")) {
                try {
                    Ids.name(value);
                } catch (final IllegalArgumentException failure) {
                    throw WorkRefusal.invalidPage();
                }
            }
        }
        return List.of(values);
    }
}
