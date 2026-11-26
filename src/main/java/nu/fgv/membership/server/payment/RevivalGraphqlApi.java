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

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

import java.util.List;

/**
 * What Nyuppsättning may do here, through the {@code REVIVAL} role (spec 5.7): see the year's fees and ask for one
 * payment request that covers the participants it pays the FGV fee for. It cannot see members or payments.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
@PreAuthorize("hasRole('fgv-membership_REVIVAL')")
public class RevivalGraphqlApi {

    private final FeeService fees;
    private final PaymentService payments;

    public RevivalGraphqlApi(final FeeService fees, final PaymentService payments) {
        this.fees = fees;
        this.payments = payments;
    }

    public record RevivalResult(String requestId, String reference, int amount, int included, List<String> alreadyMembers, Payee payee) {
    }

    public record Payee(String swishNumber, String bankAccount, String name) {
    }

    @QueryMapping
    public List<PaymentGraphqlApi.FeeLevelView> revivalFeeLevels(@Argument final Integer year) {
        return fees.available(year).stream().filter(level -> level.getKind() == FeeKind.STANDARD)
                .map(level -> new PaymentGraphqlApi.FeeLevelView(level.getId(), level.getYear(), FeeKind.STANDARD, false, level.getNameSv(), level.getNameEn(), level.getAmount(), false)).toList();
    }

    @MutationMapping
    public RevivalResult createRevivalPaymentRequest(@AuthenticationPrincipal final Jwt caller, @Argument final String payerLabel,
                                                     @Argument final List<String> spexareIds, @Argument final String feeLevelId) {
        final PaymentService.RevivalRequest result = payments.requestForRevival(caller.getSubject(), payerLabel, spexareIds, feeLevelId);
        final PaymentView request = result.request();

        return new RevivalResult(request.id(), request.reference(), request.amount(), result.included(), result.alreadyMembers(),
                new Payee(request.payee().swishNumber(), request.payee().bankAccount(), request.payee().name()));
    }
}
