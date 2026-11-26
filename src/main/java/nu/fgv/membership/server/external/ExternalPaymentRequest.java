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

import java.time.LocalDate;

/**
 * What the shop tells: who paid for which year, with the shop's own reference for the order.
 *
 * @param reference the shop's order reference; telling the same one twice changes nothing
 * @param amount    whole kronor paid
 * @param paidOn    the day it was paid; today when missing
 * @param lifetime  a life membership from {@code year}
 * @param kind      the kind of membership: {@code STANDARD} when missing; {@code SUPPORT} for a support membership, which gives a
 *                  new account other access (and {@code LIFETIME} is the same as {@code lifetime}); {@code FAMILY} is not
 *                  sold through the shop
 * @author Anders Jacobsson
 * @since 1.0
 */
public record ExternalPaymentRequest(String email, String firstName, String lastName, Integer year, Integer amount, LocalDate paidOn,
                                     String reference, Boolean lifetime, nu.fgv.membership.server.payment.FeeKind kind) {
}
