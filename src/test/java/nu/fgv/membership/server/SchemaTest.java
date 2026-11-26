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

package nu.fgv.membership.server;

import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The schema files fit together: {@code extend type Query} in one file finds its type in another.
 */
class SchemaTest {

    @Test
    void schemaFilesAssemble() throws Exception {
        final TypeDefinitionRegistry registry = new TypeDefinitionRegistry();

        for (final var resource : new PathMatchingResourcePatternResolver().getResources("classpath:graphql/**/*.graphqls")) {
            try (var reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                registry.merge(new SchemaParser().parse(reader));
            }
        }

        final var schema = new SchemaGenerator().makeExecutableSchema(registry, RuntimeWiring.newRuntimeWiring().build());

        assertThat(schema.getQueryType().getFieldDefinition("membershipChanges")).isNotNull();
        assertThat(schema.getQueryType().getFieldDefinition("users")).isNotNull();
    }
}
