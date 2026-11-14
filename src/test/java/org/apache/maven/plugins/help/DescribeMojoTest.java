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
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.apache.maven.api.ArtifactCoordinates;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.Lifecycle;
import org.apache.maven.api.Packaging;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.model.Build;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Plugin;
import org.apache.maven.api.model.PluginContainer;
import org.apache.maven.api.model.PluginExecution;
import org.apache.maven.api.model.PluginManagement;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.descriptor.MojoDescriptor;
import org.apache.maven.api.plugin.descriptor.Parameter;
import org.apache.maven.api.plugin.descriptor.PluginDescriptor;
import org.apache.maven.api.services.LifecycleRegistry;
import org.apache.maven.api.services.ProjectBuilder;
import org.apache.maven.api.services.ProjectBuilderRequest;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.api.services.xml.ModelXmlFactory;
import org.apache.maven.api.services.xml.PluginXmlFactory;
import org.apache.maven.api.settings.Settings;
import org.apache.maven.impl.DefaultModelXmlFactory;
import org.apache.maven.impl.DefaultPluginXmlFactory;
import org.apache.maven.plugins.help.DescribeMojo.LoadedPlugin;
import org.apache.maven.plugins.help.DescribeMojo.PluginInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author <a href="mailto:vincent.siveton@gmail.com">Vincent Siveton</a>
 */
class DescribeMojoTest {

    private static final String PLUGIN_XML = "<plugin>\n"
            + "  <name>Test Plugin</name>\n"
            + "  <groupId>org.test</groupId>\n"
            + "  <artifactId>test-maven-plugin</artifactId>\n"
            + "  <version>1.0</version>\n"
            + "  <goalPrefix>test</goalPrefix>\n"
            + "  <mojos>\n"
            + "    <mojo>\n"
            + "      <goal>run</goal>\n"
            + "      <implementation>org.test.RunMojo</implementation>\n"
            + "      <language>java</language>\n"
            + "      <parameters>\n"
            + "        <parameter>\n"
            + "          <name>target</name>\n"
            + "          <type>java.io.File</type>\n"
            + "          <required>true</required>\n"
            + "          <editable>true</editable>\n"
            + "          <description>The target.</description>\n"
            + "        </parameter>\n"
            + "      </parameters>\n"
            + "      <configuration>\n"
            + "        <target implementation=\"java.io.File\" default-value=\"${project.build.directory}\">"
            + "${test.target}</target>\n"
            + "      </configuration>\n"
            + "    </mojo>\n"
            + "  </mojos>\n"
            + "</plugin>\n";

    @TempDir
    private Path tempDir;

    private final Session session = mock(Session.class);

    private final Project project = mock(Project.class);

    private final ProjectManager projectManager = mock(ProjectManager.class);

    private final Log log = mock(Log.class);

    @BeforeEach
    void setup() {
        when(session.getService(ProjectManager.class)).thenReturn(projectManager);
        when(session.getService(PluginXmlFactory.class)).thenReturn(new DefaultPluginXmlFactory());
        when(session.getSettings()).thenReturn(Settings.newBuilder().build());
        when(projectManager.getRemotePluginRepositories(project)).thenReturn(List.of());
        when(project.getModel()).thenReturn(Model.newBuilder().build());
    }

    private DescribeMojo newMojo() throws Exception {
        DescribeMojo mojo = new DescribeMojo();
        setParentFieldWithReflection(mojo, "session", session);
        setParentFieldWithReflection(mojo, "project", project);
        setParentFieldWithReflection(mojo, "log", log);
        return mojo;
    }

