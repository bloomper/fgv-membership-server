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

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Swish with the number, the amount and the reference filled in, and locked: a link that opens the app on a phone, and a
 * QR code from Swish's pre-filled QR code API for a computer. Swish never tells FGV that someone paid; the reference
 * is what lets the treasurer match the payment afterwards.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class SwishLinks {

    private final RestClient client;
    private final PaymentProperties properties;

    public SwishLinks(@Qualifier("plainRestClient") final RestClient client, final PaymentProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * @return {@code swish://payment?data=...}, or null when no Swish number is set
     */
    public String link(final int amount, final String reference) {
        if (properties.swishNumber() == null || properties.swishNumber().isBlank()) {
            return null;
        }

        final String json = "{\"version\":1,\"payee\":{\"value\":\"%s\",\"editable\":false},\"amount\":{\"value\":%d,\"editable\":false},\"message\":{\"value\":\"%s\",\"editable\":false}}"
                .formatted(digits(properties.swishNumber()), amount, reference);

        return "swish://payment?data=" + URLEncoder.encode(json, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * @return the QR code as a {@code data:image/png;base64,...} URI, or null when there is no Swish number or Swish
     * cannot be reached; the page then shows the text and the link only
     */
    public String qr(final int amount, final String reference) {
        if (properties.swishNumber() == null || properties.swishNumber().isBlank()) {
            return null;
        }
        try {
            final byte[] png = client.post().uri(properties.qrUrl()).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("format", "png", "size", 300, "transparent", false,
                            "payee", Map.of("value", digits(properties.swishNumber()), "editable", false),
                            "amount", Map.of("value", amount, "editable", false),
                            "message", Map.of("value", reference, "editable", false)))
                    .retrieve().body(byte[].class);

            return png == null ? null : "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private static String digits(final String number) {
        return number.replaceAll("\\D", "");
    }
}
