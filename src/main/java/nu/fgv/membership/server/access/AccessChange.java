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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The audit of user administration: who gave or took away which roles on which application, and when.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "access_change")
public class AccessChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long pk;

    @Column(nullable = false, length = 36, unique = true)
    private String id = java.util.UUID.randomUUID().toString();

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "changed_by", nullable = false, length = 36)
    private String changedBy;

    @Column(nullable = false, length = 20)
    private String action;

    @Column(name = "client_id", length = 100)
    private String clientId;

    @Column(name = "roles_before", length = 500)
    private String rolesBefore;

    @Column(name = "roles_after", length = 500)
    private String rolesAfter;

    @Column(nullable = false)
    private Instant at = Instant.now();

    protected AccessChange() {
    }

    public AccessChange(final String userId, final String changedBy, final String action, final String clientId,
                        final String rolesBefore, final String rolesAfter) {
        this.userId = userId;
        this.changedBy = changedBy;
        this.action = action;
        this.clientId = clientId;
        this.rolesBefore = rolesBefore;
        this.rolesAfter = rolesAfter;
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public String getAction() {
        return action;
    }

    public String getClientId() {
        return clientId;
    }

    public String getRolesBefore() {
        return rolesBefore;
    }

    public String getRolesAfter() {
        return rolesAfter;
    }

    public Instant getAt() {
        return at;
    }
}
