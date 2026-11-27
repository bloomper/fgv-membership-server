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

package nu.fgv.membership.server.registration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A registration: who asked for an account, the hash of the token in the verification link, and the message to the
 * administrators. Names and addresses are kept in Keycloak, not here.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "registration")
public class Registration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long pk;

    @Column(nullable = false, length = 36, unique = true)
    private String id = UUID.randomUUID().toString();

    @Column(name = "keycloak_user_id", nullable = false, length = 36)
    private String keycloakUserId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(length = 500)
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Registration() {
    }

    public Registration(final String keycloakUserId, final String tokenHash, final Instant expiresAt, final String message) {
        this.keycloakUserId = keycloakUserId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.message = message;
    }

    public String getKeycloakUserId() {
        return keycloakUserId;
    }

    public String getMessage() {
        return message;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    public void renew(final String newTokenHash, final Instant newExpiresAt) {
        this.tokenHash = newTokenHash;
        this.expiresAt = newExpiresAt;
    }

    public void markVerified() {
        this.verifiedAt = Instant.now();
    }
}
