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

package nu.fgv.membership.server.util.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakJwtRolesConverterTest {

    private final KeycloakJwtRolesConverter converter = new KeycloakJwtRolesConverter();

    @Test
    void admin_is_also_treasurer_board_and_user() {
        assertThat(authorities(Map.of("fgv-membership", Map.of("roles", List.of("ADMIN")))))
                .contains("ROLE_fgv-membership_ADMIN", "ROLE_fgv-membership_TREASURER", "ROLE_fgv-membership_BOARD", "ROLE_fgv-membership_USER");
    }

    @Test
    void board_is_not_treasurer() {
        assertThat(authorities(Map.of("fgv-membership", Map.of("roles", List.of("BOARD")))))
                .contains("ROLE_fgv-membership_BOARD", "ROLE_fgv-membership_USER")
                .doesNotContain("ROLE_fgv-membership_TREASURER");
    }

    @Test
    void other_applications_roles_are_not_expanded() {
        assertThat(authorities(Map.of("fgv-spexregister", Map.of("roles", List.of("ADMIN")))))
                .containsExactly("ROLE_fgv-spexregister_ADMIN");
    }

    @Test
    void no_roles_no_authorities() {
        assertThat(converter.convert(jwt(Map.of("sub", "1")))).isEmpty();
    }

    private List<String> authorities(final Map<String, Object> resourceAccess) {
        return converter.convert(jwt(Map.of("sub", "1", "resource_access", resourceAccess))).stream().map(GrantedAuthority::getAuthority).toList();
    }

    private Jwt jwt(final Map<String, Object> claims) {
        return new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), claims);
    }
}
