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

package nu.fgv.membership.server.util.graphql;

import graphql.GraphQLError;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.ResultPath;
import graphql.language.Field;
import graphql.schema.DataFetchingEnvironment;
import nu.fgv.membership.server.keycloak.KeycloakException;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.access.AccessDeniedException;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionResolverTest {

    private final GlobalExceptionResolver resolver = new GlobalExceptionResolver(messages());

    private static ResourceBundleMessageSource messages() {
        final ResourceBundleMessageSource source = new ResourceBundleMessageSource();

        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    private static DataFetchingEnvironment environment(final Locale locale) {
        final DataFetchingEnvironment environment = mock(DataFetchingEnvironment.class);
        final ExecutionStepInfo step = mock(ExecutionStepInfo.class);

        when(step.getPath()).thenReturn(ResultPath.rootPath().segment("members"));
        when(environment.getExecutionStepInfo()).thenReturn(step);
        when(environment.getField()).thenReturn(new Field("members"));
        when(environment.getLocale()).thenReturn(locale);
        return environment;
    }

    private GraphQLError resolve(final Throwable exception, final Locale locale) {
        final var errors = resolver.resolveException(exception, environment(locale)).block();

        assertThat(errors).hasSize(1);
        return errors.getFirst();
    }

    @Test
    void a_rule_that_says_no_is_a_bad_request_with_its_reason() {
        final GraphQLError error = resolve(new IllegalStateException("The last administrator cannot be demoted"), Locale.ENGLISH);

        assertThat(error.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        assertThat(error.getMessage()).isEqualTo("The last administrator cannot be demoted");
        assertThat(resolve(new IllegalArgumentException("Unknown role X"), Locale.ENGLISH).getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
    }

    @Test
    void a_refusal_is_forbidden_in_the_language_asked_for() {
        assertThat(resolve(new AccessDeniedException("no"), Locale.ENGLISH).getMessage()).isEqualTo("You are not allowed to do that.");
        assertThat(resolve(new AccessDeniedException("no"), Locale.forLanguageTag("sv")).getMessage()).isEqualTo("Du får inte göra det.");
        assertThat(resolve(new AccessDeniedException("no"), null).getErrorType()).isEqualTo(ErrorType.FORBIDDEN);
    }

    @Test
    void an_upstream_that_cannot_be_reached_has_its_own_code_and_a_message_that_tells_nothing_internal() {
        final GraphQLError spexregister = resolve(new IllegalStateException("Spexregister could not be reached: connect timed out"), Locale.ENGLISH);
        final GraphQLError directory = resolve(new KeycloakException("Keycloak said 500 on /admin/realms/fgv/users"), Locale.ENGLISH);

        assertThat(spexregister.getErrorType()).isEqualTo(CustomErrorType.SPEXREGISTER_UNAVAILABLE);
        assertThat(spexregister.getMessage()).doesNotContain("timed out");
        assertThat(directory.getErrorType()).isEqualTo(CustomErrorType.DIRECTORY_UNAVAILABLE);
        assertThat(directory.getMessage()).doesNotContain("500");
    }
}
