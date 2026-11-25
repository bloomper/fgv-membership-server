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

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * One user's standing on one application.
 *
 * @param clientId     e.g. {@code fgv-spexregister}
 * @param name         what the application is called
 * @param url          where it is
 * @param ladder       the roles that can be given, lowest first
 * @param held         the roles held on the ladder
 * @param highest      the highest role held, if any
 * @param enabled      whether the user may use it
 * @param consistent   whether the roles held follow the ladder; if not, saving repairs them
 * @param defaultAccess whether every approved user has it
 * @author Anders Jacobsson
 * @since 1.0
 */
public record ApplicationAccess(String clientId, String name, @Nullable String url, List<String> ladder, List<String> held,
                                @Nullable String highest, boolean enabled, boolean consistent, boolean defaultAccess) {
}