    /**
     * Makes the jar of a plugin resolvable by the session.
     *
     * @return the coordinates that are resolved
     */
    private ArtifactCoordinates resolvablePlugin(String groupId, String artifactId, String version) throws IOException {
        Path jar = tempDir.resolve(artifactId + "-" + version + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/maven/plugin.xml"));
            out.write(PLUGIN_XML.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        ArtifactCoordinates coordinates = mock(ArtifactCoordinates.class);
        DownloadedArtifact artifact = mock(DownloadedArtifact.class);
        when(artifact.getPath()).thenReturn(jar);
        when(session.createArtifactCoordinates(groupId, artifactId, version, "jar"))
                .thenReturn(coordinates);
        when(session.resolveArtifact(eq(coordinates), anyList())).thenReturn(artifact);
        return coordinates;
    }

    @Test
    void testToLinesRejectsOverflowingIndentation() throws Exception {
        Method toLines = DescribeMojo.class.getDeclaredMethod("toLines", String.class, int.class, int.class, int.class);
        toLines.setAccessible(true);

        InvocationTargetException exception = assertThrows(
                InvocationTargetException.class, () -> toLines.invoke(null, "text", Integer.MAX_VALUE, 2, 80));

        assertTrue(exception.getCause() instanceof MojoException);
    }

    @Test
    void testGetExpressionsRoot()
            throws NoSuchMethodException, SecurityException, IllegalAccessException, IllegalArgumentException,
                    InvocationTargetException {
        Method toLines = DescribeMojo.class.getDeclaredMethod("toLines", String.class, int.class, int.class, int.class);
        toLines.setAccessible(true);
        toLines.invoke(null, "", 2, 2, 80);
    }

    @Test
    void testToLinesWrapsLongLines() throws Exception {
        Method toLines = DescribeMojo.class.getDeclaredMethod("toLines", String.class, int.class, int.class, int.class);
        toLines.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> lines = (List<String>) toLines.invoke(null, "one two three four", 1, 2, 12);

        assertEquals(List.of("  one two", "  three four"), lines);
    }

    @Test
    void testValidExpression() throws Exception {
        Parameter parameter = Parameter.newBuilder()
                .name("name")
                .expression("${valid.expression}")
                .build();
        MojoDescriptor md = MojoDescriptor.newBuilder()
                .goal("goal")
                .parameters(List.of(parameter))
                .build();

        String ls = System.lineSeparator();

        assertEquals(
                "  Available parameters:" + ls + ls + "    name" + ls + "      User property: valid.expression" + ls
                        + "      (no description available)" + ls,
                describeParameters(md));
    }

    @Test
    void testInvalidExpression() throws Exception {
        // this is a defaultValue
        Parameter parameter = Parameter.newBuilder()
                .name("name")
                .expression("${project.build.directory}/generated-sources/foobar")
                .build();
        MojoDescriptor md = MojoDescriptor.newBuilder()
                .goal("goal")
                .parameters(List.of(parameter))
                .build();

        String ls = System.lineSeparator();

        assertEquals(
                "  Available parameters:" + ls + ls
                        + "    name"
                        + ls + "      Expression: ${project.build.directory}/generated-sources/foobar"
                        + ls + "      (no description available)"
                        + ls,
                describeParameters(md));
    }

    @Test
    void testMavenThreeDescriptorKeepsDefaultValueAndExpression() throws Exception {
        resolvablePlugin("org.test", "test-maven-plugin", "1.0");
        DescribeMojo mojo = newMojo();
        PluginInfo pi = new PluginInfo();
        pi.setGroupId("org.test");
        pi.setArtifactId("test-maven-plugin");
        pi.setVersion("1.0");

        LoadedPlugin loaded = lookupPluginDescriptor(mojo, pi);
        MojoDescriptor md = loaded.descriptor.getMojos().get(0);
        Parameter parameter = md.getParameters().get(0);

        assertEquals("${project.build.directory}", loaded.defaultValue(md, parameter));
        assertEquals("${test.target}", loaded.expression(md, parameter));
    }

    private String describeParameters(MojoDescriptor md) throws Exception {
        Session styled = mock(Session.class);
        org.apache.maven.api.services.MessageBuilderFactory factory =
                mock(org.apache.maven.api.services.MessageBuilderFactory.class);
        when(styled.getService(org.apache.maven.api.services.MessageBuilderFactory.class))
                .thenReturn(factory);
        when(factory.builder()).thenAnswer(i -> new PlainMessageBuilder());

        DescribeMojo mojo = new DescribeMojo();
        setParentFieldWithReflection(mojo, "session", styled);

        StringBuilder sb = new StringBuilder();
        Method describeMojoParameters = DescribeMojo.class.getDeclaredMethod(
                "describeMojoParameters", LoadedPlugin.class, MojoDescriptor.class, StringBuilder.class);
        describeMojoParameters.setAccessible(true);
        describeMojoParameters.invoke(
                mojo, new LoadedPlugin(PluginDescriptor.newBuilder().build(), Map.of()), md, sb);
        return sb.toString();
    }

    @Test
    void testParsePluginInfoGAV() throws Throwable {
        DescribeMojo mojo = new DescribeMojo();
        setFieldWithReflection(mojo, "groupId", "org.test");
        setFieldWithReflection(mojo, "artifactId", "test");
        setFieldWithReflection(mojo, "version", "1.0");

        PluginInfo pi = parsePluginLookupInfo(mojo);

        assertEquals("org.test", pi.getGroupId());
        assertEquals("test", pi.getArtifactId());
        assertEquals("1.0", pi.getVersion());
        assertNull(pi.getPrefix());
    }

    @Test
    void testParsePluginInfoPluginPrefix() throws Throwable {
        DescribeMojo mojo = new DescribeMojo();
        setFieldWithReflection(mojo, "plugin", "help");

        PluginInfo pi = parsePluginLookupInfo(mojo);

        assertNull(pi.getGroupId());
        assertNull(pi.getArtifactId());
        assertNull(pi.getVersion());
        assertEquals("help", pi.getPrefix());

        setFieldWithReflection(mojo, "plugin", "help2:::");

        pi = parsePluginLookupInfo(mojo);

        assertEquals("help2", pi.getPrefix());
    }

    @Test
    void testParsePluginInfoPluginGA() throws Throwable {
        DescribeMojo mojo = new DescribeMojo();
        setFieldWithReflection(mojo, "plugin", "org.test:test");

        PluginInfo pi = parsePluginLookupInfo(mojo);

        assertEquals("org.test", pi.getGroupId());
        assertEquals("test", pi.getArtifactId());
        assertNull(pi.getVersion());
        assertNull(pi.getPrefix());
    }

    @Test
    void testParsePluginInfoPluginGAV() throws Throwable {
        DescribeMojo mojo = new DescribeMojo();
        setFieldWithReflection(mojo, "plugin", "org.test:test:1.0");

        PluginInfo pi = parsePluginLookupInfo(mojo);

        assertEquals("org.test", pi.getGroupId());
        assertEquals("test", pi.getArtifactId());
        assertEquals("1.0", pi.getVersion());
        assertNull(pi.getPrefix());
    }

    @Test
    void testParsePluginInfoPluginIncorrect() throws Throwable {
        DescribeMojo mojo = new DescribeMojo();
        setFieldWithReflection(mojo, "plugin", "org.test:test:1.0:invalid");
        assertThrows(MojoException.class, () -> parsePluginLookupInfo(mojo));
    }

    @Test
    void testProjectNameOfAPomThatMavenCannotBuildIsReadFromTheFile() throws Exception {
        Path pom = tempDir.resolve("old.pom");
        Files.write(
                pom,
                ("<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>a</artifactId>"
                                + "<version>2.0</version><name>Old Plugin</name></project>")
                        .getBytes(StandardCharsets.UTF_8));
        ProjectBuilder projectBuilder = mock(ProjectBuilder.class);
        when(projectBuilder.build(any(ProjectBuilderRequest.class))).thenThrow(new MojoException("invalid POM"));
        when(session.getService(ProjectBuilder.class)).thenReturn(projectBuilder);
        when(session.getService(ModelXmlFactory.class)).thenReturn(new DefaultModelXmlFactory());
        DescribeMojo mojo = newMojo();

        Method readProjectName = DescribeMojo.class.getDeclaredMethod("readProjectName", Path.class);
        readProjectName.setAccessible(true);

        assertEquals("Old Plugin", readProjectName.invoke(mojo, pom));
    }

    @Test
    void testGoalPrefix() {
        assertEquals("help", DescribeMojo.goalPrefix("maven-help-plugin"));
        assertEquals("exec", DescribeMojo.goalPrefix("exec-maven-plugin"));
        assertNull(DescribeMojo.goalPrefix("something"));
    }

    @Test
    void testLookupPluginDescriptorPrefixOfAProjectPluginWithVersion() throws Throwable {
        resolvablePlugin("org.test", "test-maven-plugin", "1.0");
        when(project.getModel())
                .thenReturn(Model.newBuilder()
                        .build(Build.newBuilder()
                                .plugins(List.of(Plugin.newBuilder()
                                        .groupId("org.test")
                                        .artifactId("test-maven-plugin")
                                        .version("0.9")
                                        .build()))
                                .build())
                        .build());
        DescribeMojo mojo = newMojo();

        PluginInfo pi = new PluginInfo();
        pi.setPrefix("test");
        pi.setVersion("1.0");

        LoadedPlugin returned = lookupPluginDescriptor(mojo, pi);

        assertEquals("org.test:test-maven-plugin:1.0", returned.descriptor.getId());
        // the version of the parameter wins over the one of the project
        verify(session, never()).resolveVersionRange(any(), anyList());
    }

    @Test
    void testLookupPluginDescriptorPrefixOfAProjectPluginWithoutVersion() throws Throwable {
        resolvablePlugin("org.test", "test-maven-plugin", "2.0");
        when(project.getModel())
                .thenReturn(Model.newBuilder()
                        .build(Build.newBuilder()
                                .pluginManagement(PluginManagement.newBuilder()
                                        .plugins(List.of(Plugin.newBuilder()
                                                .groupId("org.test")
                                                .artifactId("test-maven-plugin")
                                                .version("2.0")
                                                .build()))
                                        .build())
                                .build())
                        .build());
        DescribeMojo mojo = newMojo();

        PluginInfo pi = new PluginInfo();
        pi.setPrefix("test");

        LoadedPlugin returned = lookupPluginDescriptor(mojo, pi);

        assertEquals("org.test", returned.descriptor.getGroupId());
        assertEquals("test", returned.descriptor.getGoalPrefix());
    }

    @Test
    void testLookupPluginDescriptorGAV() throws Throwable {
        resolvablePlugin("org.test", "test-maven-plugin", "1.0");
        DescribeMojo mojo = newMojo();

        PluginInfo pi = new PluginInfo();
        pi.setGroupId("org.test");
        pi.setArtifactId("test-maven-plugin");
        pi.setVersion("1.0");

        LoadedPlugin returned = lookupPluginDescriptor(mojo, pi);

        assertEquals("Test Plugin", returned.descriptor.getName());
        assertEquals("run", returned.descriptor.getMojos().get(0).getGoal());
        verify(session, never()).resolveVersionRange(any(), anyList());
    }

    @Test
    void testLookupPluginDescriptorPrefixNotFound() throws Throwable {
        when(session.createArtifactCoordinates(any(), any(), any(), any())).thenReturn(mock(ArtifactCoordinates.class));
        when(session.resolveVersionRange(any(), anyList())).thenThrow(new MojoException("not found"));
        DescribeMojo mojo = newMojo();

        PluginInfo pi = new PluginInfo();
        pi.setPrefix("nothing");

        MojoException e = assertThrows(MojoException.class, () -> lookupPluginDescriptor(mojo, pi));
        assertTrue(e.getMessage().contains("nothing"), e.getMessage());
    }

    @Test
    void testLookupPluginDescriptorGMissingA() {
        PluginInfo pi = new PluginInfo();
        pi.setGroupId("org.test");
        try {
            lookupPluginDescriptor(newMojo(), pi);
            fail();
        } catch (MojoException e) {
            assertTrue(e.getMessage().startsWith("You must specify either"));
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    void testLookupPluginDescriptorAMissingG() {
        PluginInfo pi = new PluginInfo();
        pi.setArtifactId("test");
        try {
            lookupPluginDescriptor(newMojo(), pi);
            fail();
        } catch (MojoException e) {
            assertTrue(e.getMessage().startsWith("You must specify either"));
        } catch (Exception e) {
            fail(e);
        }
    }

    private Lifecycle lifecycle(String id, Map<String, List<Plugin>> bindings, String... phases) {
        Lifecycle lifecycle = mock(Lifecycle.class);
        when(lifecycle.id()).thenReturn(id);
        List<Lifecycle.Phase> phaseMocks = new java.util.ArrayList<>();
        for (String phase : phases) {
            Lifecycle.Phase p = mock(Lifecycle.Phase.class);
            when(p.name()).thenReturn(phase);
            when(p.plugins()).thenReturn(bindings.getOrDefault(phase, List.of()));
            phaseMocks.add(p);
        }
        when(lifecycle.allPhases()).thenAnswer(i -> phaseMocks.stream());
        return lifecycle;
    }

    private void registry(Lifecycle lifecycle, String... phases) {
        LifecycleRegistry registry = mock(LifecycleRegistry.class);
        when(registry.iterator()).thenAnswer(i -> List.of(lifecycle).iterator());
        when(registry.computePhases(lifecycle)).thenReturn(List.of(phases));
        when(session.getService(LifecycleRegistry.class)).thenReturn(registry);
    }

    private static Plugin plugin(String artifactId, String version, String phase, String goal) {
        return Plugin.newBuilder()
                .groupId("org.apache.maven.plugins")
                .artifactId(artifactId)
                .version(version)
                .executions(List.of(PluginExecution.newBuilder()
                        .phase(phase)
                        .goals(List.of(goal))
                        .build()))
                .build();
    }

    /**
     * The "default" lifecycle has no binding of its own: the mojo must use the bindings of the packaging.
     */
    @Test
    void testDescribeCommandPackagingSpecificPhaseShowsBindings() throws Exception {
        Lifecycle lifecycle = lifecycle("default", Map.of(), "validate", "compile", "test", "package");
        registry(lifecycle, "validate", "compile", "test", "package");

        // packaging-specific bindings: only "compile" phase is bound
        Packaging packaging = mock(Packaging.class);
        when(packaging.id()).thenReturn("jar");
        when(packaging.plugins())
                .thenReturn(Map.of(
                        "default",
                        PluginContainer.newBuilder()
                                .plugins(List.of(plugin("maven-compiler-plugin", "3.11.0", "compile", "compile")))
                                .build()));
        when(project.getPackaging()).thenReturn(packaging);

        DescribeMojo mojo = newMojo();
        setFieldWithReflection(mojo, "cmd", "compile");

        StringBuilder sb = new StringBuilder();
        boolean result = describeCommand(mojo, sb);

        String output = sb.toString();
        assertFalse(result);
        assertTrue(output.contains("maven-compiler-plugin"), "Should show compiler plugin: " + output);
        assertFalse(output.contains("* compile: Not defined"), "compile should not be 'Not defined': " + output);
        assertTrue(output.contains("* validate: Not defined"), "validate has no binding: " + output);
        assertTrue(output.contains("It is a part of the lifecycle for the POM packaging 'jar'"), output);
    }

    /**
     * Built-in lifecycle (e.g. "clean") has bindings of its own: the mojo should use those directly without
     * consulting the packaging.
     */
    @Test
    void testDescribeCommandBuiltinLifecyclePhaseShowsBindings() throws Exception {
        Lifecycle lifecycle = lifecycle(
                "clean",
                Map.of("clean", List.of(plugin("maven-clean-plugin", "3.2.0", "clean", "clean"))),
                "pre-clean",
                "clean",
                "post-clean");
        registry(lifecycle, "pre-clean", "clean", "post-clean");

        DescribeMojo mojo = newMojo();
        setFieldWithReflection(mojo, "cmd", "clean");

        StringBuilder sb = new StringBuilder();
        boolean result = describeCommand(mojo, sb);

        String output = sb.toString();
        assertFalse(result);
        assertTrue(output.contains("'clean' is a phase within the 'clean' lifecycle"), output);
        assertTrue(output.contains("maven-clean-plugin"), output);
        assertTrue(output.contains("* pre-clean: Not defined"), output);
        assertTrue(output.contains("* post-clean: Not defined"), output);
    }

    @Test
    void testDescribeCommandAcceptsTheMavenThreeNameOfAPhase() throws Exception {
        Lifecycle lifecycle = lifecycle("default", Map.of(), "validate", "after:resources", "compile");
        Lifecycle.Alias alias = mock(Lifecycle.Alias.class);
        when(alias.v3Phase()).thenReturn("process-resources");
        when(alias.v4Phase()).thenReturn("after:resources");
        when(lifecycle.aliases()).thenReturn(List.of(alias));
        registry(lifecycle, "validate", "after:resources", "compile");

        Packaging packaging = mock(Packaging.class);
        when(packaging.id()).thenReturn("jar");
        when(packaging.plugins())
                .thenReturn(Map.of(
                        "default",
                        PluginContainer.newBuilder()
                                .plugins(List.of(
                                        plugin("maven-resources-plugin", "3.5.0", "process-resources", "resources")))
                                .build()));
        when(project.getPackaging()).thenReturn(packaging);

        DescribeMojo mojo = newMojo();
        setFieldWithReflection(mojo, "cmd", "process-resources");

        StringBuilder sb = new StringBuilder();
        describeCommand(mojo, sb);

        String output = sb.toString();
        assertTrue(output.contains("'process-resources' is a phase corresponding to this plugin:"), output);
        assertTrue(output.contains("org.apache.maven.plugins:maven-resources-plugin:3.5.0:resources"), output);
        assertTrue(output.contains("* after:resources: org.apache.maven.plugins:maven-resources-plugin"), output);
    }

    @Test
    void testDescribeCommandUnknownPhaseThrows() throws Exception {
        Lifecycle lifecycle = lifecycle("default", Map.of(), "validate");
        registry(lifecycle, "validate");

        DescribeMojo mojo = newMojo();
        setFieldWithReflection(mojo, "cmd", "nonexistent-phase");

        MojoException e = assertThrows(MojoException.class, () -> describeCommand(mojo, new StringBuilder()));
        assertTrue(e.getMessage().contains("nonexistent-phase"));
    }

    @Test
    void testDescribeCommandGoalSetsThePluginAndTheGoal() throws Exception {
        DescribeMojo mojo = newMojo();
        setFieldWithReflection(mojo, "cmd", "org.test:test-maven-plugin:1.0:run");

        StringBuilder sb = new StringBuilder();
        boolean result = describeCommand(mojo, sb);

        assertTrue(result);
        assertTrue(sb.toString().contains("is a plugin goal (aka mojo)"));
        assertEquals("org.test:test-maven-plugin:1.0", getFieldWithReflection(mojo, "plugin"));
        assertEquals("run", getFieldWithReflection(mojo, "goal"));

        setFieldWithReflection(mojo, "cmd", "test:run");
        describeCommand(mojo, new StringBuilder());
        assertEquals("test", getFieldWithReflection(mojo, "plugin"));
    }

    private static boolean describeCommand(DescribeMojo mojo, StringBuilder sb) throws Exception {
        Method describeCommand = DescribeMojo.class.getDeclaredMethod("describeCommand", StringBuilder.class);
        describeCommand.setAccessible(true);
        try {
            return (boolean) describeCommand.invoke(mojo, sb);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getTargetException();
        }
    }

    private static LoadedPlugin lookupPluginDescriptor(DescribeMojo mojo, PluginInfo pi) throws Exception {
        Method lookupPluginDescriptor =
                DescribeMojo.class.getDeclaredMethod("lookupPluginDescriptor", PluginInfo.class);
        lookupPluginDescriptor.setAccessible(true);
        try {
            return (LoadedPlugin) lookupPluginDescriptor.invoke(mojo, pi);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getTargetException();
        }
    }

    private static PluginInfo parsePluginLookupInfo(DescribeMojo mojo) throws Exception {
        Method parsePluginLookupInfo = DescribeMojo.class.getDeclaredMethod("parsePluginLookupInfo");
        parsePluginLookupInfo.setAccessible(true);
        try {
            return (PluginInfo) parsePluginLookupInfo.invoke(mojo);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getTargetException();
        }
    }

    private static void setParentFieldWithReflection(
            final DescribeMojo mojo, final String fieldName, final Object value)
            throws NoSuchFieldException, IllegalAccessException {
        final Field field = mojo.getClass().getSuperclass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(mojo, value);
    }

    private static void setFieldWithReflection(final Object mojo, final String fieldName, final Object value)
            throws NoSuchFieldException, IllegalAccessException {
        final Field field = mojo.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(mojo, value);
    }

    private static Object getFieldWithReflection(final Object mojo, final String fieldName)
            throws NoSuchFieldException, IllegalAccessException {
        final Field field = mojo.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(mojo);
    }

    /** A message builder without styles. */
    static final class PlainMessageBuilder implements org.apache.maven.api.services.MessageBuilder {
        private final StringBuilder sb = new StringBuilder();

        @Override
        public org.apache.maven.api.services.MessageBuilder style(String style) {
            return this;
        }

        @Override
        public org.apache.maven.api.services.MessageBuilder resetStyle() {
            return this;
        }

        @Override
        public org.apache.maven.api.services.MessageBuilder append(CharSequence s) {
            sb.append(s);
            return this;
        }

        @Override
        public org.apache.maven.api.services.MessageBuilder append(CharSequence s, int start, int end) {
            sb.append(s, start, end);
            return this;
        }

        @Override
        public org.apache.maven.api.services.MessageBuilder append(char c) {
            sb.append(c);
            return this;
        }

        @Override
        public org.apache.maven.api.services.MessageBuilder setLength(int length) {
            sb.setLength(length);
            return this;
        }

        @Override
        public String build() {
            return sb.toString();
        }
    }
}
