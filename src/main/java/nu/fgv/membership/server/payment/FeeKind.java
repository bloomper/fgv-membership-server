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
 * The kinds of membership FGV sells. Admins set a price for each, for each year.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public enum FeeKind {
    /**
     * One person, for a year
     */
    STANDARD,
    /**
     * One family, for a year: one pays and every family member is a member
     */
    FAMILY,
    /**
     * One person, for life from the year it is paid
     */
    LIFETIME,
    /**
     * A support membership (stödmedlemskap): a person, for a year, at another price and with other access
     */
    SUPPORT;

    /**
     * @return which default access an account created by paying this kind of fee gets
     */
    public AccessProfile profile() {
        return this == SUPPORT ? AccessProfile.SUPPORT : AccessProfile.MEMBER;
    }
}
