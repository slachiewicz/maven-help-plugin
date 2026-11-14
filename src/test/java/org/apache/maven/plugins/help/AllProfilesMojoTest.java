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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.model.Profile;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.testing.InjectMojo;
import org.apache.maven.api.plugin.testing.MojoTest;
import org.apache.maven.api.settings.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.apache.maven.api.plugin.testing.MojoExtension.setVariableValueToObject;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test class for the all-profiles mojo of the Help Plugin.
 */
@MojoTest
class AllProfilesMojoTest {

    private final Project project = mock(Project.class);

    private final Session session = mock(Session.class);

    private final Log log = mock(Log.class);

    @TempDir
    private Path tempDir;

    private Path outputPath;

    private final List<Profile> projectProfiles = new ArrayList<>();
    private final List<Profile> projectActiveProfiles = new ArrayList<>();
    private final List<org.apache.maven.api.settings.Profile> settingsProfiles = new ArrayList<>();
    private final List<String> activeSettingsProfiles = new ArrayList<>();

    @BeforeEach
    void setup() throws IOException {
        when(session.getProjects()).thenReturn(List.of(project));
        when(session.getSettings())
                .thenAnswer(i -> Settings.newBuilder()
                        .profiles(settingsProfiles)
                        .activeProfiles(activeSettingsProfiles)
                        .build());

        when(project.getId()).thenReturn("org.apache.maven.test:test:1.0");
        when(project.getEffectiveActiveProfiles()).thenReturn(projectActiveProfiles);
        when(project.getDeclaredProfiles()).thenReturn(projectProfiles);
        when(project.getParent()).thenReturn(Optional.empty());

        outputPath = Files.createTempFile(tempDir, "maven-help-plugin-test-", ".txt");
    }

    private void prepare(AllProfilesMojo mojo) throws Exception {
        setVariableValueToObject(mojo, "session", session);
        setVariableValueToObject(mojo, "log", log);
        setVariableValueToObject(mojo, "output", outputPath);
    }

    /**
     * Tests the case when no profiles are present for the projects.
     *
     * @throws Exception in case of errors.
     */
    @Test
    @InjectMojo(goal = "all-profiles")
    void testNoProfiles(AllProfilesMojo mojo) throws Exception {
        prepare(mojo);

        mojo.execute();

        verify(log).warn("No profiles detected!");
    }

    /**
     * Tests the case when profiles are present in the POM and in a parent POM.
     *
     * @throws Exception in case of errors.
     */
    @Test
    @InjectMojo(goal = "all-profiles")
    void testProfileFromPom(AllProfilesMojo mojo) throws Exception {
        prepare(mojo);
        projectProfiles.add(newPomProfile("pro-1", "pom"));
        projectProfiles.add(newPomProfile("pro-2", "pom"));

        Project parentProject = mock(Project.class);
        when(parentProject.getDeclaredProfiles()).thenReturn(List.of(newPomProfile("pro-3", "pom")));
        when(parentProject.getEffectiveActiveProfiles()).thenReturn(List.of());
        when(parentProject.getParent()).thenReturn(Optional.empty());
        when(project.getParent()).thenReturn(Optional.of(parentProject));

        projectActiveProfiles.add(newPomProfile("pro-1", "pom"));

        mojo.execute();

        String file = readOutput();
        assertTrue(file.contains("Profile Id: pro-1 (Active: true, Source: pom)"), file);
        assertTrue(file.contains("Profile Id: pro-2 (Active: false, Source: pom)"), file);
        assertTrue(file.contains("Profile Id: pro-3 (Active: false, Source: pom)"), file);
    }

    /**
     * Tests that a parent profile injected into the current project is active.
     *
     * @throws Exception in case of errors.
     */
    @Test
    @InjectMojo(goal = "all-profiles")
    void testInjectedProfileFromParentPom(AllProfilesMojo mojo) throws Exception {
        prepare(mojo);
        Project parentProject = newParentProject("pro-1");
        when(project.getParent()).thenReturn(Optional.of(parentProject));
        projectActiveProfiles.add(newPomProfile("pro-1", "pom"));

        mojo.execute();

        String file = readOutput();
        assertTrue(file.contains("Profile Id: pro-1 (Active: true, Source: pom)"), file);
        assertFalse(file.contains("Profile Id: pro-1 (Active: false, Source: pom)"), file);
    }

    /**
     * Tests that a profile active only in the parent's build context is inactive for the current project.
     *
     * @throws Exception in case of errors.
     */
    @Test
    @InjectMojo(goal = "all-profiles")
    void testParentActiveProfileNotInjectedIntoProject(AllProfilesMojo mojo) throws Exception {
        prepare(mojo);
        Project parentProject = newParentProject("pro-1");
        when(parentProject.getEffectiveActiveProfiles()).thenReturn(List.of(newPomProfile("pro-1", "pom")));
        when(project.getParent()).thenReturn(Optional.of(parentProject));

        mojo.execute();

        String file = readOutput();
        assertTrue(file.contains("Profile Id: pro-1 (Active: false, Source: pom)"), file);
        assertFalse(file.contains("Profile Id: pro-1 (Active: true, Source: pom)"), file);
    }

