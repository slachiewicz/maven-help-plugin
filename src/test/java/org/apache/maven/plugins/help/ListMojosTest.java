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
import java.util.Set;

import org.apache.maven.api.Lifecycle;
import org.apache.maven.api.Packaging;
import org.apache.maven.api.Session;
import org.apache.maven.api.Type;
import org.apache.maven.api.model.Plugin;
import org.apache.maven.api.model.PluginContainer;
import org.apache.maven.api.model.PluginExecution;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.testing.InjectMojo;
import org.apache.maven.api.plugin.testing.MojoTest;
import org.apache.maven.api.services.LifecycleRegistry;
import org.apache.maven.api.services.PackagingRegistry;
import org.apache.maven.api.services.TypeRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.apache.maven.api.plugin.testing.MojoExtension.setVariableValueToObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test class for the goals of the Help Plugin that list what Maven defines.
 */
@MojoTest
class ListMojosTest {

    private final Session session = mock(Session.class);

    private final Log log = mock(Log.class);

    @TempDir
    private Path tempDir;

    private void prepare(AbstractHelpMojo mojo) throws Exception {
        setVariableValueToObject(mojo, "session", session);
        setVariableValueToObject(mojo, "log", log);
        setVariableValueToObject(mojo, "output", tempDir.resolve("out.txt"));
    }

    private String output() throws Exception {
        // with an output file configured, nothing goes to the log
        verify(log, never()).info(anyString());
        return Files.readString(tempDir.resolve("out.txt"));
    }

    private Lifecycle lifecycle(String id) {
        Lifecycle lifecycle = mock(Lifecycle.class);
        when(lifecycle.id()).thenReturn(id);
        return lifecycle;
    }

    @Test
    @InjectMojo(goal = "list-lifecycle-phases")
    void listLifecyclePhases(ListLifecyclePhasesMojo mojo) throws Exception {
        prepare(mojo);
        Lifecycle clean = lifecycle("clean");
        Lifecycle site = lifecycle("site");
        LifecycleRegistry registry = mock(LifecycleRegistry.class);
        when(registry.iterator()).thenAnswer(i -> List.of(clean, site).iterator());
        when(registry.computePhases(clean)).thenReturn(List.of("pre-clean", "clean", "post-clean"));
        when(registry.computePhases(site)).thenReturn(List.of("site"));
        when(session.getService(LifecycleRegistry.class)).thenReturn(registry);

        mojo.execute();

        String ls = System.lineSeparator();
        assertEquals(
                "clean" + ls + " * pre-clean" + ls + " * clean" + ls + " * post-clean" + ls + ls + "site" + ls
                        + " * site" + ls + ls,
                output());
    }

    @Test
    @InjectMojo(goal = "list-packaging")
    void listPackaging(ListPackagingMojo mojo) throws Exception {
        prepare(mojo);
        Lifecycle lifecycle = lifecycle("default");
        LifecycleRegistry registry = mock(LifecycleRegistry.class);
        when(registry.require("default")).thenReturn(lifecycle);
        when(registry.computePhases(lifecycle)).thenReturn(List.of("validate", "compile", "test"));
        when(session.getService(LifecycleRegistry.class)).thenReturn(registry);

        Packaging jar = mock(Packaging.class);
        when(jar.plugins())
                .thenReturn(Map.of(
                        "default",
                        PluginContainer.newBuilder()
                                .plugins(List.of(Plugin.newBuilder()
                                        .groupId("org.apache.maven.plugins")
                                        .artifactId("maven-compiler-plugin")
                                        .version("3.14.0")
                                        .executions(List.of(PluginExecution.newBuilder()
                                                .phase("compile")
                                                .goals(List.of("compile"))
                                                .build()))
                                        .build()))
                                .build()));
        PackagingRegistry packagingRegistry = mock(PackagingRegistry.class);
        when(packagingRegistry.lookup(anyString())).thenReturn(java.util.Optional.empty());
        when(packagingRegistry.lookup("jar")).thenReturn(java.util.Optional.of(jar));
        when(session.getService(PackagingRegistry.class)).thenReturn(packagingRegistry);

        mojo.execute();

        String ls = System.lineSeparator();
        assertEquals(
                "jar (lifecycle: default)" + ls + "  * compile" + ls
                        + "    - org.apache.maven.plugins:maven-compiler-plugin:3.14.0:compile" + ls + ls,
                output());
    }

