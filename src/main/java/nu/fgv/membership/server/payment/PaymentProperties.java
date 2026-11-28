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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Where members pay ({@code membership.payment.*}). Settings that admins keep in the application come with
 * administration (spec 5.8); until then they are configuration.
 *
 * @param swishNumber    FGV's Swish number
 * @param bankAccount    FGV's bank account for transfers, shown as text
 * @param payeeName      the name of the payee, for the payment page
 * @param qrUrl          Swish's pre-filled QR code API
 * @param reminderAfter  how long an open request waits before the payer is reminded once
 * @param membershipType the membership type fees and requests are for
 * @author Anders Jacobsson
 * @since 1.0
 */
@ConfigurationProperties("membership.payment")
public record PaymentProperties(String swishNumber, String bankAccount, @DefaultValue("FGV") String payeeName,
                                @DefaultValue("https://mpc.getswish.net/qrg-swish/api/v1/prefilled") String qrUrl,
                                @DefaultValue("3d") Duration reminderAfter,
                                @DefaultValue("FGV") String membershipType) {
}
