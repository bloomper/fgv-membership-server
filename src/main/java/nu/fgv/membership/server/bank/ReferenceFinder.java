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

package nu.fgv.membership.server.bank;

import nu.fgv.membership.server.payment.PaymentReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds payment references in a transaction's text. Spaces and hyphens are ignored, so a reference the payer's app or
 * a person broke up still counts.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class ReferenceFinder {

    private static final Pattern SHAPE = Pattern.compile("FGV\\d{2}[" + PaymentReference.ALPHABET + "]{4}");

    private ReferenceFinder() {
    }

    /**
     * @return the references in the text that have a correct check character
     */
    public static List<String> valid(final String text) {
        return shaped(text).stream().filter(PaymentReference::isValid).toList();
    }

    /**
     * @return what looks like a reference but has a wrong check character
     */
    public static List<String> invalid(final String text) {
        return shaped(text).stream().filter(reference -> !PaymentReference.isValid(reference)).toList();
    }

    /**
     * @return the references that differ from this one in a single character
     */
    public static List<String> nearest(final String reference, final List<String> candidates) {
        return candidates.stream().filter(candidate -> candidate.length() == reference.length() && distance(candidate, reference) == 1).toList();
    }

    private static List<String> shaped(final String text) {
        final List<String> found = new ArrayList<>();

        if (text == null) {
            return found;
        }

        final Matcher matcher = SHAPE.matcher(text.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", ""));

        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    private static int distance(final String a, final String b) {
        int differences = 0;

        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) {
                differences++;
            }
        }
        return differences;
    }
}
