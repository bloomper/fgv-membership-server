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

package nu.fgv.membership.server.docs;

import nu.fgv.membership.server.board.ExportController;
import nu.fgv.membership.server.board.ExportService;
import nu.fgv.membership.server.board.PersonalLinkController;
import nu.fgv.membership.server.board.PersonalLinkService;
import nu.fgv.membership.server.config.MembershipProperties;
import nu.fgv.membership.server.external.ExternalLookupService;
import nu.fgv.membership.server.external.ExternalPaymentController;
import nu.fgv.membership.server.external.ExternalPaymentService;
import nu.fgv.membership.server.membership.MembershipService;
import nu.fgv.membership.server.membership.SpexareDeletedController;
import nu.fgv.membership.server.payment.PaymentStatus;
import nu.fgv.membership.server.registration.RegistrationController;
import nu.fgv.membership.server.registration.RegistrationService;
import nu.fgv.membership.server.registration.TurnstileVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessRequest;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessResponse;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.request.RequestDocumentation.queryParameters;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The plain HTTP endpoints (GraphQL is described by its schema), documented from what they answer: the snippets go into the
 * API guide. The controllers are tested alone, with the services mocked; who may call what is in the guide's text and is
 * tested against Keycloak in the integration tests.
 */
@ExtendWith(RestDocumentationExtension.class)
class ApiDocumentationTest {

    private static final String TOKEN = "Bearer <access token>";

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-26T10:00:00Z"), ZoneOffset.UTC);
    private final RegistrationService registration = mock(RegistrationService.class);
    private final TurnstileVerifier turnstile = mock(TurnstileVerifier.class);
    private final PersonalLinkService personal = mock(PersonalLinkService.class);
    private final ExternalPaymentService payments = mock(ExternalPaymentService.class);
    private final ExternalLookupService lookup = mock(ExternalLookupService.class);
    private final ExportService exports = mock(ExportService.class);
    private final MembershipService memberships = mock(MembershipService.class);
    private RestDocumentationContextProvider documentation;

    @BeforeEach
    void setUp(final RestDocumentationContextProvider documentation) {
        this.documentation = documentation;
    }

    private MockMvc mvc(final Object... controllers) {
        final HandlerMethodArgumentResolver caller = new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(final MethodParameter parameter) {
                return parameter.getParameterType().equals(Jwt.class);
            }

            @Override
            public Object resolveArgument(final MethodParameter parameter, final ModelAndViewContainer container, final NativeWebRequest request,
                                          final WebDataBinderFactory binder) {
                return Jwt.withTokenValue("t").header("alg", "none").subject("service-account-id").claim("azp", "fgv-external-payment-service").build();
            }
        };

