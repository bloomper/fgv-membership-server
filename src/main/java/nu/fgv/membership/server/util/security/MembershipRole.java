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

import java.util.Arrays;
import java.util.List;

/**
 * Medlemskap's roles on {@code fgv-membership}, lowest first. A role includes every role below it.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public enum MembershipRole {
    USER,
    BOARD,
    TREASURER,
    ADMIN;

    public static final String CLIENT_ID = "fgv-membership";

    public String authority() {
        return "ROLE_" + CLIENT_ID + "_" + name();
    }

    /**
     * @return the roles below this one
     */
    public List<MembershipRole> implied() {
        return Arrays.stream(values()).filter(role -> role.ordinal() < ordinal()).toList();
    }
}
