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

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SwishLinksTest {

    private static SwishLinks links(final String number) {
        return new SwishLinks(RestClient.create(), new PaymentProperties(number, "", "FGV", "http://localhost:1/qr", Duration.ofDays(3), "FGV"));
    }

    @Test
    void theLinkCarriesTheNumberTheAmountAndTheReferenceLocked() {
        final String link = links("123 456 78 90").link(200, "FGV27K7QX");
        final String data = URLDecoder.decode(link.substring("swish://payment?data=".length()), StandardCharsets.UTF_8);

        assertThat(link).startsWith("swish://payment?data=");
        assertThat(data).contains("\"value\":\"1234567890\"", "\"value\":200", "\"value\":\"FGV27K7QX\"");
        assertThat(data).contains("\"editable\":false");
    }

    @Test
    void withoutANumberThereIsNoLinkAndNoQrCode() {
        assertThat(links("").link(200, "FGV27K7QX")).isNull();
        assertThat(links(null).qr(200, "FGV27K7QX")).isNull();
    }

    @Test
    void aQrCodeThatCannotBeFetchedIsLeftOut() {
        assertThat(links("1234567890").qr(200, "FGV27K7QX")).isNull();
    }
}
