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

import nu.fgv.membership.server.payment.PaymentHow;
import nu.fgv.membership.server.payment.PaymentView;
import nu.fgv.membership.server.payment.Spexare;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

import java.time.LocalDate;
import java.util.List;

/**
 * The board's lists and what it does with members.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
@PreAuthorize("hasRole('fgv-membership_BOARD')")
public class BoardGraphqlApi {

    private final MemberService members;
    private final RenewalService renewals;
    private final BoardService board;

    public BoardGraphqlApi(final MemberService members, final RenewalService renewals, final BoardService board) {
        this.members = members;
        this.renewals = renewals;
        this.board = board;
    }

    @QueryMapping
    public List<MemberService.Member> members(@Argument final int year, @Argument final String status, @Argument final String how,
                                              @Argument final Boolean wasMemberLastYear) {
        return members.members(year, status, how, wasMemberLastYear);
    }

    @QueryMapping
    public List<MemberService.Count> memberTrend() {
        return members.trend();
    }

    @QueryMapping
    public RenewalService.Preview renewalPreview(@Argument final int year, @Argument final String feeLevelId) {
        return renewals.preview(year, feeLevelId);
    }

    @QueryMapping
    public BoardService.Detail memberDetail(@Argument final String spexareId) {
        return board.detail(spexareId);
    }

    @QueryMapping
    public List<Spexare> spexareSearch(@Argument final String q) {
        return board.search(q);
    }

    @MutationMapping
    public RenewalService.Result sendRenewals(@AuthenticationPrincipal final Jwt caller, @Argument final int year, @Argument final String feeLevelId,
                                              @Argument final @Nullable String subject, @Argument final @Nullable String message) {
        return renewals.send(caller.getSubject(), year, feeLevelId, subject, message);
    }

    @MutationMapping
    public BoardService.MemberItem grantWaiver(@AuthenticationPrincipal final Jwt caller, @Argument final String spexareId, @Argument final int year,
                                               @Argument final boolean lifetime, @Argument final String note) {
        return board.waive(caller.getSubject(), spexareId, year, lifetime, note);
    }

    @MutationMapping
    public PaymentView recordPaymentWithoutRequest(@AuthenticationPrincipal final Jwt caller, @Argument final String spexareId,
                                                   @Argument final List<String> feeLevelIds, @Argument final String paidOn, @Argument final int amount,
                                                   @Argument final PaymentHow how, @Argument final String note) {
        return board.recordWithoutRequest(caller.getSubject(), spexareId, feeLevelIds, LocalDate.parse(paidOn), amount, how, note);
    }

    @MutationMapping
    public boolean sendPaymentLink(@AuthenticationPrincipal final Jwt caller, @Argument final String spexareId, @Argument final List<String> feeLevelIds) {
        return board.sendPaymentLink(caller.getSubject(), spexareId, feeLevelIds);
    }
}
