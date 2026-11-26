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

import nu.fgv.membership.server.keycloak.KeycloakAdmin;
import nu.fgv.membership.server.payment.PaymentMails;
import nu.fgv.membership.server.payment.PaymentService;
import nu.fgv.membership.server.payment.PaymentView;
import nu.fgv.membership.server.payment.Spexare;
import nu.fgv.membership.server.payment.SpexregisterClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A payment made elsewhere (the WordPress shop) tells which email address paid for which year. The spexare is found in
 * Spexregister by that address, or created there; the user is found in Keycloak by it, or created with the default
 * access and told, with a link to choose a password. Then the payment is recorded like any other.
 *
 * <p>The outside steps are all "find or create", so a retry after a failure picks up where it stopped; the shop's
 * reference makes telling the same payment twice harmless.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class ExternalPaymentService {

    private static final Logger log = LoggerFactory.getLogger(ExternalPaymentService.class);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int SET_PASSWORD_LIFESPAN = 7 * 24 * 3600;
    private static final ZoneId ZONE = ZoneId.of("Europe/Stockholm");

    public enum Status { RECORDED, DUPLICATE, ALREADY_MEMBER }

    /**
     * @param reference      the payment's own reference here (the one a treasurer sees), when it was recorded
     * @param accountCreated whether an account was created for this payment
     */
    public record Result(Status status, boolean accountCreated, String reference) {
    }

    private final ExternalPaymentRepository externals;
    private final SpexregisterClient spexregister;
    private final KeycloakAdmin keycloak;
    private final DefaultAccessService defaults;
    private final PaymentService payments;
    private final PaymentMails mails;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public ExternalPaymentService(final ExternalPaymentRepository externals, final SpexregisterClient spexregister,
                                  final KeycloakAdmin keycloak, final DefaultAccessService defaults, final PaymentService payments,
                                  final PaymentMails mails, final PlatformTransactionManager transactionManager, final Clock clock) {
        this.externals = externals;
        this.spexregister = spexregister;
        this.keycloak = keycloak;
        this.defaults = defaults;
        this.payments = payments;
        this.mails = mails;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @param actor the shop's service account, by Keycloak subject
     * @throws IllegalArgumentException when the request is not valid
     */
    public Result record(final String actor, final ExternalPaymentRequest request) {
        final String email = validate(request);
        final Optional<ExternalPayment> earlier = externals.findByExternalRef(request.reference().strip());

        if (earlier.isPresent()) {
            return new Result(Status.DUPLICATE, earlier.get().isAccountCreated(), null);
        }

        final Spexare spexare = spexregister.byEmail(email)
                .orElseGet(() -> spexregister.create(request.firstName().strip(), request.lastName().strip(), email));
        final boolean accountCreated = ensureAccount(actor, email, request);

        return transaction.execute(_ -> {
            final boolean lifetime = Boolean.TRUE.equals(request.lifetime());
            final LocalDate paidOn = request.paidOn() == null ? LocalDate.now(clock.withZone(ZONE)) : request.paidOn();
            final Optional<PaymentView> view = payments.recordExternal(actor, spexare.id(), request.year(), lifetime, request.amount(), paidOn,
                    "Online: " + request.reference().strip());
            final Status status = view.isPresent() ? Status.RECORDED : Status.ALREADY_MEMBER;

            externals.save(new ExternalPayment(actor, request.reference().strip(), view.map(PaymentView::id).orElse(null), status.name(),
                    accountCreated, clock.instant()));
            if (!view.isPresent()) {
                log.warn("{} paid online ({}) but is already a member {}: nothing recorded, a refund may be due", spexare.id(),
                        request.reference(), request.year());
            }
            return new Result(status, accountCreated, view.map(PaymentView::reference).orElse(null));
        });
    }

    private boolean ensureAccount(final String actor, final String email, final ExternalPaymentRequest request) {
        if (keycloak.findUserByEmail(email).isPresent()) {
            return false;
        }

        final String id = keycloak.createUser(email, request.firstName().strip(), request.lastName().strip(), "sv");

        try {
            // The link below goes to this address, so choosing a password also proves it is theirs
            keycloak.setEmailVerified(id, true);
            defaults.grant(actor, id);
            keycloak.sendSetPasswordEmail(id, "fgv-membership", null, SET_PASSWORD_LIFESPAN);
            mails.accountCreated(id, email);
        } catch (final RuntimeException e) {
            log.error("Could not finish the account for a payment, removing user {}", id, e);
            keycloak.deleteUser(id);
            throw e;
        }
        return true;
    }

    private String validate(final ExternalPaymentRequest request) {
        if (request.email() == null || !EMAIL.matcher(request.email().strip()).matches()) {
            throw new IllegalArgumentException("A valid email address is needed");
        }
        if (request.reference() == null || request.reference().isBlank() || request.reference().length() > 100) {
            throw new IllegalArgumentException("The shop's order reference is needed");
        }
        if (request.firstName() == null || request.firstName().isBlank() || request.lastName() == null || request.lastName().isBlank()) {
            throw new IllegalArgumentException("First and last name are needed");
        }

        final int thisYear = LocalDate.now(clock.withZone(ZONE)).getYear();

        if (request.year() == null || request.year() < 1948 || request.year() > thisYear + 1) {
            throw new IllegalArgumentException("The year is out of range");
        }
        if (request.amount() == null || request.amount() < 0) {
            throw new IllegalArgumentException("The amount is needed");
        }
        return request.email().strip().toLowerCase(Locale.ROOT);
    }
}
