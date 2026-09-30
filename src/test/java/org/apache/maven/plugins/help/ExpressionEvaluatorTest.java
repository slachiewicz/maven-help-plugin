/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.help;

import java.util.List;
import java.util.Map;

import org.apache.maven.api.MojoExecution;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.model.Build;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Resource;
import org.apache.maven.api.settings.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the expressions supported by the evaluate goal.
 */
class ExpressionEvaluatorTest {

    private final Session session = mock(Session.class);

    private final Project project = mock(Project.class);

    private ExpressionEvaluator evaluator;

    @BeforeEach
    void setup() {
        Model model = Model.newBuilder()
                .groupId("org.test")
                .artifactId("test")
                .version("1.0")
                .name("Test")
                .properties(Map.of("my.property", "from-pom"))
                .build(Build.newBuilder()
                        .directory("/base/target")
                        .resources(List.of(Resource.newBuilder()
                                .directory("/base/src/main/resources")
                                .build()))
                        .build())
                .build();
        when(project.getModel()).thenReturn(model);
        when(project.getBuild()).thenReturn(model.getBuild());
        when(project.getGroupId()).thenReturn("org.test");
        when(project.getBasedir()).thenReturn(java.nio.file.Path.of("/base"));
        when(session.getSettings())
                .thenReturn(Settings.newBuilder().offline(true).build());
        when(session.getEffectiveProperties()).thenReturn(Map.of("my.user.property", "from-session"));
        evaluator = new ExpressionEvaluator(session, project, mock(MojoExecution.class));
    }

    @Test
    void projectGetterOfTheApi() {
        assertEquals("org.test", evaluator.evaluate("${project.groupId}"));
    }

    @Test
    void projectGetterOfTheModel() {
        // the project has no getName(), the model has
        assertEquals("Test", evaluator.evaluate("${project.name}"));
        assertEquals("/base/target", evaluator.evaluate("${project.build.directory}"));
    }

    @Test
    void modelCollectionAndIndex() {
        assertTrue(evaluator.evaluate("${project.build.resources}") instanceof List);
        assertEquals("/base/src/main/resources", evaluator.evaluate("${project.build.resources[0].directory}"));
    }

    @Test
    void mapKey() {
        assertEquals("from-pom", evaluator.evaluate("${project.properties(my.property)}"));
    }

    @Test
    void settingsBooleanGetter() {
        assertEquals(Boolean.TRUE, evaluator.evaluate("${settings.offline}"));
    }

    @Test
    void basedir() {
        assertEquals(java.nio.file.Path.of("/base"), evaluator.evaluate("${basedir}"));
    }

    @Test
    void propertyOfTheProjectThenOfTheSession() {
        assertEquals("from-pom", evaluator.evaluate("${my.property}"));
        assertEquals("from-session", evaluator.evaluate("${my.user.property}"));
        assertEquals(System.getProperty("java.version"), evaluator.evaluate("${java.version}"));
    }

    @Test
    void unknownExpressionIsNull() {
        assertNull(evaluator.evaluate("${project.doesNotExist}"));
        assertNull(evaluator.evaluate("${does.not.exist}"));
    }

    @Test
    void severalExpressionsAreInterpolatedAndUnknownOnesKept() {
        assertEquals(
                "org.test:test-${does.not.exist}",
                evaluator.evaluate("${project.groupId}:${project.model.artifactId}-${does.not.exist}"));
    }
}
