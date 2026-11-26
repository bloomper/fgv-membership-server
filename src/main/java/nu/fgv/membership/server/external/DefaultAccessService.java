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

import nu.fgv.membership.server.access.AccessService;
import nu.fgv.membership.server.access.ApplicationCatalog;
import nu.fgv.membership.server.config.MembershipProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The access a user gets when an account is created from a payment made elsewhere. Admins keep it. Until they have, it
 * is the lowest role of every application except the ones configured as excluded (Nyuppsättning); the first time it is
 * read the list is written down, so that what admins change stays changed.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class DefaultAccessService {

    static final String SEEDED = "default-access.seeded";

    private final DefaultAccessRepository defaults;
    private final AppSettingRepository settings;
    private final ApplicationCatalog catalog;
    private final AccessService access;
    private final MembershipProperties properties;
    private final List<String> excluded;

    public DefaultAccessService(final DefaultAccessRepository defaults, final AppSettingRepository settings,
                                final ApplicationCatalog catalog, final AccessService access, final MembershipProperties properties,
                                @Value("${membership.default-access.excluded:fgv-revival}") final List<String> excluded) {
        this.defaults = defaults;
        this.settings = settings;
        this.catalog = catalog;
        this.access = access;
        this.properties = properties;
        this.excluded = excluded.stream().map(String::strip).filter(client -> !client.isEmpty()).toList();
    }

    /**
     * @param clientId an application
     * @param name     what it is called
     * @param ladder   its roles, lowest first
     * @param role     the role new users get, or null for none
     */
    public record Entry(String clientId, String name, List<String> ladder, String role) {
    }

    /**
     * @return every administered application with the role new users get on it
     */
    @Transactional
    public List<Entry> all() {
        seed();

        final Map<String, String> chosen = defaults.findAll().stream().collect(Collectors.toMap(DefaultAccess::getClientId, DefaultAccess::getRole));

        return catalog.all().stream().map(entry -> new Entry(entry.client().clientId(), entry.client().displayName(), entry.ladder().roles(),
                chosen.get(entry.client().clientId()))).toList();
    }

    /**
     * @param role a role of the application's ladder, or null for none
     */
    @Transactional
    public Entry set(final String clientId, final String role) {
        final ApplicationCatalog.Entry entry = catalog.find(clientId).orElseThrow(() -> new IllegalArgumentException("Unknown application " + clientId));

        seed();
        if (role == null) {
            defaults.deleteById(clientId);
        } else {
            if (!entry.ladder().contains(role)) {
                throw new IllegalArgumentException(role + " is not a role of " + clientId);
            }
            defaults.findById(clientId).ifPresentOrElse(existing -> existing.setRole(role), () -> defaults.save(new DefaultAccess(clientId, role)));
        }
        return new Entry(clientId, entry.client().displayName(), entry.ladder().roles(), role);
    }

    /**
     * Gives the user the default access, without emails: the user is told in one message that the account exists.
     */
    @Transactional
    public void grant(final String actor, final String userId) {
        for (final Entry entry : all()) {
            if (entry.role() != null) {
                access.setAccess(actor, userId, entry.clientId(), entry.role(), false);
            }
        }
    }

    private void seed() {
        if (settings.existsById(SEEDED)) {
            return;
        }
        catalog.all().stream().filter(entry -> !excluded.contains(entry.client().clientId())).forEach(entry ->
                entry.ladder().roles().stream().findFirst().ifPresent(lowest -> defaults.save(new DefaultAccess(entry.client().clientId(), lowest))));
        settings.save(new AppSetting(SEEDED, "true"));
    }

    List<String> excluded() {
        return excluded;
    }

    MembershipProperties properties() {
        return properties;
    }
}
