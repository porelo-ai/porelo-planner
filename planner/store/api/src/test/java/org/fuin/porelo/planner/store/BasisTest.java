package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A caller's edit basis and the complete ids of refusals cannot change after construction. */
class BasisTest {

    @Test
    void aBasisKeepsTheVersionsSeenEvenWhenItsInputsAreChanged() {
        // Harm: mutable request data changes which edits a write is allowed to overwrite.
        final Version seen = new Version("seen");
        final Map<String, Version> input = new HashMap<>(Map.of("AB1", seen));
        final Basis basis = new Basis(input);
        input.clear();
        assertThat(basis.of("AB1")).as("the captured version").isEqualTo(seen);
        assertThat(basis.of("AB2")).as("a missing version stays missing").isNull();
        assertThat(Basis.none().items()).as("an empty basis").isEmpty();
        final Basis replaced = basis.with("AB1", new Version("new")).with("AB2", seen);
        assertThat(basis.items())
                .as("with leaves the original basis intact")
                .containsExactlyEntriesOf(Map.of("AB1", seen));
        assertThat(replaced.items())
                .as("with captures both replacements and additions")
                .containsExactlyInAnyOrderEntriesOf(Map.of("AB1", new Version("new"), "AB2", seen));
        assertThatThrownBy(() -> basis.items().clear())
                .as("the captured map cannot be changed")
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void refusalsKeepEveryIdAndCannotNameNoItem() {
        // Harm: an empty or mutable refusal hides which items must be reread before retrying.
        final List<String> ids = new ArrayList<>(List.of("AB1", "ST1"));
        final StoreException.Changed changed = new StoreException.Changed(ids);
        final StoreException.BasisIncomplete missing = new StoreException.BasisIncomplete(ids);
        ids.clear();
        assertThat(changed.ids()).as("changed targets are captured").containsExactly("AB1", "ST1");
        assertThat(missing.missing())
                .as("missing targets are captured")
                .containsExactly("AB1", "ST1");
        assertThatThrownBy(() -> changed.ids().clear())
                .as("changed ids are immutable")
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> missing.missing().clear())
                .as("missing ids are immutable")
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new StoreException.Changed(List.of()))
                .as("a change names a target")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoreException.BasisIncomplete(List.of()))
                .as("incompleteness names a target")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
