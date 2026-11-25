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

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Turns Keycloak's client roles into authorities, e.g. {@code ROLE_fgv-membership_ADMIN}, and adds the roles an
 * authority implies: each role of Medlemskap includes the ones below it (see {@link MembershipRole}).
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public class KeycloakJwtRolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String CLAIM_RESOURCE_ACCESS = "resource_access";

    @Override
    @SuppressWarnings("unchecked")
    public Collection<GrantedAuthority> convert(final Jwt jwt) {
        final Collection<GrantedAuthority> authorities = new ArrayList<>();
        final Map<String, Object> resourceAccess = jwt.getClaim(CLAIM_RESOURCE_ACCESS);

        if (resourceAccess == null) {
            return authorities;
        }

        resourceAccess.forEach((resource, claims) -> {
            if (claims instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> roles) {
                roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + resource + "_" + role)));
            }
        });

        // Medlemskap's roles are a ladder: ADMIN is also TREASURER, BOARD and USER
        final List<String> held = authorities.stream().map(GrantedAuthority::getAuthority).toList();

        for (final MembershipRole role : MembershipRole.values()) {
            if (held.contains(role.authority())) {
                role.implied().forEach(implied -> {
                    if (!held.contains(implied.authority())) {
                        authorities.add(new SimpleGrantedAuthority(implied.authority()));
                    }
                });
            }
        }
        return authorities;
    }
}