    /**
     * Tests that only injected profiles of more distant ancestors are active.
     *
     * @throws Exception in case of errors.
     */
    @Test
    @InjectMojo(goal = "all-profiles")
    void testInjectedProfileFromGrandparentPom(AllProfilesMojo mojo) throws Exception {
        prepare(mojo);
        Project parentProject = newParentProject("parent-profile");
        Project grandparentProject = newParentProject("grandparent-profile");
        when(parentProject.getParent()).thenReturn(Optional.of(grandparentProject));
        when(project.getParent()).thenReturn(Optional.of(parentProject));
        projectActiveProfiles.add(newPomProfile("grandparent-profile", "pom"));

        mojo.execute();

        String file = readOutput();
        assertTrue(file.contains("Profile Id: grandparent-profile (Active: true, Source: pom)"), file);
        assertFalse(file.contains("Profile Id: grandparent-profile (Active: false, Source: pom)"), file);
        assertTrue(file.contains("Profile Id: parent-profile (Active: false, Source: pom)"), file);
        assertFalse(file.contains("Profile Id: parent-profile (Active: true, Source: pom)"), file);
    }

    /**
     * Tests the case when profiles are present in the settings.
     *
     * @throws Exception in case of errors.
     */
    @Test
    @InjectMojo(goal = "all-profiles")
    void testProfileFromSettings(AllProfilesMojo mojo) throws Exception {
        prepare(mojo);
        settingsProfiles.add(newSettingsProfile("settings-1"));
        settingsProfiles.add(newSettingsProfile("settings-2"));
        activeSettingsProfiles.add("settings-1");

        mojo.execute();

        String file = readOutput();
        assertTrue(file.contains("Profile Id: settings-1 (Active: true, Source: settings.xml)"), file);
        assertTrue(file.contains("Profile Id: settings-2 (Active: false, Source: settings.xml)"), file);
    }

    /**
     * Tests that an active settings profile is not overwritten by an inactive parent profile with the same id.
     *
     * @throws Exception in case of errors.
     */
    @Test
    @InjectMojo(goal = "all-profiles")
    void testActiveSettingsProfileWithInactiveParentProfile(AllProfilesMojo mojo) throws Exception {
        prepare(mojo);
        Project parentProject = newParentProject("collision");
        when(project.getParent()).thenReturn(Optional.of(parentProject));
        settingsProfiles.add(newSettingsProfile("collision"));
        activeSettingsProfiles.add("collision");
        projectActiveProfiles.add(newPomProfile("collision", "settings.xml"));

        mojo.execute();

        String file = readOutput();
        assertTrue(file.contains("Profile Id: collision (Active: true, Source: settings.xml)"), file);
        assertFalse(file.contains("Profile Id: collision (Active: true, Source: pom)"), file);
    }

    /**
     * Tests that the POM profile wins when the same id is injected from the settings and from a parent POM.
     *
     * @throws Exception in case of errors.
     */
    @Test
    @InjectMojo(goal = "all-profiles")
    void testActiveSettingsProfileWithActiveParentProfile(AllProfilesMojo mojo) throws Exception {
        prepare(mojo);
        Project parentProject = newParentProject("collision");
        when(project.getParent()).thenReturn(Optional.of(parentProject));
        settingsProfiles.add(newSettingsProfile("collision"));
        activeSettingsProfiles.add("collision");
        projectActiveProfiles.add(newPomProfile("collision", "settings.xml"));
        projectActiveProfiles.add(newPomProfile("collision", "pom"));

        mojo.execute();

        String file = readOutput();
        assertTrue(file.contains("Profile Id: collision (Active: true, Source: pom)"), file);
        assertFalse(file.contains("Profile Id: collision (Active: true, Source: settings.xml)"), file);
    }

    private Project newParentProject(String profileId) {
        Project parentProject = mock(Project.class);
        when(parentProject.getDeclaredProfiles()).thenReturn(List.of(newPomProfile(profileId, "pom")));
        when(parentProject.getEffectiveActiveProfiles()).thenReturn(List.of());
        when(parentProject.getParent()).thenReturn(Optional.empty());
        return parentProject;
    }

    private Profile newPomProfile(String id, String source) {
        Profile profile = Profile.newBuilder().id(id).build();
        profile.setSource(source);
        return profile;
    }

    private org.apache.maven.api.settings.Profile newSettingsProfile(String id) {
        return org.apache.maven.api.settings.Profile.newBuilder().id(id).build();
    }

    private String readOutput() throws IOException {
        return new String(Files.readAllBytes(outputPath));
    }
}
