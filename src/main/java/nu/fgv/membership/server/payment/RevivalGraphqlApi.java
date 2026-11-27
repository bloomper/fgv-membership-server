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

package nu.fgv.membership.server.payment;

import nu.fgv.membership.server.board.AuditService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

import java.util.List;

/**
 * What Nyuppsättning may do here, through the {@code REVIVAL} role (spec 5.7): see the year's fees, ask for one payment
 * request that covers the participants it pays the FGV fee for, and ask whether its request has been paid. It cannot see
 * members or other payments.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
@PreAuthorize("hasRole('fgv-membership_REVIVAL')")
public class RevivalGraphqlApi {

    private final FeeService fees;
    private final PaymentService payments;
    private final AuditService audit;

    public RevivalGraphqlApi(final FeeService fees, final PaymentService payments, final AuditService audit) {
        this.fees = fees;
        this.payments = payments;
        this.audit = audit;
    }

    public record RevivalResult(String requestId, String reference, int amount, int paid, PaymentStatus status,
                                int included, boolean existing,
                                List<String> alreadyMembers, List<String> alreadyRequested, Payee payee) {
    }

    public record Payee(String swishNumber, String bankgiro, String bankAccount, String name) {
    }

    @QueryMapping
    public List<PaymentGraphqlApi.FeeLevelView> revivalFeeLevels(@Argument final Integer year) {
        return fees.available(year).stream().filter(level -> level.getKind() == FeeKind.STANDARD)
                .map(level -> new PaymentGraphqlApi.FeeLevelView(level.getId(), level.getYear(), FeeKind.STANDARD, false, level.getNameSv(), level.getNameEn(), level.getAmount(), false)).toList();
    }

    @MutationMapping
    public RevivalResult createRevivalPaymentRequest(@AuthenticationPrincipal final Jwt caller, @Argument final String payerLabel,
                                                     @Argument final List<String> spexareIds, @Argument final String feeLevelId,
                                                     @Argument final String key, @Argument final String actingPerson) {
        final PaymentService.RevivalRequest result = payments.requestForRevival(caller.getSubject(), caller.getClaimAsString("azp"), key, payerLabel, spexareIds, feeLevelId);

        if (!result.existing()) {
            audit.log(caller.getSubject(), "REVIVAL_PAYMENT_REQUEST", result.request().reference(), "%s, %d people%s".formatted(payerLabel, result.included(),
                    actingPerson == null || actingPerson.isBlank() ? "" : ", on behalf of " + actingPerson.strip()));
        }
        return view(result);
    }

    @QueryMapping
    public RevivalResult revivalPaymentRequest(@AuthenticationPrincipal final Jwt caller, @Argument final String id) {
        return view(payments.revivalRequest(caller.getClaimAsString("azp"), id));
    }

    private static RevivalResult view(final PaymentService.RevivalRequest result) {
        final PaymentView request = result.request();

        return new RevivalResult(request.id(), request.reference(), request.amount(), request.paid(), request.status(), result.included(), result.existing(),
                result.alreadyMembers(), result.alreadyRequested(),
                new Payee(request.payee().swishNumber(), request.payee().bankgiro(), request.payee().bankAccount(), request.payee().name()));
    }
}
