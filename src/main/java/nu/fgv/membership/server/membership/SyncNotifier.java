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

package nu.fgv.membership.server.membership;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Tells the consumers of the feed (Spexregister's {@code /api/sync/notify}) that there is something new, after the
 * change has committed. A failed call is only logged: the consumer pulls the feed every few minutes anyway.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class SyncNotifier {

    private static final Logger log = LoggerFactory.getLogger(SyncNotifier.class);

    private final RestClient client;
    private final List<String> urls;

    public SyncNotifier(@Qualifier("keycloakRestClient") final RestClient client,
                        @Value("${membership.sync.notify-urls:}") final List<String> urls) {
        this.client = client;
        this.urls = urls.stream().filter(url -> !url.isBlank()).toList();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChange(final MembershipsChanged event) {
        for (final String url : urls) {
            try {
                client.post().uri(url).retrieve().toBodilessEntity();
            } catch (RuntimeException e) {
                log.warn("Could not notify {}: {}", url, e.getMessage());
            }
        }
    }
}
