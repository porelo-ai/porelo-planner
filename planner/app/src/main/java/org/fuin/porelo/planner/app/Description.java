package org.fuin.porelo.planner.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The planner's own description converted to JSON when the planner is built, so the running planner
 * serves it without reading YAML; a description holding an operation without one agent marker fails
 * the build.
 */
public final class Description {

    /** The resource the build writes and GET /openapi answers. */
    static final String RESOURCE = "openapi/planner.json";

    /** The member that says whether an agent may call an operation as a tool. */
    static final String MARKER = "x-mcp";

    /** The methods of an OpenAPI path item that name an operation. */
    private static final Set<String> METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    private Description() {}

    /**
     * Converts the description at the first path to JSON at the second, refusing one with an
     * operation that carries no marker.
     *
     * @param args the YAML description and the JSON file to write
     * @throws IOException when the description cannot be read or the JSON written
     */
    public static void main(final String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: Description <planner.yaml> <planner.json>");
        }
        final JsonNode description = new YAMLMapper().readTree(Path.of(args[0]).toFile());
        final List<String> faults = unmarked(description);
        if (!faults.isEmpty()) {
            throw new IllegalStateException(
                    "Every operation carries x-mcp: true or x-mcp: false <reason>; these do not: "
                            + String.join(", ", faults));
        }
        final Path out = Path.of(args[1]);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, new ObjectMapper().writeValueAsBytes(description));
    }

    /**
     * Names each operation of a description whose marker is missing or neither {@code true} nor a
     * string opening with {@code false} and a reason.
     *
     * @param description the description as a tree
     * @return each faulty operation, by method and path, in the description's order
     */
    static List<String> unmarked(final JsonNode description) {
        final List<String> faults = new ArrayList<>();
        final Iterator<Map.Entry<String, JsonNode>> paths = description.path("paths").fields();
        while (paths.hasNext()) {
            final Map.Entry<String, JsonNode> path = paths.next();
            final Iterator<Map.Entry<String, JsonNode>> items = path.getValue().fields();
            while (items.hasNext()) {
                final Map.Entry<String, JsonNode> item = items.next();
                if (METHODS.contains(item.getKey()) && !marked(item.getValue().get(MARKER))) {
                    faults.add(item.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey());
                }
            }
        }
        return faults;
    }

    private static boolean marked(final @Nullable JsonNode marker) {
        if (marker == null) {
            return false;
        }
        if (marker.isBoolean()) {
            return marker.booleanValue();
        }
        return marker.isTextual() && marker.textValue().matches("false \\S.*");
    }
}
