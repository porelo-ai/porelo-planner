package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FieldsTest {

    @Test
    void aValueOutOfItsFieldsBoundsIsRefused() {
        // A store writes what it is given: a size, estimate or rank the board cannot read would
        // stand on the forge as a fault on every board read after.
        final Set<Fields.Field> all = EnumSet.allOf(Fields.Field.class);
        assertThatThrownBy(() -> new Fields(all, "XXL", null, "High", null))
                .as("a size none of the five")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Fields(all, null, 0, "High", null))
                .as("an estimate of no minute")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Fields(all, null, Fields.MOST_ESTIMATE + 1, "High", null))
                .as("an estimate above the most")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Fields(all, null, null, "Urgent", null))
                .as("a priority none of the five")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Fields(all, null, null, "High", 0))
                .as("a rank below 1")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Fields(all, null, null, "High", Fields.MOST_RANK + 1))
                .as("a rank above the most")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new Fields(all, "XL", Fields.MOST_ESTIMATE, "Lowest", Fields.MOST_RANK).named())
                .as("the bounds themselves are taken")
                .isEqualTo(all);
    }

    @Test
    void aPriorityIsNeverClearedAndAValueNeedsItsFieldNamed() {
        // A cleared priority leaves the issue unsorted; a value of a field not named would be
        // written by a store that reads the values rather than the names.
        assertThatThrownBy(() -> new Fields(Set.of(Fields.Field.PRIORITY), null, null, null, null))
                .as("a priority named and cleared")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Fields(Set.of(Fields.Field.RANK), "M", null, null, 3))
                .as("a size given but not named")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new Fields(Set.of(Fields.Field.SIZE), null, null, null, null).none())
                .as("a size named to be cleared writes something")
                .isFalse();
        assertThat(new Fields(Set.of(), null, null, null, null).none())
                .as("no field named writes nothing")
                .isTrue();
    }
}
