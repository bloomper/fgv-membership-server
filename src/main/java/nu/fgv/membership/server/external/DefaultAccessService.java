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
import nu.fgv.membership.server.payment.AccessProfile;

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
    static final String SEEDED_SUPPORT = "default-access.seeded.support";

    private final DefaultAccessRepository defaults;
    private final AppSettingRepository settings;
    private final ApplicationCatalog catalog;
    private final AccessService access;
    private final MembershipProperties properties;
    private final List<String> excluded;
    private final List<String> supportApplications;

    public DefaultAccessService(final DefaultAccessRepository defaults, final AppSettingRepository settings,
                                final ApplicationCatalog catalog, final AccessService access, final MembershipProperties properties,
                                @Value("${membership.default-access.excluded:fgv-revival}") final List<String> excluded,
                                @Value("${membership.default-access.support-applications:fgv-spexipedia,fgv-spexflix,fgv-spexgram,fgv-spexify}") final List<String> supportApplications) {
        this.defaults = defaults;
        this.settings = settings;
        this.catalog = catalog;
        this.access = access;
        this.properties = properties;
        this.excluded = clean(excluded);
        this.supportApplications = clean(supportApplications);
    }

    private static List<String> clean(final List<String> clients) {
        return clients.stream().map(String::strip).filter(client -> !client.isEmpty()).toList();
    }

    /**
     * @param clientId an application
     * @param name     what it is called
     * @param ladder   its roles, lowest first
     * @param role     the role new users get, or null for none
     */
    public record Entry(String clientId, String name, List<String> ladder, String role) {
    }

    public List<Entry> all() {
        return all(AccessProfile.MEMBER);
    }

    /**
     * @return every administered application with the role new users get on it, for the kind of membership
     */
    @Transactional
    public List<Entry> all(final AccessProfile profile) {
        seed(profile);

        final Map<String, String> chosen = defaults.findByProfile(profile).stream().collect(Collectors.toMap(DefaultAccess::getClientId, DefaultAccess::getRole));

        return catalog.all().stream().map(entry -> new Entry(entry.client().clientId(), entry.client().displayName(), entry.ladder().roles(),
                chosen.get(entry.client().clientId()))).toList();
    }

    public Entry set(final String clientId, final String role) {
        return set(AccessProfile.MEMBER, clientId, role);
    }

    /**
     * @param role a role of the application's ladder, or null for none
     */
    @Transactional
    public Entry set(final AccessProfile profile, final String clientId, final String role) {
        final ApplicationCatalog.Entry entry = catalog.find(clientId).orElseThrow(() -> new IllegalArgumentException("Unknown application " + clientId));

        seed(profile);
        if (role == null) {
            defaults.deleteById(new DefaultAccess.Key(profile, clientId));
        } else {
            if (!entry.ladder().contains(role)) {
                throw new IllegalArgumentException(role + " is not a role of " + clientId);
            }
            defaults.findById(new DefaultAccess.Key(profile, clientId)).ifPresentOrElse(existing -> existing.setRole(role),
                    () -> defaults.save(new DefaultAccess(profile, clientId, role)));
        }
        return new Entry(clientId, entry.client().displayName(), entry.ladder().roles(), role);
    }

    public void grant(final String actor, final String userId) {
        grant(actor, userId, AccessProfile.MEMBER);
    }

    /**
     * Gives the user the default access of the kind of membership, without emails: the user is told in one message that
     * the account exists.
     */
    @Transactional
    public void grant(final String actor, final String userId, final AccessProfile profile) {
        for (final Entry entry : all(profile)) {
            if (entry.role() != null) {
                access.setAccess(actor, userId, entry.clientId(), entry.role(), false);
            }
        }
    }

    /**
     * Written down the first time it is read, so what admins change stays changed. The full memberships start with the
     * lowest role of every application except those excluded (Nyuppsättning); a support membership starts with the lowest
     * role of Spexipedia, Spexflix, Spexgram and Spexify.
     */
    private void seed(final AccessProfile profile) {
        final String flag = profile == AccessProfile.SUPPORT ? SEEDED_SUPPORT : SEEDED;

        if (settings.existsById(flag)) {
            return;
        }
        catalog.all().stream()
                .filter(entry -> profile == AccessProfile.SUPPORT ? supportApplications.contains(entry.client().clientId()) : !excluded.contains(entry.client().clientId()))
                .forEach(entry -> entry.ladder().roles().stream().findFirst()
                        .ifPresent(lowest -> defaults.save(new DefaultAccess(profile, entry.client().clientId(), lowest))));
        settings.save(new AppSetting(flag, "true"));
    }

    List<String> excluded() {
        return excluded;
    }

    MembershipProperties properties() {
        return properties;
    }
}
