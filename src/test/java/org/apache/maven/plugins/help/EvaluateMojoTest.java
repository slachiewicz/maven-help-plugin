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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.testing.InjectMojo;
import org.apache.maven.api.plugin.testing.MojoParameter;
import org.apache.maven.api.plugin.testing.MojoTest;
import org.apache.maven.api.services.Prompter;
import org.apache.maven.api.settings.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import static org.apache.maven.api.plugin.testing.MojoExtension.setVariableValueToObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test class for the evaluate mojo of the Help Plugin.
 */
@MojoTest
class EvaluateMojoTest {

    private final Log log = mock(Log.class);

    private final Prompter prompter = mock(Prompter.class);

    private final ExpressionEvaluator expressionEvaluator = mock(ExpressionEvaluator.class);

    private final Session session = mock(Session.class);

    private final Project project = mock(Project.class);

    @TempDir
    private Path tempDir;

    @BeforeEach
    void setup() {
        when(session.getSettings())
                .thenReturn(Settings.newBuilder().interactiveMode(true).build());
        when(project.getId()).thenReturn("org.apache.maven.test:test:1.0");
    }

    private void prepare(EvaluateMojo mojo) throws Exception {
        setVariableValueToObject(mojo, "session", session);
        setVariableValueToObject(mojo, "project", project);
        setVariableValueToObject(mojo, "log", log);
        setVariableValueToObject(mojo, "prompter", prompter);
        Field field = EvaluateMojo.class.getDeclaredField("evaluator");
        field.setAccessible(true);
        field.set(mojo, expressionEvaluator);
    }

    /**
     * Tests evaluation of an expression in interactive mode with a mock prompter.
     * @throws Exception in case of errors.
     */
    @Test
    @InjectMojo(goal = "evaluate")
    void testEvaluateWithoutExpression(EvaluateMojo mojo) throws Exception {
        prepare(mojo);
        when(prompter.prompt(anyString())).thenReturn("${project.groupId}", "0");
        when(expressionEvaluator.evaluate(anyString())).thenReturn("My result");
        when(log.isInfoEnabled()).thenReturn(true);

        mojo.execute();

        String ls = System.lineSeparator();

        verify(log).info(ls + "My result");
        verify(log, never()).warn(anyString());
        verify(expressionEvaluator).evaluate("${project.groupId}");
        verify(prompter, times(2)).prompt(anyString());
    }

    /**
     * Tests evaluation of an expression in interactive mode with a mock prompter, when "output" is set.
     * @throws Exception in case of errors.
     */
    @Test
    @InjectMojo(goal = "evaluate")
    void testEvaluateWithoutExpressionWithOutput(EvaluateMojo mojo) throws Exception {
        prepare(mojo);
        setVariableValueToObject(mojo, "output", tempDir.resolve("result.txt"));
        when(prompter.prompt(anyString())).thenReturn("${project.artifactId}", "0");
        when(expressionEvaluator.evaluate(anyString())).thenReturn("My result");
        when(log.isInfoEnabled()).thenReturn(true);

        mojo.execute();

        String ls = System.lineSeparator();
        verify(log).info(ls + "My result");
        verify(log).warn(anyString());
        verify(expressionEvaluator).evaluate("${project.artifactId}");
        verify(prompter, times(2)).prompt(anyString());
    }

    /**
     * Tests that the result is written to the output file when an expression is given.
     * @throws Exception in case of errors.
     */
    @Test
    @InjectMojo(goal = "evaluate")
    @MojoParameter(name = "expression", value = "project.groupId")
    void testEvaluateWithOutputFile(EvaluateMojo mojo) throws Exception {
        prepare(mojo);
        Path output = tempDir.resolve("target/result.txt");
        setVariableValueToObject(mojo, "output", output);
        when(expressionEvaluator.evaluate("${project.groupId}")).thenReturn("org.apache.maven.its.help");

        mojo.execute();

        assertEquals("org.apache.maven.its.help", new String(Files.readAllBytes(output)));
    }

    /**
     * Tests evaluation of a complex expression.
     *
     * @throws Exception in case of errors.
     */
    @Test
    @ResourceLock(Resources.SYSTEM_OUT)
    @InjectMojo(goal = "evaluate")
    @MojoParameter(name = "forceStdout", value = "true")
    @MojoParameter(name = "expression", value = "project_groupId=${project.groupId}")
    void testEvaluateForComplexExpression(EvaluateMojo mojo) throws Exception {
        prepare(mojo);
        when(expressionEvaluator.evaluate(anyString())).thenReturn("project_groupId=org.apache.maven.its.help");

        // Quiet mode given on command line.(simulation)
        when(log.isInfoEnabled()).thenReturn(false);

        assertEquals("project_groupId=org.apache.maven.its.help", execute(mojo));
        verify(log, never()).warn(anyString());
    }

    /**
     * This test will check that only the <code>project.groupId</code> is printed to
     * stdout nothing else.
     *
     * @throws Exception in case of errors.
     * @see <a href="https://issues.apache.org/jira/browse/MPH-144">MPH-144</a>
     */
    @Test
    @ResourceLock(Resources.SYSTEM_OUT)
    @InjectMojo(goal = "evaluate")
    @MojoParameter(name = "forceStdout", value = "true")
    @MojoParameter(name = "expression", value = "project.groupId")
    void testEvaluateQuiteModeWithOutputOnStdout(EvaluateMojo mojo) throws Exception {
        prepare(mojo);
        when(expressionEvaluator.evaluate(anyString())).thenReturn("org.apache.maven.its.help");

        // Quiet mode given on command line.(simulation)
        when(log.isInfoEnabled()).thenReturn(false);

        assertEquals("org.apache.maven.its.help", execute(mojo));
        verify(log, never()).warn(anyString());
    }

    /**
     * A collection is written as XML, named after its items.
     *
     * @throws Exception in case of errors.
     */
    @Test
    @ResourceLock(Resources.SYSTEM_OUT)
    @InjectMojo(goal = "evaluate")
    @MojoParameter(name = "forceStdout", value = "true")
    @MojoParameter(name = "expression", value = "project.build.resources")
    void testEvaluateCollectionAsXml(EvaluateMojo mojo) throws Exception {
        prepare(mojo);
        when(expressionEvaluator.evaluate(anyString()))
                .thenReturn(java.util.List.of(org.apache.maven.api.model.Resource.newBuilder()
                        .directory("src/main/resources")
                        .build()));
        when(log.isInfoEnabled()).thenReturn(false);

        String result = execute(mojo);
        assertTrue(result.startsWith("<resources>"), result);
        assertTrue(result.contains("<resource>"), result);
        assertTrue(result.contains("<directory>src/main/resources</directory>"), result);
    }

    private static String execute(EvaluateMojo mojo) throws Exception {
        PrintStream saveOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));

        try {
            mojo.execute();
        } finally {
            System.setOut(saveOut);
            baos.close();
        }
        return baos.toString();
    }
}
