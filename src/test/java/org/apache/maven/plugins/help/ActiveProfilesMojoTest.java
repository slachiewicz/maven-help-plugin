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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.model.Profile;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.testing.InjectMojo;
import org.apache.maven.api.plugin.testing.MojoTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.apache.maven.api.plugin.testing.MojoExtension.setVariableValueToObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test class for the active-profiles mojo of the Help Plugin.
 */
@MojoTest
class ActiveProfilesMojoTest {

    private final Project project = mock(Project.class);

    private final Session session = mock(Session.class);

    @TempDir
    private Path tempDir;

    private Path outputPath;

    @BeforeEach
    void setup() throws IOException {
        when(project.getId()).thenReturn("org.apache.maven.test:test:pom:1.0");
        when(project.getGroupId()).thenReturn("org.apache.maven.test");
        when(project.getArtifactId()).thenReturn("test");
        when(project.getVersion()).thenReturn("1.0");
        when(project.getParent()).thenReturn(Optional.empty());
        when(project.getDeclaredActiveProfiles()).thenReturn(List.of());
        when(session.getProjects()).thenReturn(List.of(project));

        outputPath = Files.createTempFile(tempDir, "maven-help-plugin-test-", ".txt");
    }

    private void prepare(ActiveProfilesMojo mojo) throws Exception {
        setVariableValueToObject(mojo, "session", session);
        setVariableValueToObject(mojo, "log", mock(Log.class));
        setVariableValueToObject(mojo, "output", outputPath);
    }

    private static Profile profile(String id, String source) {
        Profile profile = Profile.newBuilder().id(id).build();
        profile.setSource(source);
        return profile;
    }

    /**
     * Tests that profiles activated in the settings are resolved.
     *
     * @throws Exception in case of errors.
     */
    @Test
    @InjectMojo(goal = "active-profiles")
    void testActiveProfilesFromSettings(ActiveProfilesMojo mojo) throws Exception {
        prepare(mojo);
        when(project.getEffectiveActiveProfiles())
                .thenReturn(List.of(profile("from-settings", Profile.SOURCE_SETTINGS)));

        mojo.execute();

        String file = readOutput();
        assertTrue(file.contains("from-settings (source: external)"), file);
    }

    /**
     * Tests that profiles activated in the POM are resolved.
     *
     * @throws Exception in case of errors.
     */
    @Test
    @InjectMojo(goal = "active-profiles")
    void testActiveProfilesFromPom(ActiveProfilesMojo mojo) throws Exception {
        prepare(mojo);
        when(project.getDeclaredActiveProfiles()).thenReturn(List.of(profile("from-pom", Profile.SOURCE_POM)));
        when(project.getEffectiveActiveProfiles()).thenReturn(List.of(profile("from-pom", Profile.SOURCE_POM)));

        mojo.execute();

        String file = readOutput();
        assertTrue(file.contains("from-pom (source: org.apache.maven.test:test:1.0)"), file);
    }

    /**
     * Tests that a profile of a parent POM is attributed to the parent, and that the profiles of settings.xml
     * are listed once.
     *
     * @throws Exception in case of errors.
     */
    @Test
    @InjectMojo(goal = "active-profiles")
    void testActiveProfilesFromParent(ActiveProfilesMojo mojo) throws Exception {
        prepare(mojo);
        Project parent = mock(Project.class);
        when(parent.getGroupId()).thenReturn("org.apache.maven.test");
        when(parent.getArtifactId()).thenReturn("parent");
        when(parent.getVersion()).thenReturn("2.0");
        when(parent.getParent()).thenReturn(Optional.empty());
        when(parent.getDeclaredActiveProfiles()).thenReturn(List.of(profile("from-parent", Profile.SOURCE_POM)));
        when(project.getParent()).thenReturn(Optional.of(parent));
        when(project.getDeclaredActiveProfiles()).thenReturn(List.of(profile("from-pom", Profile.SOURCE_POM)));
        when(project.getEffectiveActiveProfiles())
                .thenReturn(List.of(
                        profile("from-parent", Profile.SOURCE_POM),
                        profile("from-pom", Profile.SOURCE_POM),
                        profile("from-settings", Profile.SOURCE_SETTINGS),
                        profile("from-settings", Profile.SOURCE_SETTINGS)));

        mojo.execute();

        String file = readOutput();
        assertTrue(file.contains("from-parent (source: org.apache.maven.test:parent:2.0)"), file);
        assertTrue(file.contains("from-pom (source: org.apache.maven.test:test:1.0)"), file);
        assertEquals(1, file.split("from-settings \\(source: external\\)", -1).length - 1, file);
    }

    /**
     * Tests the report of a project without active profile.
     *
     * @throws Exception in case of errors.
     */
    @Test
    @InjectMojo(goal = "active-profiles")
    void testNoActiveProfiles(ActiveProfilesMojo mojo) throws Exception {
        prepare(mojo);
        when(project.getEffectiveActiveProfiles()).thenReturn(List.of());

        mojo.execute();

        assertTrue(readOutput().contains("There are no active profiles."));
    }

    private String readOutput() throws IOException {
        return new String(Files.readAllBytes(outputPath));
    }
}
