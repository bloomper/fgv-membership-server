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

import graphql.ErrorClassification;
import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolationException;
import nu.fgv.membership.server.keycloak.KeycloakException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Maps exceptions to the API's error codes, so that a client can tell a refused request from a failure. The services
 * reject what the rules do not allow with {@link IllegalArgumentException} (the request names something that does not
 * exist or is not valid) and {@link IllegalStateException} (it is not possible now, e.g. the request is paid already);
 * both are {@code BAD_REQUEST} and carry their message, which says why. What the access control refuses is
 * {@code FORBIDDEN} or {@code UNAUTHORIZED}, with messages in the request's language. Anything else is left to Spring,
 * which answers {@code INTERNAL_ERROR} without telling what went wrong.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class GlobalExceptionResolver extends DataFetcherExceptionResolverAdapter {

    private static final Locale SWEDISH = Locale.of("sv");

    private final MessageSource messageSource;

    public GlobalExceptionResolver(final MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    private static GraphQLError error(final ErrorClassification type, final @Nullable String message, final DataFetchingEnvironment environment) {
        return GraphQLError.newError()
                .errorType(type)
                .message(message)
                .path(environment.getExecutionStepInfo().getPath())
                .location(environment.getField().getSourceLocation())
                .build();
    }

    @Override
    protected @Nullable List<GraphQLError> resolveToMultipleErrors(final Throwable ex, final DataFetchingEnvironment environment) {
        if (ex instanceof final ConstraintViolationException e) {
            return e.getConstraintViolations().stream()
                    .map(violation -> error(ErrorType.BAD_REQUEST, violation.getMessage(), environment))
                    .toList();
        }

        final GraphQLError error = resolveToSingleError(ex, environment);

        return error != null ? List.of(error) : null;
    }

    @Override
    protected @Nullable GraphQLError resolveToSingleError(final Throwable ex, final DataFetchingEnvironment environment) {
        return switch (ex) {
            case final AccessDeniedException _ -> localized(ErrorType.FORBIDDEN, "error.forbidden", environment);
            case final AuthenticationException _ ->
                    localized(ErrorType.UNAUTHORIZED, "error.unauthorized", environment);
            case final KeycloakException _ ->
                    localized(CustomErrorType.DIRECTORY_UNAVAILABLE, "error.directory.unavailable", environment);
            case final OptimisticLockingFailureException _, final OptimisticLockException _ ->
                    localized(CustomErrorType.CONFLICT, "error.conflict", environment);
            case final IllegalStateException e when e.getMessage() != null && e.getMessage().startsWith("Spexregister") ->
                    localized(CustomErrorType.SPEXREGISTER_UNAVAILABLE, "error.spexregister.unavailable", environment);
            case final IllegalArgumentException e -> error(ErrorType.BAD_REQUEST, e.getMessage(), environment);
            case final IllegalStateException e -> error(ErrorType.BAD_REQUEST, e.getMessage(), environment);
            default -> null;
        };
    }

    private GraphQLError localized(final ErrorClassification type, final String code, final DataFetchingEnvironment environment) {
        final Locale locale = environment.getLocale() != null && Locale.ENGLISH.getLanguage().equals(environment.getLocale().getLanguage()) ? Locale.ENGLISH : SWEDISH;

        return error(type, messageSource.getMessage(code, null, code, locale), environment);
    }
}
