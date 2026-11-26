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

import java.security.SecureRandom;
import java.time.Year;
import java.util.Locale;

/**
 * The reference of a payment request: {@code FGV}, the year's last two digits and four characters from an alphabet
 * without look-alikes (no {@code 0}, {@code O}, {@code 1}, {@code I}), the last of which is a check character, e.g.
 * {@code FGV27K7QX}. It is short enough to type, and a typo is caught by the check character.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class PaymentReference {

    static final String PREFIX = "FGV";
    static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private PaymentReference() {
    }

    public static String generate(final Year year) {
        final String body = PREFIX + "%02d".formatted(year.getValue() % 100)
                + ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length()))
                + ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length()))
                + ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length()));

        return body + check(body);
    }

    /**
     * @return whether the text is a reference with a correct check character; case, spaces and hyphens are ignored
     */
    public static boolean isValid(final String text) {
        final String reference = normalize(text);

        return reference != null && reference.length() == 9 && reference.charAt(8) == check(reference.substring(0, 8));
    }

    /**
     * @return the reference as it is stored, or null when the text is not shaped like one
     */
    public static String normalize(final String text) {
        if (text == null) {
            return null;
        }

        final String reference = text.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");

        return reference.matches("FGV\\d{2}[" + ALPHABET + "]{4}") ? reference : null;
    }

    /**
     * A weighted sum with odd weights, which are invertible modulo the alphabet's 32 characters: changing any one
     * character always changes the check character.
     */
    static char check(final String body) {
        int sum = 0;

        for (int i = 0; i < body.length(); i++) {
            final char c = body.charAt(i);
            final int value = Character.isDigit(c) && ALPHABET.indexOf(c) < 0 ? c - '0' : ALPHABET.indexOf(c) + 1;

            sum += value * (2 * i + 1);
        }
        return ALPHABET.charAt(sum % ALPHABET.length());
    }
}
