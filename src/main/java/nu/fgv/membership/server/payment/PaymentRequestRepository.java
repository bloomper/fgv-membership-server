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

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface PaymentRequestRepository extends JpaRepository<PaymentRequest, Long> {

    Optional<PaymentRequest> findById(String id);

    Optional<PaymentRequest> findByReference(String reference);

    boolean existsByReference(String reference);

    List<PaymentRequest> findBySpexareIdOrderByCreatedAtDesc(String spexareId);

    /**
     * The latest request that another application made with its own key.
     */
    Optional<PaymentRequest> findFirstByExternalSourceAndExternalReferenceOrderByCreatedAtDesc(String externalSource, String externalReference);

    /**
     * @return whether a membership is on a request that can still be paid
     */
    @org.springframework.data.jpa.repository.Query("""
            select count(r) > 0 from PaymentRequest r join r.membershipIds m
            where m = :membershipId and r.status in (nu.fgv.membership.server.payment.PaymentStatus.OPEN, nu.fgv.membership.server.payment.PaymentStatus.REPORTED)""")
    boolean isOnOpenRequest(@org.springframework.data.repository.query.Param("membershipId") String membershipId);

    List<PaymentRequest> findByStatusInOrderByCreatedAtAsc(java.util.Collection<PaymentStatus> statuses);

    List<PaymentRequest> findByStatusAndReminderSentAtIsNullAndCreatedAtBefore(PaymentStatus status, Instant before);
}
