/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nu.fgv.membership.server.access;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleLadderTest {

    private final RoleLadder ladder = new RoleLadder(List.of("USER", "EDITOR", "ADMIN"));

    @Test
    void granting_admin_grants_editor_and_user() {
        assertThat(ladder.grantedBy("ADMIN")).containsExactly("USER", "EDITOR", "ADMIN");
    }

    @Test
    void granting_user_grants_only_user() {
        assertThat(ladder.grantedBy("USER")).containsExactly("USER");
    }

    @Test
    void unknown_role_is_refused() {
        assertThatThrownBy(() -> ladder.grantedBy("OWNER")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void change_to_admin_adds_what_is_missing() {
        final RoleLadder.Change change = ladder.change(List.of("USER"), "ADMIN");

        assertThat(change.add()).containsExactly("EDITOR", "ADMIN");
        assertThat(change.remove()).isEmpty();
    }

    @Test
    void change_to_user_removes_the_roles_above() {
        final RoleLadder.Change change = ladder.change(List.of("USER", "EDITOR", "ADMIN"), "USER");

        assertThat(change.add()).isEmpty();
        assertThat(change.remove()).containsExactly("EDITOR", "ADMIN");
    }

    @Test
    void change_to_nothing_removes_all_access_but_leaves_service_roles() {
        final RoleLadder.Change change = ladder.change(List.of("USER", "EDITOR", "NOTIFY"), null);

        assertThat(change.remove()).containsExactly("USER", "EDITOR");
        assertThat(change.add()).isEmpty();
    }

    @Test
    void change_repairs_a_user_with_admin_but_not_editor() {
        final List<String> held = List.of("USER", "ADMIN");

        assertThat(ladder.isConsistent(held)).isFalse();
        assertThat(ladder.change(held, "ADMIN").add()).containsExactly("EDITOR");
    }

    @Test
    void no_change_when_already_right() {
        assertThat(ladder.change(List.of("USER", "EDITOR"), "EDITOR").isEmpty()).isTrue();
    }

    @Test
    void highest_ignores_roles_off_the_ladder() {
        assertThat(ladder.highest(List.of("NOTIFY", "EDITOR", "USER"))).contains("EDITOR");
        assertThat(ladder.highest(List.of("NOTIFY"))).isEmpty();
    }
}
