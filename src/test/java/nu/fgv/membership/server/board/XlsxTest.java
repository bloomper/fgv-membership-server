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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class XlsxTest {

    private static Map<String, String> unzip(final byte[] bytes) throws Exception {
        final Map<String, String> files = new HashMap<>();

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                files.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    @Test
    void aWorkbookHasThePartsExcelNeeds() throws Exception {
        final var files = unzip(Xlsx.write("Medlemmar", List.of("Namn", "År"), List.of(Arrays.asList("Åke & <Co>", 2026))));

        assertThat(files).containsKeys("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml");
        assertThat(files.get("xl/workbook.xml")).contains("name=\"Medlemmar\"");
    }

    @Test
    void textIsEscapedNumbersAreNumbersAndEmptyCellsAreSkipped() throws Exception {
        final String sheet = unzip(Xlsx.write("S", List.of("A", "B", "C"), List.of(Arrays.asList("Åke & <Co>", 2026, null)))).get("xl/worksheets/sheet1.xml");

        assertThat(sheet).contains("Åke &amp; &lt;Co&gt;").contains("<c r=\"B2\"><v>2026</v></c>").doesNotContain("C2");
        assertThat(sheet).contains("<c r=\"A1\" t=\"inlineStr\" s=\"1\">");
    }

    @Test
    void columnsAreNamedLikeExcelDoes() {
        assertThat(Xlsx.column(0)).isEqualTo("A");
        assertThat(Xlsx.column(25)).isEqualTo("Z");
        assertThat(Xlsx.column(26)).isEqualTo("AA");
        assertThat(Xlsx.column(27)).isEqualTo("AB");
    }

    @Test
    void controlCharactersThatBreakTheXmlAreDropped() throws Exception {
        final String sheet = unzip(Xlsx.write("S", List.of("A"), List.of(List.of("a\u0001b")))).get("xl/worksheets/sheet1.xml");

        assertThat(sheet).contains("<t xml:space=\"preserve\">ab</t>");
    }
}
