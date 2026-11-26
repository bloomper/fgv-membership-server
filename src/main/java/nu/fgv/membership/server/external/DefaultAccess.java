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

package nu.fgv.membership.server.external;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import nu.fgv.membership.server.payment.AccessProfile;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * The role a user created from an outside payment gets on an application.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@IdClass(DefaultAccess.Key.class)
@Table(name = "default_access")
public class DefaultAccess {

    /**
     * @param profile which kinds of membership get this access
     */
    public record Key(AccessProfile profile, String clientId) implements java.io.Serializable {
    }

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private AccessProfile profile;

    @Id
    @Column(name = "client_id", length = 100)
    private String clientId;

    @Column(nullable = false, length = 50)
    private String role;

    protected DefaultAccess() {
    }

    public DefaultAccess(final AccessProfile profile, final String clientId, final String role) {
        this.profile = profile;
        this.clientId = clientId;
        this.role = role;
    }

    public AccessProfile getProfile() {
        return profile;
    }

    public String getClientId() {
        return clientId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(final String role) {
        this.role = role;
    }
}
