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

package nu.fgv.membership.server.board;

import nu.fgv.membership.server.payment.PaymentProperties;
import nu.fgv.membership.server.payment.PaymentRequest;
import nu.fgv.membership.server.payment.PaymentRequestRepository;
import nu.fgv.membership.server.payment.PaymentService;
import nu.fgv.membership.server.payment.PaymentStatus;
import nu.fgv.membership.server.payment.SwishLinks;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Personal links for people who pay without logging in: a long random token that opens one payment request and nothing
 * else, valid until the end of the year. Only the token's hash is stored.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class PersonalLinkService {

    private static final ZoneId ZONE = ZoneId.of("Europe/Stockholm");
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * What the page of a personal link shows, and nothing more.
     */
    public record Page(String reference, int amount, int paid, PaymentStatus status, List<Integer> years, String swishNumber, String bankAccount,
                       String swishLink, String qr, boolean optedOut) {
    }

    private final PersonalLinkRepository links;
    private final PaymentRequestRepository requests;
    private final PaymentService payments;
    private final PaymentProperties properties;
    private final SwishLinks swish;
    private final OptOutRepository optOuts;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public PersonalLinkService(final PersonalLinkRepository links, final PaymentRequestRepository requests, final PaymentService payments,
                               final PaymentProperties properties, final SwishLinks swish, final OptOutRepository optOuts, final Clock clock) {
        this.links = links;
        this.requests = requests;
        this.payments = payments;
        this.properties = properties;
        this.swish = swish;
        this.optOuts = optOuts;
        this.clock = clock;
    }

    /**
     * @return the token to put in the link; it is not stored and cannot be found again
     */
    @Transactional
    public String create(final PaymentRequest request) {
        final byte[] bytes = new byte[32];

        RANDOM.nextBytes(bytes);

        final String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        final Instant endOfYear = Year.now(clock.withZone(ZONE)).plusYears(1).atDay(1).atStartOfDay(ZONE).toInstant();

        links.save(new PersonalLink(request.getPk(), hash(token), endOfYear, clock.instant()));
        return token;
    }

    /**
     * @return the page for a token that is valid and not expired
     */
    @Transactional(readOnly = true)
    public Optional<Page> page(final String token) {
        return resolve(token).map(request -> {
            final boolean open = request.isPayable();

            return new Page(request.getReference(), request.getAmount(), request.getAmount() - payments.remaining(request), request.getStatus(),
                    payments.yearsOf(request), properties.swishNumber(), properties.bankAccount(),
                    open ? swish.link(payments.remaining(request), request.getReference()) : null,
                    open ? swish.qr(payments.remaining(request), request.getReference()) : null, optOuts.existsById(request.getSpexareId()));
        });
    }

    /**
     * The payer says they paid, without logging in.
     *
     * @return whether the token was valid
     */
    @Transactional
    public boolean report(final String token, final String paidBy) {
        final Optional<PaymentRequest> request = resolve(token);

        if (request.isEmpty() || !request.get().isPayable()) {
            return request.isPresent();
        }
        payments.reportBy(request.get().getId(), paidBy);
        return true;
    }

    /**
     * <em>Don't remind me again</em>.
     *
     * @return whether the token was valid
     */
    @Transactional
    public boolean optOut(final String token) {
        final Optional<PaymentRequest> request = resolve(token);

        request.ifPresent(r -> {
            if (!optOuts.existsById(r.getSpexareId())) {
                optOuts.save(new OptOut(r.getSpexareId(), clock.instant()));
            }
        });
        return request.isPresent();
    }

    private Optional<PaymentRequest> resolve(final String token) {
        if (token == null || token.length() < 40 || token.length() > 100) {
            return Optional.empty();
        }
        return links.findByTokenHash(hash(token)).filter(link -> link.getExpiresAt().isAfter(clock.instant()))
                .flatMap(link -> requests.findById(link.getRequestPk()));
    }

    static String hash(final String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
