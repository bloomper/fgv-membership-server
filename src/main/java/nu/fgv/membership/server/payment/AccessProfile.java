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

/**
 * Which default access an account gets when it is created from a payment: the full memberships and the support
 * membership have their own, set by admins.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public enum AccessProfile {
    /** Standard, family and life memberships */
    MEMBER,
    /** Support memberships (stödmedlemskap) */
    SUPPORT
}