        return MockMvcBuilders.standaloneSetup(controllers).setCustomArgumentResolvers(caller)
                .apply(documentationConfiguration(documentation).uris().withScheme("https").withHost("membership-api.example.org").withPort(443)).build();
    }

    private RegistrationController registrationController() {
        final MembershipProperties properties = new MembershipProperties("https://membership.example.org", null, null,
                new MembershipProperties.Registration(java.time.Duration.ofHours(48), java.time.Duration.ofDays(7), List.of(), 10, 3), null, List.of(), null);

        return new RegistrationController(registration, turnstile, properties, clock);
    }

    @Test
    void register() throws Exception {
        when(turnstile.verify(anyString(), any())).thenReturn(true);

        mvc(registrationController()).perform(post("/api/registration").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Greta\",\"lastName\":\"Garbo\",\"email\":\"greta@example.org\",\"message\":\"Spexade 1998\",\"language\":\"sv\",\"turnstileToken\":\"<token from the widget>\"}"))
                .andExpect(status().isAccepted())
                .andDo(document("registration-register", preprocessRequest(prettyPrint()), preprocessResponse(prettyPrint()),
                        requestFields(fieldWithPath("firstName").description("First name"), fieldWithPath("lastName").description("Last name"),
                                fieldWithPath("email").description("Email address; the verification link is sent here"),
                                fieldWithPath("message").description("Who the person is and what they want access to (optional, at most 500 characters)").optional(),
                                fieldWithPath("language").description("`sv` or `en`: the language of the emails").optional(),
                                fieldWithPath("turnstileToken").description("The answer of Cloudflare's Turnstile widget")),
                        responseFields(fieldWithPath("status").description("`verification-sent`, whether or not the address already had an account"))));
    }

    @Test
    void verify() throws Exception {
        when(registration.verify("abc")).thenReturn(true);

        mvc(registrationController()).perform(post("/api/registration/verify").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"abc\"}"))
                .andExpect(status().isOk())
                .andDo(document("registration-verify", preprocessRequest(prettyPrint()), preprocessResponse(prettyPrint()),
                        requestFields(fieldWithPath("token").description("The token in the emailed link")),
                        responseFields(fieldWithPath("status").description("`verified`"))));
    }

    private PersonalLinkController personalController() {
        return new PersonalLinkController(personal, clock);
    }

    @Test
    void personalLinkPage() throws Exception {
        when(personal.page("t".repeat(43))).thenReturn(java.util.Optional.of(new PersonalLinkService.Page("FGV27K7QX", 200, 0, PaymentStatus.OPEN, List.of(2027),
                "123 456 78 90", "123-4567", null, "swish://payment?data=...", "data:image/png;base64,...", false)));

        mvc(personalController()).perform(get("/api/personal/{token}", "t".repeat(43))).andExpect(status().isOk())
                .andDo(document("personal-link-page", preprocessResponse(prettyPrint()), pathParameters(parameterWithName("token").description("The token in the personal link")),
                        responseFields(fieldWithPath("reference").description("The payment request's reference, to give as the payment's message"),
                                fieldWithPath("amount").description("The amount asked for, in whole kronor"),
                                fieldWithPath("paid").description("How much has been paid so far"),
                                fieldWithPath("status").description("`OPEN`, `REPORTED`, `MATCHED` or `CANCELLED`"),
                                fieldWithPath("years[]").description("The years the request pays for"),
                                fieldWithPath("swishNumber").description("FGV's Swish number").optional(),
                                fieldWithPath("bankgiro").description("FGV's bankgiro").optional(),
                                fieldWithPath("bankAccount").description("A bank account for direct transfers, if one is used").optional(),
                                fieldWithPath("swishLink").description("Opens Swish with everything filled in; null once paid").optional(),
                                fieldWithPath("qr").description("A QR code of the same as a `data:` URI; null when it cannot be made").optional(),
                                fieldWithPath("optedOut").description("Whether the member has asked not to be reminded again"))));
    }

    @Test
    void personalLinkReport() throws Exception {
        when(personal.report("t".repeat(43), "Min sambo")).thenReturn(true);

        mvc(personalController()).perform(post("/api/personal/{token}/report", "t".repeat(43)).contentType(MediaType.APPLICATION_JSON).content("{\"paidBy\":\"Min sambo\"}"))
                .andExpect(status().isOk())
                .andDo(document("personal-link-report", preprocessRequest(prettyPrint()), preprocessResponse(prettyPrint()),
                        pathParameters(parameterWithName("token").description("The token in the personal link")),
                        requestFields(fieldWithPath("paidBy").description("Who paid, if it was someone else (optional)").optional()),
                        responseFields(fieldWithPath("ok").description("`true`"))));
    }

    @Test
    void personalLinkOptOut() throws Exception {
        when(personal.optOut("t".repeat(43))).thenReturn(true);

        mvc(personalController()).perform(post("/api/personal/{token}/opt-out", "t".repeat(43))).andExpect(status().isOk())
                .andDo(document("personal-link-opt-out", preprocessResponse(prettyPrint()), pathParameters(parameterWithName("token").description("The token in the personal link")),
                        responseFields(fieldWithPath("ok").description("`true`"))));
    }

    @Test
    void externalPayment() throws Exception {
        when(payments.record(anyString(), eq("fgv-external-payment-service"), any())).thenReturn(
                new ExternalPaymentService.Result(ExternalPaymentService.Status.RECORDED, true, "FGV27K7QX", "order-1234"));

        mvc(new ExternalPaymentController(payments, lookup)).perform(post("/api/external/payments").header("Authorization", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"greta@example.org\",\"firstName\":\"Greta\",\"lastName\":\"Garbo\",\"year\":2027,\"amount\":200,\"paidOn\":\"2026-11-25\",\"paymentReference\":\"order-1234\",\"kind\":\"STANDARD\"}"))
                .andExpect(status().isCreated())
                .andDo(document("external-payment", preprocessRequest(prettyPrint()), preprocessResponse(prettyPrint()),
                        requestHeaders(headerWithName("Authorization").description("The service account's access token")),
                        requestFields(fieldWithPath("email").description("Who paid; the identifier (case is ignored)"),
                                fieldWithPath("firstName").description("Used only when the person is new"), fieldWithPath("lastName").description("Used only when the person is new"),
                                fieldWithPath("year").description("The membership year paid for; for a life membership the first year"),
                                fieldWithPath("amount").description("Whole kronor paid"),
                                fieldWithPath("paidOn").description("The day it was paid, `yyyy-mm-dd`; today when missing").optional(),
                                fieldWithPath("paymentReference").description("The application's own id for the payment (an order or transaction id), at most 100 characters. Kept with the payment, shown to the treasurer and in the bookkeeping export; the same id from the same application is the same payment. Optional, but without it nothing protects against being told twice").optional(),
                                fieldWithPath("lifetime").type(JsonFieldType.BOOLEAN).description("`true` for a life membership from `year`; same as `kind` `LIFETIME`").optional(),
                                fieldWithPath("kind").description("`STANDARD` (default), `SUPPORT` (a support membership, which gives a new account other access) or `LIFETIME`. `FAMILY` is refused").optional()),
                        responseFields(fieldWithPath("status").description("`RECORDED`, `DUPLICATE` (told before) or `ALREADY_MEMBER` (a member that year already, so nothing was recorded)"),
                                fieldWithPath("accountCreated").description("Whether an account was created for this payment"),
                                fieldWithPath("reference").description("FGV's reference for the payment, when it was recorded").optional(),
                                fieldWithPath("paymentReference").description("The application's own id, as it was sent").optional())));
    }

    @Test
    void externalPaymentRefused() throws Exception {
        when(payments.record(anyString(), anyString(), any())).thenThrow(new IllegalArgumentException("A valid email address is needed"));

        mvc(new ExternalPaymentController(payments, lookup)).perform(post("/api/external/payments").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"nope\"}"))
                .andExpect(status().isBadRequest())
                .andDo(document("external-payment-invalid", preprocessResponse(prettyPrint()), responseFields(fieldWithPath("error").description("Why the request was refused"))));
    }

    @Test
    void externalLookup() throws Exception {
        when(lookup.lookup("greta@example.org", 2027)).thenReturn(new ExternalLookupService.Result("greta@example.org", 2027, new ExternalLookupService.Account(true, true), true, true,
                new ExternalLookupService.Active(2027, "STANDARD", false, "PAID")));

        mvc(new ExternalPaymentController(payments, lookup)).perform(get("/api/external/lookup").param("email", "greta@example.org").param("year", "2027").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andDo(document("external-lookup", preprocessResponse(prettyPrint()), requestHeaders(headerWithName("Authorization").description("The service account's access token")),
                        queryParameters(parameterWithName("email").description("The address to ask about (case is ignored)"),
                                parameterWithName("year").description("The year to ask about; this year when missing").optional()),
                        responseFields(fieldWithPath("email").description("The address, lower-cased"), fieldWithPath("year").description("The year asked about"),
                                fieldWithPath("account.exists").description("Whether there is an account with this address"),
                                fieldWithPath("account.enabled").description("Whether the account may log in"),
                                fieldWithPath("spexare").description("Whether the address is on a spexare in Spexregister"),
                                fieldWithPath("member").description("Whether that spexare is a member for the year (paid or granted)"),
                                fieldWithPath("active").description("The membership, when there is one; otherwise `null`").optional(),
                                fieldWithPath("active.year").description("The year of the membership; for a life membership its first year").optional(),
                                fieldWithPath("active.kind").description("`STANDARD`, `FAMILY`, `SUPPORT` or `LIFETIME`").optional(),
                                fieldWithPath("active.lifetime").description("Whether it is for life").optional(),
                                fieldWithPath("active.status").description("`PAID` or `WAIVED`").optional())));
    }

    @Test
    void spexareDeleted() throws Exception {
        mvc(new SpexareDeletedController(memberships)).perform(post("/api/sync/spexare-deleted").header("Authorization", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spexareId\":\"0197d6a0-0000-7000-8000-000000000001\"}"))
                .andExpect(status().isNoContent())
                .andDo(document("sync-spexare-deleted", preprocessRequest(prettyPrint()), requestHeaders(headerWithName("Authorization").description("Spexregister's service token")),
                        requestFields(fieldWithPath("spexareId").description("The deleted spexare's id"))));
    }

    @Test
    void exportMembers() throws Exception {
        when(exports.memberList(2027)).thenReturn(new byte[]{'P', 'K'});

        mvc(new ExportController(exports)).perform(get("/api/exports/members.xlsx").param("year", "2027").header("Authorization", TOKEN)).andExpect(status().isOk())
                .andDo(document("export-members", requestHeaders(headerWithName("Authorization").description("An access token holding `BOARD`")),
                        queryParameters(parameterWithName("year").description("The year"))));
    }

    @Test
    void exportPayments() throws Exception {
        when(exports.payments(2027)).thenReturn(new byte[]{'P', 'K'});

        mvc(new ExportController(exports)).perform(get("/api/exports/payments.xlsx").param("year", "2027").header("Authorization", TOKEN)).andExpect(status().isOk())
                .andDo(document("export-payments", requestHeaders(headerWithName("Authorization").description("An access token holding `TREASURER`")),
                        queryParameters(parameterWithName("year").description("The year"))));
    }
}
