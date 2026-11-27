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

import nu.fgv.membership.server.external.AppSetting;
import nu.fgv.membership.server.external.AppSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentSettingsTest {

    private final Map<String, AppSetting> rows = new HashMap<>();
    private final AppSettingRepository repository = mock(AppSettingRepository.class);
    private PaymentSettings settings;

    @BeforeEach
    void setUp() {
        when(repository.findById(anyString())).thenAnswer(call -> Optional.ofNullable(rows.get(call.<String>getArgument(0))));
        when(repository.save(any(AppSetting.class))).thenAnswer(call -> {
            final AppSetting row = call.getArgument(0);

            rows.put(row.getName(), row);
            return row;
        });
        org.mockito.Mockito.doAnswer(call -> rows.remove(call.<AppSetting>getArgument(0).getName())).when(repository).delete(any(AppSetting.class));
        settings = new PaymentSettings(new PaymentProperties("070 111 22 33", "1234-5", "FGV", "http://qr", Duration.ofDays(3), "FGV"), repository,
                List.of("a@fgv.nu"), "123-4567");
    }

    @Test
    void withNothingSetTheConfigurationIsUsed() {
        assertThat(settings.swishNumber()).isEqualTo("070 111 22 33");
        assertThat(settings.bankgiro()).isEqualTo("123-4567");
        assertThat(settings.bankAccount()).isEqualTo("1234-5");
        assertThat(settings.reminderAfter()).isEqualTo(Duration.ofDays(3));
        assertThat(settings.notifyRecipients()).containsExactly("a@fgv.nu");
    }

    @Test
    void whatAnAdminSavesWinsAndAnEmptyValueGoesBackToTheConfiguration() {
        settings.save("070 999 99 99", "555-5555", "", "", 5, "x@fgv.nu, y@fgv.nu\nz@fgv.nu");

        assertThat(settings.swishNumber()).isEqualTo("070 999 99 99");
        assertThat(settings.bankgiro()).isEqualTo("555-5555");
        assertThat(settings.bankAccount()).isEqualTo("1234-5");
        assertThat(settings.reminderAfter()).isEqualTo(Duration.ofDays(5));
        assertThat(settings.notifyRecipients()).containsExactly("x@fgv.nu", "y@fgv.nu", "z@fgv.nu");

        settings.save("", "", "", "", 3, "");

        assertThat(settings.swishNumber()).isEqualTo("070 111 22 33");
        assertThat(settings.bankgiro()).isEqualTo("123-4567");
    }

    @Test
    void theBankAccountMayBeLeftOutWhenOnlyTheBankgiroIsUsed() {
        final PaymentSettings onlyBankgiro = PaymentSettings.fixed(new PaymentProperties("", "", "FGV", "http://qr", Duration.ofDays(3), "FGV"), List.of());

        assertThat(onlyBankgiro.bankAccount()).isEmpty();
    }

    @Test
    void invalidValuesAreRefused() {
        assertThatThrownBy(() -> settings.save("1", "2", "3", "4", 0, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.save("1", "2", "3", "4", 61, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.save("1", "2", "3", "4", 3, "not-an-address")).isInstanceOf(IllegalArgumentException.class);
    }
}
