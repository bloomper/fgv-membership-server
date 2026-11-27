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

package nu.fgv.membership.server.board;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/exports/members.xlsx?year=} for the board and {@code GET /api/exports/payments.xlsx?year=} for the
 * treasurer.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@RestController
public class ExportController {

    private final ExportService exports;

    public ExportController(final ExportService exports) {
        this.exports = exports;
    }

    @GetMapping("/api/exports/members.xlsx")
    @PreAuthorize("hasRole('fgv-membership_BOARD')")
    public ResponseEntity<byte[]> members(@RequestParam final int year) {
        return download(exports.memberList(year), "medlemmar-" + year + ".xlsx");
    }

    @GetMapping("/api/exports/payments.xlsx")
    @PreAuthorize("hasRole('fgv-membership_TREASURER')")
    public ResponseEntity<byte[]> payments(@RequestParam final int year) {
        return download(exports.payments(year), "betalningar-" + year + ".xlsx");
    }

    private static ResponseEntity<byte[]> download(final byte[] content, final String name) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(Xlsx.CONTENT_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString()).body(content);
    }
}
