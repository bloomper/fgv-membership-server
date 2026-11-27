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

package nu.fgv.membership.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * @param webUrl       where Medlemskap Web is, without a trailing slash; links in emails point there
 * @param keycloak     the realm users live in, and the service account that administers them
 * @param turnstile    the challenge on the registration page
 * @param registration how registration behaves
 * @param mail         sender of emails
 * @param applications the FGV applications whose access and roles are administered, each with its ladder of roles
 * @param security     who may call the API
 * @author Anders Jacobsson
 * @since 1.0
 */
@ConfigurationProperties("membership")
public record MembershipProperties(String webUrl,
                                   Keycloak keycloak,
                                   Turnstile turnstile,
                                   Registration registration,
                                   Mail mail,
                                   List<Application> applications,
                                   Security security) {

    /**
     * @param url                 Keycloak's address including {@code /auth} when it has that prefix
     * @param realm               the realm
     * @param serviceClientId     {@code fgv-membership-service}
     * @param serviceClientSecret its secret
     * @param pendingGroup        the group of users who wait for approval
     * @param timeout             connect and read timeout
     */
    public record Keycloak(String url, String realm, String serviceClientId, String serviceClientSecret,
                           @DefaultValue("pending-approval") String pendingGroup,
                           @DefaultValue("10s") Duration timeout) {
    }

    /**
     * @param secretKey {@code siteverify}'s secret key; without it registration is refused, except when
     *                  {@code skipWithoutKey} is on, which only the local and test profiles do
     * @param verifyUrl Turnstile's {@code siteverify} endpoint
     */
    public record Turnstile(String secretKey,
                            @DefaultValue("https://challenges.cloudflare.com/turnstile/v0/siteverify") String verifyUrl,
                            @DefaultValue("false") boolean skipWithoutKey,
                            @DefaultValue("10s") Duration timeout) {
    }

    /**
     * @param tokenValidity      how long the link in the verification email works
     * @param unverifiedExpiry   how long an unverified registration is kept before the account is removed
     * @param notifyRecipients   who is told when a new user has verified their email address
     * @param maxPerHourPerIp    registrations from one address per hour
     * @param maxPerHourPerEmail registrations for one email address per hour
     */
    public record Registration(@DefaultValue("48h") Duration tokenValidity,
                               @DefaultValue("7d") Duration unverifiedExpiry,
                               @DefaultValue List<String> notifyRecipients,
                               @DefaultValue("10") int maxPerHourPerIp,
                               @DefaultValue("3") int maxPerHourPerEmail) {
    }

    /**
     * @param from sender address
     */
    public record Mail(@DefaultValue("noreply@localhost") String from) {
    }

    /**
     * An application whose access is administered.
     *
     * @param clientId      the client in Keycloak, e.g. {@code fgv-spexregister}; its name and address are read from the realm
     * @param ladder        the roles people hold, lowest first; granting one grants all below it
     * @param defaultAccess whether every approved user has the lowest role through the realm's default role
     */
    public record Application(String clientId, List<String> ladder, @DefaultValue("false") boolean defaultAccess) {
    }

    /**
     * @param authorizedParties clients whose tokens may call the API
     */
    public record Security(@DefaultValue("fgv-membership") List<String> authorizedParties) {
    }
}
