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

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * How much a payer's name resembles a member's: the share of the name's words, ignoring case and diacritics, that the
 * payer's name has too.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class NameMatcher {

    private NameMatcher() {
    }

    public static double similarity(final String payer, final String name) {
        final Set<String> payerWords = words(payer);
        final Set<String> nameWords = words(name);

        if (payerWords.isEmpty() || nameWords.isEmpty()) {
            return 0;
        }

        final Set<String> shared = new HashSet<>(nameWords);

        shared.retainAll(payerWords);
        return (double) shared.size() / Math.max(nameWords.size(), 1);
    }

    static Set<String> words(final String text) {
        final Set<String> words = new HashSet<>();

        if (text == null) {
            return words;
        }
        for (final String word : Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "").split("[^a-z0-9]+")) {
            if (word.length() >= 2) {
                words.add(word);
            }
        }
        return words;
    }
}
