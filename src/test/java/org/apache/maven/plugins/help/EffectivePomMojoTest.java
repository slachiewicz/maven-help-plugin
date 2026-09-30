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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.maven.api.MojoExecution;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.model.InputLocation;
import org.apache.maven.api.model.InputSource;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.testing.InjectMojo;
import org.apache.maven.api.plugin.testing.MojoParameter;
import org.apache.maven.api.plugin.testing.MojoTest;
import org.apache.maven.api.services.MessageBuilderFactory;
import org.apache.maven.api.services.xml.ModelXmlFactory;
import org.apache.maven.impl.DefaultModelXmlFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.apache.maven.api.plugin.testing.MojoExtension.setVariableValueToObject;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test class for the effective-pom mojo of the Help Plugin.
 */
@MojoTest
class EffectivePomMojoTest {

    private final Session session = mock(Session.class);

    private final MojoExecution mojoExecution = mock(MojoExecution.class);

    @TempDir
    private Path tempDir;

    private Path output;

    private Project newProject(String artifactId) {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn("org.test:" + artifactId + ":1.0");
        when(project.getModel())
                .thenReturn(Model.newBuilder()
                        .modelVersion("4.0.0")
                        .groupId("org.test")
                        .artifactId(artifactId)
                        .version("1.0")
                        .properties(Map.of("z", "1", "a", "2"))
                        .build());
        return project;
    }

    @BeforeEach
    void setup() {
        when(session.getService(ModelXmlFactory.class)).thenReturn(new DefaultModelXmlFactory());
        MessageBuilderFactory messageBuilderFactory = mock(MessageBuilderFactory.class);
        when(session.getService(MessageBuilderFactory.class)).thenReturn(messageBuilderFactory);
        output = tempDir.resolve("effective-pom.xml");
    }

    private void prepare(EffectivePomMojo mojo, Project project, Project... others) throws Exception {
        List<Project> projects = new java.util.ArrayList<>(List.of(project));
        projects.addAll(List.of(others));
        when(session.getProjects()).thenReturn(projects);
        setVariableValueToObject(mojo, "session", session);
        setVariableValueToObject(mojo, "project", project);
        setVariableValueToObject(mojo, "mojoExecution", mojoExecution);
        setVariableValueToObject(mojo, "log", mock(Log.class));
        setVariableValueToObject(mojo, "output", output);
    }

    @Test
    @InjectMojo(goal = "effective-pom")
    void writesTheModelOfTheProjectWithSortedProperties(EffectivePomMojo mojo) throws Exception {
        prepare(mojo, newProject("one"));

        mojo.execute();

        String xml = Files.readString(output);
        assertTrue(xml.contains("Effective POM for project 'org.test:one:1.0'"), xml);
        assertTrue(xml.contains("<artifactId>one</artifactId>"), xml);
        assertTrue(xml.indexOf("<a>2</a>") < xml.indexOf("<z>1</z>"), xml);
        assertFalse(xml.contains("<projects>"), xml);
    }

    @Test
    @InjectMojo(goal = "effective-pom")
    void writesEveryProjectOfTheReactorWhenCalledFromTheCommandLine(EffectivePomMojo mojo) throws Exception {
        prepare(mojo, newProject("one"), newProject("two"));
        // not bound to a phase: invoked from the command line
        when(mojoExecution.getLifecyclePhase()).thenReturn(null);

        mojo.execute();

        String xml = Files.readString(output);
        assertTrue(xml.contains("<projects>"), xml);
        assertTrue(xml.contains("<artifactId>one</artifactId>"), xml);
        assertTrue(xml.contains("<artifactId>two</artifactId>"), xml);
    }

    @Test
    @InjectMojo(goal = "effective-pom")
    void writesOnlyTheCurrentProjectWhenBoundToAPhase(EffectivePomMojo mojo) throws Exception {
        Project two = newProject("two");
        prepare(mojo, newProject("one"), two);
        setVariableValueToObject(mojo, "project", two);
        when(mojoExecution.getLifecyclePhase()).thenReturn("validate");

        mojo.execute();

        String xml = Files.readString(output);
        assertFalse(xml.contains("<projects>"), xml);
        assertFalse(xml.contains("<artifactId>one</artifactId>"), xml);
        assertTrue(xml.contains("<artifactId>two</artifactId>"), xml);
    }

    @Test
    @InjectMojo(goal = "effective-pom")
    @MojoParameter(name = "verbose", value = "true")
    void verboseWritesTheLocationOfTheElements(EffectivePomMojo mojo) throws Exception {
        Project project = newProject("one");
        InputSource source = new InputSource("org.test:one:1.0", "pom.xml");
        Model located = Model.newBuilder(project.getModel(), true)
                .location("artifactId", new InputLocation(7, 3, source))
                .build();
        when(project.getModel()).thenReturn(located);
        prepare(mojo, project);

        mojo.execute();

        String xml = Files.readString(output);
        assertTrue(xml.contains("<artifactId>one</artifactId>  <!--"), xml);
        assertTrue(xml.contains("org.test:one:1.0, line 7"), xml);
    }
}