    @Test
    @InjectMojo(goal = "list-packaging")
    void listPackagingMatchesTheMavenThreeNamesOfThePhases(ListPackagingMojo mojo) throws Exception {
        prepare(mojo);
        Lifecycle lifecycle = lifecycle("default");
        Lifecycle.Alias alias = mock(Lifecycle.Alias.class);
        when(alias.v3Phase()).thenReturn("process-resources");
        when(alias.v4Phase()).thenReturn("after:resources");
        when(lifecycle.aliases()).thenReturn(List.of(alias));
        LifecycleRegistry registry = mock(LifecycleRegistry.class);
        when(registry.require("default")).thenReturn(lifecycle);
        when(registry.computePhases(lifecycle)).thenReturn(List.of("resources", "after:resources", "compile"));
        when(session.getService(LifecycleRegistry.class)).thenReturn(registry);

        Packaging jar = mock(Packaging.class);
        when(jar.plugins())
                .thenReturn(Map.of(
                        "default",
                        PluginContainer.newBuilder()
                                .plugins(List.of(Plugin.newBuilder()
                                        .groupId("org.apache.maven.plugins")
                                        .artifactId("maven-resources-plugin")
                                        .version("3.5.0")
                                        .executions(List.of(PluginExecution.newBuilder()
                                                .phase("process-resources")
                                                .goals(List.of("resources"))
                                                .build()))
                                        .build()))
                                .build()));
        PackagingRegistry packagingRegistry = mock(PackagingRegistry.class);
        when(packagingRegistry.lookup(anyString())).thenReturn(java.util.Optional.empty());
        when(packagingRegistry.lookup("jar")).thenReturn(java.util.Optional.of(jar));
        when(session.getService(PackagingRegistry.class)).thenReturn(packagingRegistry);

        mojo.execute();

        String ls = System.lineSeparator();
        assertEquals(
                "jar (lifecycle: default)" + ls + "  * after:resources" + ls
                        + "    - org.apache.maven.plugins:maven-resources-plugin:3.5.0:resources" + ls + ls,
                output());
    }

    @Test
    @InjectMojo(goal = "list-dependency-types")
    void listDependencyTypes(ListDependencyTypesMojo mojo) throws Exception {
        prepare(mojo);
        Type jar = mock(Type.class);
        when(jar.getExtension()).thenReturn("jar");
        when(jar.getClassifier()).thenReturn("");
        when(jar.getPathTypes()).thenReturn(Set.of(org.apache.maven.api.JavaPathType.CLASSES));
        Type testJar = mock(Type.class);
        when(testJar.getExtension()).thenReturn("jar");
        when(testJar.getClassifier()).thenReturn("tests");
        when(testJar.getPathTypes()).thenReturn(Set.of());
        when(testJar.isIncludesDependencies()).thenReturn(true);
        TypeRegistry registry = mock(TypeRegistry.class);
        when(registry.lookup(anyString())).thenReturn(java.util.Optional.empty());
        when(registry.lookup("jar")).thenReturn(java.util.Optional.of(jar));
        when(registry.lookup("test-jar")).thenReturn(java.util.Optional.of(testJar));
        when(session.getService(TypeRegistry.class)).thenReturn(registry);

        mojo.execute();

        String ls = System.lineSeparator();
        assertEquals(
                "jar" + ls + " - Extension: *.jar" + ls + " - Added to classpath" + ls + ls + "test-jar" + ls
                        + " - Extension: *.jar" + ls + " - Classifier: tests" + ls + " - Includes dependencies" + ls
                        + ls,
                output());
        assertFalse(output().contains("pom"));
    }
}
