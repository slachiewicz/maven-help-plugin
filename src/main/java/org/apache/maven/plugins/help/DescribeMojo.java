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

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.maven.api.Lifecycle;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.Project;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.api.model.Build;
import org.apache.maven.api.model.Plugin;
import org.apache.maven.api.model.PluginContainer;
import org.apache.maven.api.model.PluginExecution;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.plugin.descriptor.MojoDescriptor;
import org.apache.maven.api.plugin.descriptor.PluginDescriptor;
import org.apache.maven.api.services.LifecycleRegistry;
import org.apache.maven.api.services.ProjectBuilder;
import org.apache.maven.api.services.ProjectBuilderRequest;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.api.services.xml.ModelXmlFactory;
import org.apache.maven.api.services.xml.PluginXmlFactory;
import org.apache.maven.reporting.MavenReport;
import org.apache.maven.tools.plugin.generator.HtmlToPlainTextConverter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Displays a list of the attributes for a Maven Plugin and/or goals (aka Mojo - Maven plain Old Java Object).
 *
 * @see <a href="http://maven.apache.org/general.html#What_is_a_Mojo">What is a Mojo?</a>
 * @since 2.0
 */
@Mojo(name = "describe", projectRequired = false, aggregator = true)
public class DescribeMojo extends AbstractHelpMojo {
    /**
     * The default indent size when writing description's Mojo.
     */
    private static final int INDENT_SIZE = 2;

    /**
     * For unknown values
     */
    private static final String UNKNOWN = "Unknown";

    /**
     * For not defined values
     */
    private static final String NOT_DEFINED = "Not defined";

    /**
     * For deprecated values
     */
    private static final String NO_REASON = "No reason given";

    private static final Pattern EXPRESSION = Pattern.compile("^\\$\\{([^}]+)\\}$");

    /**
     * The location of the plugin descriptor in a plugin jar.
     */
    private static final String PLUGIN_DESCRIPTOR = "META-INF/maven/plugin.xml";

    /**
     * The plugin groups Maven searches besides the plugin groups of the settings.
     */
    private static final List<String> DEFAULT_PLUGIN_GROUPS = List.of("org.apache.maven.plugins", "org.codehaus.mojo");

    // ----------------------------------------------------------------------
    // Mojo parameters
    // ----------------------------------------------------------------------

    /**
     * The Maven Plugin to describe. This must be specified in one of three ways:
     * <br/>
     * <ol>
     * <li>plugin-prefix, i.e. 'help'</li>
     * <li>groupId:artifactId, i.e. 'org.apache.maven.plugins:maven-help-plugin'</li>
     * <li>groupId:artifactId:version, i.e. 'org.apache.maven.plugins:maven-help-plugin:2.0'</li>
     * </ol>
     */
    @Parameter(property = "plugin", alias = "prefix")
    private String plugin;

    /**
     * The Maven Plugin <code>groupId</code> to describe.
     * <br/>
     * <b>Note</b>: Should be used with <code>artifactId</code> parameter.
     */
    @Parameter(property = "groupId")
    private String groupId;

    /**
     * The Maven Plugin <code>artifactId</code> to describe.
     * <br/>
     * <b>Note</b>: Should be used with <code>groupId</code> parameter.
     */
    @Parameter(property = "artifactId")
    private String artifactId;

    /**
     * The Maven Plugin <code>version</code> to describe.
     * <br/>
     * <b>Note</b>: Should be used with <code>groupId/artifactId</code> parameters.
     */
    @Parameter(property = "version")
    private String version;

    /**
     * The goal name of a Mojo to describe within the specified Maven Plugin.
     * If this parameter is specified, only the corresponding goal (Mojo) will be described,
     * rather than the whole Plugin.
     *
     * @since 2.1
     */
    @Parameter(property = "goal")
    private String goal;

    /**
     * This flag specifies that a detailed (verbose) list of goal (Mojo) information should be given.
     *
     * @since 2.1
     */
    @Parameter(property = "detail", defaultValue = "false")
    private boolean detail;

    /**
     * This flag specifies that a minimal list of goal (Mojo) information should be given.
     *
     * @since 2.1
     */
    @Parameter(property = "minimal", defaultValue = "false")
    private boolean minimal;

    /**
     * A Maven command like a single goal or a single phase following the Maven command line:
     * <br/>
     * <code>mvn [options] [&lt;goal(s)&gt;] [&lt;phase(s)&gt;]</code>
     *
     * @since 2.1
     */
    @Parameter(property = "cmd")
    private String cmd;

    // ----------------------------------------------------------------------
    // Public methods
    // ----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute() throws MojoException {
        StringBuilder descriptionBuffer = new StringBuilder();

        boolean describePlugin = true;
        if (cmd != null && !cmd.isEmpty()) {
            describePlugin = describeCommand(descriptionBuffer);
        }

        if (describePlugin) {
            PluginInfo pi = parsePluginLookupInfo();
            LoadedPlugin loaded = lookupPluginDescriptor(pi);
            PluginDescriptor descriptor = loaded.descriptor;
            if (goal != null && !goal.isEmpty()) {
                MojoDescriptor mojo = descriptor.getMojos().stream()
                        .filter(m -> goal.equals(m.getGoal()))
                        .findFirst()
                        .orElseThrow(() -> new MojoException(
                                "The goal '" + goal + "' does not exist in the plugin '" + pi.getPrefix() + "'"));
                describeMojo(loaded, mojo, descriptionBuffer);
            } else {
                describePlugin(loaded, descriptionBuffer);
            }
        }

        writeDescription(descriptionBuffer);
    }

    // ----------------------------------------------------------------------
    // Private methods
    // ----------------------------------------------------------------------

    /**
     * Method to write the Mojo description to the output file
     *
     * @param descriptionBuffer contains the description to be written to the file
     * @throws MojoException if any
     */
    private void writeDescription(StringBuilder descriptionBuffer) throws MojoException {
        if (output != null) {
            try {
                writeFile(output, descriptionBuffer);
            } catch (IOException e) {
                throw new MojoException("Cannot write plugin/goal description to output: " + output, e);
            }

            log.info("Wrote descriptions to: " + output);
        } else {
            log.info(descriptionBuffer.toString());
        }
    }

    /**
     * A plugin descriptor with the parameter configuration read from the descriptor file, because the Maven 4 model
     * of a plugin descriptor does not keep the default values and the expressions of a Maven 3 plugin descriptor.
     */
    static final class LoadedPlugin {
        final PluginDescriptor descriptor;

        /** goal, then parameter name, to {default value, expression} */
        final Map<String, Map<String, String[]>> configuration;

        LoadedPlugin(PluginDescriptor descriptor, Map<String, Map<String, String[]>> configuration) {
            this.descriptor = descriptor;
            this.configuration = configuration;
        }

        String defaultValue(MojoDescriptor md, org.apache.maven.api.plugin.descriptor.Parameter parameter) {
            if (parameter.getDefaultValue() != null) {
                return parameter.getDefaultValue();
            }
            String[] values = configuration.getOrDefault(md.getGoal(), Map.of()).get(parameter.getName());
            return values != null ? values[0] : null;
        }

        String expression(MojoDescriptor md, org.apache.maven.api.plugin.descriptor.Parameter parameter) {
            if (parameter.getExpression() != null && !parameter.getExpression().isEmpty()) {
                return parameter.getExpression();
            }
            String[] values = configuration.getOrDefault(md.getGoal(), Map.of()).get(parameter.getName());
            return values != null ? values[1] : null;
        }
    }

    /**
     * Method for retrieving the description of the plugin
     *
     * @param pi holds information of the plugin whose description is to be retrieved
     * @return a plugin descriptor where the plugin description is to be retrieved
     * @throws MojoException if the plugin could not be verify, or if groupId or artifactId is empty
     */
    private LoadedPlugin lookupPluginDescriptor(PluginInfo pi) throws MojoException {
        List<RemoteRepository> repositories =
                session.getService(ProjectManager.class).getRemotePluginRepositories(project);

        Coordinates forLookup = null;
        if (pi.getPrefix() != null && !pi.getPrefix().isEmpty()) {
            forLookup = findPluginForPrefix(pi.getPrefix(), repositories);
        } else if (pi.getGroupId() != null
                && !pi.getGroupId().isEmpty()
                && pi.getArtifactId() != null
                && !pi.getArtifactId().isEmpty()) {
            forLookup = new Coordinates(pi.getGroupId(), pi.getArtifactId(), null);
        }
        if (forLookup == null) {
            String msg = "You must specify either: both 'groupId' and 'artifactId' parameters OR a 'plugin' parameter"
                    + " OR a 'cmd' parameter. For instance:" + LS
                    + "  # mvn help:describe -Dcmd=install" + LS
                    + "or" + LS
                    + "  # mvn help:describe -Dcmd=help:describe" + LS
                    + "or" + LS
                    + "  # mvn help:describe -Dplugin=org.apache.maven.plugins:maven-help-plugin" + LS
                    + "or" + LS
                    + "  # mvn help:describe -DgroupId=org.apache.maven.plugins -DartifactId=maven-help-plugin" + LS
                    + LS
                    + "Try 'mvn help:help -Ddetail=true' for more information.";
            throw new MojoException(msg);
        }

        String pluginVersion;
        if (pi.getVersion() != null && !pi.getVersion().isEmpty()) {
            pluginVersion = pi.getVersion();
        } else if (forLookup.version != null) {
            pluginVersion = forLookup.version;
        } else {
            try {
                pluginVersion = resolvePluginVersion(forLookup, repositories);
            } catch (RuntimeException e) {
                throw new MojoException(
                        "Unable to resolve the version of the plugin with prefix: " + pi.getPrefix(), e);
            }
        }

        try {
            Path jar = resolveArtifactPath(
                    new Coordinates(forLookup.groupId, forLookup.artifactId, pluginVersion), "jar", repositories);
            return readPluginDescriptor(jar);
        } catch (Exception e) {
            throw new MojoException(
                    "Error retrieving plugin descriptor for:" + LS + LS + "groupId: '"
                            + forLookup.groupId + "'" + LS + "artifactId: '" + forLookup.artifactId + "'" + LS
                            + "version: '" + pluginVersion + "'" + LS
                            + LS,
                    e);
        }
    }

    /**
     * @return the plugins the project configures, in its build and in its plugin management
     */
    private List<Plugin> projectPlugins() {
        List<Plugin> candidates = new ArrayList<>();
        Build build = project.getModel().getBuild();
        if (build != null) {
            candidates.addAll(build.getPlugins());
            if (build.getPluginManagement() != null) {
                candidates.addAll(build.getPluginManagement().getPlugins());
            }
        }
        return candidates;
    }

    /**
     * Finds the version of a plugin: the one the project configures, else the latest one.
     */
    private String resolvePluginVersion(Coordinates plugin, List<RemoteRepository> repositories) {
        for (Plugin candidate : projectPlugins()) {
            if (plugin.groupId.equals(candidate.getGroupId())
                    && plugin.artifactId.equals(candidate.getArtifactId())
                    && candidate.getVersion() != null) {
                return candidate.getVersion();
            }
        }
        return resolveLatestVersion(plugin, repositories).toString();
    }

    /**
     * Finds the plugin for a goal prefix. The Maven 4 API has no access to the plugin prefix metadata of the
     * repositories, so the plugins of the project, and then the plugins named after the conventions
     * <code>maven-PREFIX-plugin</code> and <code>PREFIX-maven-plugin</code> in the plugin groups, are tried.
     *
     * @param prefix not null
     * @param repositories the plugin repositories
     * @return the plugin, the version is {@code null} if the project does not configure one
     * @throws MojoException if no plugin is found
     */
    private Coordinates findPluginForPrefix(String prefix, List<RemoteRepository> repositories) throws MojoException {
        for (Plugin candidate : projectPlugins()) {
            if (prefix.equals(goalPrefix(candidate.getArtifactId()))) {
                return new Coordinates(candidate.getGroupId(), candidate.getArtifactId(), candidate.getVersion());
            }
        }

        List<String> groups = new ArrayList<>(session.getSettings().getPluginGroups());
        DEFAULT_PLUGIN_GROUPS.stream().filter(g -> !groups.contains(g)).forEach(groups::add);
        for (String group : groups) {
            for (String artifactId : new String[] {"maven-" + prefix + "-plugin", prefix + "-maven-plugin"}) {
                try {
                    Coordinates candidate = new Coordinates(group, artifactId, null);
                    resolveLatestVersion(candidate, repositories);
                    return candidate;
                } catch (RuntimeException e) {
                    log.debug("No plugin " + group + ":" + artifactId + " for the prefix " + prefix, e);
                }
            }
        }
        throw new MojoException("Unable to find the plugin with prefix: " + prefix);
    }

    /**
     * @param artifactId a plugin artifactId
     * @return the goal prefix, following the naming convention, or {@code null} if the artifactId does not follow it
     */
    static String goalPrefix(String artifactId) {
        if (artifactId.startsWith("maven-") && artifactId.endsWith("-plugin")) {
            return artifactId.substring("maven-".length(), artifactId.length() - "-plugin".length());
        }
        if (artifactId.endsWith("-maven-plugin")) {
            return artifactId.substring(0, artifactId.length() - "-maven-plugin".length());
        }
        return null;
    }

    private LoadedPlugin readPluginDescriptor(Path jar) throws IOException {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry entry = jarFile.getJarEntry(PLUGIN_DESCRIPTOR);
            if (entry == null) {
                throw new IOException("No " + PLUGIN_DESCRIPTOR + " in " + jar);
            }
            PluginDescriptor descriptor;
            try (InputStream is = jarFile.getInputStream(entry)) {
                // not strict: a descriptor of a Maven 3 plugin has elements unknown to the Maven 4 model
                descriptor = session.getService(PluginXmlFactory.class).read(is, false);
            }
            try (InputStream is = jarFile.getInputStream(entry)) {
                return new LoadedPlugin(descriptor, readConfiguration(is));
            }
        }
    }

    /**
     * Reads the <code>configuration</code> element of each mojo of a plugin descriptor.
     */
    private static Map<String, Map<String, String[]>> readConfiguration(InputStream is) throws IOException {
        Map<String, Map<String, String[]>> result = new HashMap<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(is);
            NodeList mojos = document.getElementsByTagName("mojo");
            for (int i = 0; i < mojos.getLength(); i++) {
                Element mojo = (Element) mojos.item(i);
                String mojoGoal = childText(mojo, "goal");
                Element configuration = child(mojo, "configuration");
                if (mojoGoal == null || configuration == null) {
                    continue;
                }
                Map<String, String[]> parameters = new HashMap<>();
                for (Node n = configuration.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (n instanceof Element) {
                        Element e = (Element) n;
                        String defaultValue = e.hasAttribute("default-value") ? e.getAttribute("default-value") : null;
                        String text = e.getTextContent();
                        parameters.put(e.getTagName(), new String[] {defaultValue, text.isEmpty() ? null : text});
                    }
                }
                result.put(mojoGoal, parameters);
            }
        } catch (javax.xml.parsers.ParserConfigurationException | SAXException e) {
            throw new IOException("Cannot read the configuration of " + PLUGIN_DESCRIPTOR, e);
        }
        return result;
    }

    private static Element child(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && name.equals(((Element) n).getTagName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static String childText(Element parent, String name) {
        Element e = child(parent, name);
        return e != null ? e.getTextContent() : null;
    }

    /**
     * Method for parsing the plugin parameter
     *
     * @return Plugin info containing information about the plugin whose description is to be retrieved
     * @throws MojoException if <code>plugin<*code> parameter is not conform to
     *                              <code>groupId:artifactId[:version]</code>
     */
    private PluginInfo parsePluginLookupInfo() throws MojoException {
        PluginInfo pi = new PluginInfo();
        if (plugin != null && !plugin.isEmpty()) {
            if (plugin.indexOf(':') > -1) {
                String[] pluginParts = plugin.split(":");

                switch (pluginParts.length) {
                    case 1:
                        pi.setPrefix(pluginParts[0]);
                        break;
                    case 2:
                        pi.setGroupId(pluginParts[0]);
                        pi.setArtifactId(pluginParts[1]);
                        break;
                    case 3:
                        pi.setGroupId(pluginParts[0]);
                        pi.setArtifactId(pluginParts[1]);
                        pi.setVersion(pluginParts[2]);
                        break;
                    default:
                        throw new MojoException("plugin parameter must be a plugin prefix,"
                                + " or conform to: 'groupId:artifactId[:version]'.");
                }
            } else {
                pi.setPrefix(plugin);
            }
        } else {
            pi.setGroupId(groupId);
            pi.setArtifactId(artifactId);
            pi.setVersion(version);
        }
        return pi;
    }

    /**
     * Method for retrieving the plugin description
     *
     * @param loaded contains the plugin description
     * @param buffer contains the information to be displayed or printed
     * @throws MojoException if any
     */
    private void describePlugin(LoadedPlugin loaded, StringBuilder buffer) throws MojoException {
        PluginDescriptor pd = loaded.descriptor;
        append(buffer, pd.getId(), 0);
        buffer.append(LS);

        String name = pd.getName();
        if (name == null) {
            // Can be null because of MPLUGIN-137 (and descriptors generated with maven-plugin-tools-api <= 2.4.3)
            try {
                Path pom = resolveArtifactPath(
                        new Coordinates(pd.getGroupId(), pd.getArtifactId(), pd.getVersion()),
                        "pom",
                        session.getService(ProjectManager.class).getRemotePluginRepositories(project));
                name = readProjectName(pom);
            } catch (Exception e) {
                // oh well, we tried our best.
                log.warn("Unable to get the name of the plugin " + pd.getId() + ": " + e.getMessage());
                name = pd.getId();
            }
        }
        append(buffer, "Name", strong(name), 0);
        appendAsParagraph(buffer, "Description", toDescription(pd.getDescription()), 0);
        append(buffer, "Group Id", pd.getGroupId(), 0);
        append(buffer, "Artifact Id", pd.getArtifactId(), 0);
        append(buffer, "Version", pd.getVersion(), 0);
        append(buffer, "Goal Prefix", strong(pd.getGoalPrefix()), 0);
        buffer.append(LS);

        List<MojoDescriptor> mojos = pd.getMojos();

        if (mojos == null || mojos.isEmpty()) {
            append(buffer, "This plugin has no goals.", 0);
            return;
        }

        if (!minimal) {
            append(buffer, "This plugin has " + mojos.size() + " goal" + (mojos.size() > 1 ? "s" : "") + ":", 0);
            buffer.append(LS);

            mojos = mojos.stream()
                    .sorted((m1, m2) -> m1.getGoal().compareToIgnoreCase(m2.getGoal()))
                    .collect(Collectors.toList());

            for (MojoDescriptor md : mojos) {
                describeMojoGuts(loaded, md, buffer, detail);
                buffer.append(LS);
            }
        }

        if (!detail) {
            buffer.append("For more information, run 'mvn help:describe [...] -Ddetail'");
            buffer.append(LS);
        }
    }

    /**
     * Displays information about the Plugin Mojo
     *
     * @param loaded the plugin descriptor
     * @param md     contains the description of the Plugin Mojo
     * @param buffer the displayed output
     * @throws MojoException if any
     */
    private void describeMojo(LoadedPlugin loaded, MojoDescriptor md, StringBuilder buffer) throws MojoException {
        buffer.append("Mojo: '").append(fullGoalName(loaded, md)).append("'");
        buffer.append(LS);

        describeMojoGuts(loaded, md, buffer, detail);
        buffer.append(LS);

        if (!detail) {
            buffer.append("For more information, run 'mvn help:describe [...] -Ddetail'");
            buffer.append(LS);
        }
    }

    /**
     * Reads the name of the project of a POM. The POM is built, which has the name of its parent when it has none,
     * unless it is one of the old POMs that Maven 4 cannot build: then its name is the one the file declares.
     */
    private String readProjectName(Path pom) {
        try {
            return session.getService(ProjectBuilder.class)
                    .build(ProjectBuilderRequest.builder()
                            .session(session)
                            .path(pom)
                            .build())
                    .getProject()
                    .orElseThrow(() -> new MojoException("No project was built for " + pom))
                    .getModel()
                    .getName();
        } catch (RuntimeException e) {
            log.debug("Cannot build " + pom + ", reading its name", e);
            return session.getService(ModelXmlFactory.class).read(pom, false).getName();
        }
    }

    private static String fullGoalName(LoadedPlugin loaded, MojoDescriptor md) {
        return loaded.descriptor.getGoalPrefix() + ":" + md.getGoal();
    }

    /**
     * Displays detailed information about the Plugin Mojo
     *
     * @param loaded          the plugin descriptor
     * @param md              contains the description of the Plugin Mojo
     * @param buffer          contains information to be printed or displayed
     * @param fullDescription specifies whether all the details about the Plugin Mojo is to  be displayed
     * @throws MojoException if any
     */
    private void describeMojoGuts(LoadedPlugin loaded, MojoDescriptor md, StringBuilder buffer, boolean fullDescription)
            throws MojoException {
        append(buffer, strong(fullGoalName(loaded, md)), 0);

        // indent 1
        appendAsParagraph(buffer, "Description", toDescription(md.getDescription()), 1);

        String deprecation = md.getDeprecated();
        if (deprecation != null && deprecation.length() <= 0) {
            deprecation = NO_REASON;
        }

        if (deprecation != null) {
            append(buffer, warning("Deprecated. " + deprecation), 1);
        }

        if (isReportGoal(loaded, md)) {
            append(buffer, "Note", "This goal should be used as a Maven report.", 1);
        }

        if (!fullDescription) {
            return;
        }

        append(buffer, "Implementation", md.getImplementation(), 1);
        append(buffer, "Language", md.getLanguage(), 1);

        String phase = md.getPhase();
        if (phase != null && !phase.isEmpty()) {
            append(buffer, "Bound to phase", phase, 1);
        }

        String eGoal = md.getExecuteGoal();
        String eLife = md.getExecuteLifecycle();
        String ePhase = md.getExecutePhase();

        if ((eGoal != null && !eGoal.isEmpty()) || (ePhase != null && !ePhase.isEmpty())) {
            append(buffer, "Before this goal executes, it will call:", 1);

            if (eGoal != null && !eGoal.isEmpty()) {
                append(buffer, "Single goal", "'" + eGoal + "'", 2);
            }

            if (ePhase != null && !ePhase.isEmpty()) {
                String s = "Phase: '" + ePhase + "'";

                if (eLife != null && !eLife.isEmpty()) {
                    s += " in Lifecycle Overlay: '" + eLife + "'";
                }

                append(buffer, s, 2);
            }
        }

        buffer.append(LS);

        describeMojoParameters(loaded, md, buffer);
    }

    /**
     * Displays parameter information of the Plugin Mojo
     *
     * @param loaded the plugin descriptor
     * @param md     contains the description of the Plugin Mojo
     * @param buffer contains information to be printed or displayed
     * @throws MojoException if any
     */
    private void describeMojoParameters(LoadedPlugin loaded, MojoDescriptor md, StringBuilder buffer)
            throws MojoException {
        List<org.apache.maven.api.plugin.descriptor.Parameter> params = md.getParameters();

        if (params == null || params.isEmpty()) {
            append(buffer, "This mojo doesn't use any parameters.", 1);
            return;
        }

        params = params.stream()
                .sorted((p1, p2) -> p1.getName().compareToIgnoreCase(p2.getName()))
                .collect(Collectors.toList());

        append(buffer, "Available parameters:", 1);

        // indent 2
        for (org.apache.maven.api.plugin.descriptor.Parameter parameter : params) {
            if (!parameter.isEditable()) {
                continue;
            }

            buffer.append(LS);

            String defaultVal = loaded.defaultValue(md, parameter);
            if (defaultVal != null && !defaultVal.isEmpty()) {
                defaultVal = " (Default: " + strong(defaultVal) + ")";
            } else {
                defaultVal = "";
            }
            append(buffer, strong(parameter.getName()) + defaultVal, 2);

            String alias = parameter.getAlias();
            if (!(alias == null || alias.isEmpty())) {
                append(buffer, "Alias", alias, 3);
            }

            if (parameter.isRequired()) {
                append(buffer, "Required", "true", 3);
            }

            String expression = loaded.expression(md, parameter);
            if (expression != null && !expression.isEmpty()) {
                Matcher matcher = EXPRESSION.matcher(expression);
                if (matcher.matches()) {
                    append(buffer, "User property", matcher.group(1), 3);
                } else {
                    append(buffer, "Expression", expression, 3);
                }
            }

            append(buffer, toDescription(parameter.getDescription()), 3);

            String deprecation = parameter.getDeprecated();
            if (deprecation != null && deprecation.length() <= 0) {
                deprecation = NO_REASON;
            }

            if (deprecation != null) {
                append(buffer, warning("Deprecated. " + deprecation), 3);
            }
        }
    }

    /**
     * Describe the <code>cmd</code> parameter
     *
     * @param descriptionBuffer not null
     * @return <code>true</code> if it implies to describe a plugin, <code>false</code> otherwise.
     * @throws MojoException if any
     */
    private boolean describeCommand(StringBuilder descriptionBuffer) throws MojoException {
        if (cmd.indexOf(':') == -1) {
            // phase
            LifecycleRegistry lifecycleRegistry = session.getService(LifecycleRegistry.class);
            Lifecycle lifecycle = null;
            // the phase, by its Maven 4 name: the Maven 3 names of the phases, like process-resources, are aliases
            String phaseName = cmd;
            for (Lifecycle candidate : lifecycleRegistry) {
                if (lifecycleRegistry.computePhases(candidate).contains(cmd)) {
                    lifecycle = candidate;
                    break;
                }
                for (Lifecycle.Alias alias : candidate.aliases()) {
                    if (alias.v3Phase().equals(cmd)) {
                        lifecycle = candidate;
                        phaseName = alias.v4Phase();
                        break;
                    }
                }
                if (lifecycle != null) {
                    break;
                }
            }
            if (lifecycle == null) {
                throw new MojoException("The given phase '" + cmd + "' is an unknown phase.");
            }

            List<String> phases = lifecycleRegistry.computePhases(lifecycle);

            // the bindings of the lifecycle itself, like the ones of the clean and site lifecycles
            Map<String, List<String>> lifecycleBindings = new HashMap<>();
            lifecycle.allPhases().forEach(p -> {
                if (!p.plugins().isEmpty()) {
                    lifecycleBindings.put(p.name(), goals(p.plugins(), p.name()));
                }
            });

            if (lifecycleBindings.isEmpty()) {
                // the bindings of the packaging of the project
                PluginContainer packagingPlugins =
                        project.getPackaging().plugins().get(lifecycle.id());
                Map<String, List<String>> defaultLifecyclePhases = new HashMap<>();
                for (String key : phases) {
                    List<String> goals = packagingPlugins != null
                            ? ListPackagingMojo.getGoals(packagingPlugins, lifecycle, key)
                            : List.of();
                    if (!goals.isEmpty()) {
                        defaultLifecyclePhases.put(key, goals);
                    }
                }

                descriptionBuffer.append("'").append(cmd);
                descriptionBuffer
                        .append("' is a phase corresponding to this plugin:")
                        .append(LS);
                for (String key : phases) {
                    if (!key.equals(phaseName)) {
                        continue;
                    }
                    if (defaultLifecyclePhases.get(key) != null) {
                        descriptionBuffer.append(String.join(", ", defaultLifecyclePhases.get(key)));
                        descriptionBuffer.append(LS);
                    }
                }

                descriptionBuffer.append(LS);
                descriptionBuffer.append("It is a part of the lifecycle for the POM packaging '");
                descriptionBuffer.append(project.getPackaging().id());
                descriptionBuffer.append("'. This lifecycle includes the following phases:");
                descriptionBuffer.append(LS);
                for (String key : phases) {
                    descriptionBuffer.append("* ").append(key).append(": ");
                    List<String> goals = defaultLifecyclePhases.get(key);
                    if (goals != null && !goals.isEmpty()) {
                        descriptionBuffer.append(String.join(", ", goals)).append(LS);
                    } else {
                        descriptionBuffer.append(NOT_DEFINED).append(LS);
                    }
                }
            } else {
                descriptionBuffer.append("'").append(cmd);
                descriptionBuffer.append("' is a phase within the '").append(lifecycle.id());
                descriptionBuffer.append("' lifecycle, which has the following phases: ");
                descriptionBuffer.append(LS);

                for (String key : phases) {
                    descriptionBuffer.append("* ").append(key).append(": ");
                    if (lifecycleBindings.get(key) != null) {
                        descriptionBuffer
                                .append(String.join(", ", lifecycleBindings.get(key)))
                                .append(LS);
                    } else {
                        descriptionBuffer.append(NOT_DEFINED).append(LS);
                    }
                }
            }
            return false;
        }

        // goals: prefix:goal, groupId:artifactId:goal or groupId:artifactId:version:goal
        String[] parts = cmd.split(":");
        switch (parts.length) {
            case 2:
                plugin = parts[0];
                break;
            case 3:
                plugin = parts[0] + ":" + parts[1];
                break;
            case 4:
                plugin = parts[0] + ":" + parts[1] + ":" + parts[2];
                break;
            default:
                throw new MojoException("Unable to get descriptor for " + cmd);
        }
        goal = parts[parts.length - 1];
        descriptionBuffer
                .append("'")
                .append(cmd)
                .append("' is a plugin goal (aka mojo)")
                .append(".");
        descriptionBuffer.append(LS);

        return true;
    }

    /**
     * @param plugins the plugins bound to a phase of a lifecycle
     * @param phase the phase
     * @return the goals of the plugins, as <code>groupId:artifactId[:version]:goal</code>
     */
    private static List<String> goals(List<Plugin> plugins, String phase) {
        List<String> goals = new ArrayList<>();
        for (Plugin p : plugins) {
            for (PluginExecution execution : p.getExecutions()) {
                for (String g : execution.getGoals()) {
                    goals.add(p.getGroupId() + ":" + p.getArtifactId()
                            + (p.getVersion() != null ? ":" + p.getVersion() : "") + ":" + g);
                }
            }
        }
        return goals;
    }

    /**
     * Splits the specified text into lines of convenient display length.
     *
     * @param text       The text to split into lines, must not be <code>null</code>.
     * @param indent     The base indentation level of each line, must not be negative.
     * @param indentSize The size of each indentation, must not be negative.
     * @param lineLength The length of the line, must not be negative.
     * @return The sequence of display lines, never <code>null</code>.
     * @throws NegativeArraySizeException if <code>indent &lt; 0</code>
     */
    private static List<String> toLines(String text, int indent, int indentSize, int lineLength) {
        List<String> lines = new ArrayList<>();

        String ind = "\t".repeat(indent);

        String[] plainLines = text.split("(\r\n)|(\r)|(\n)");

        for (String plainLine : plainLines) {
            toLines(lines, ind + plainLine, indentSize, lineLength);
        }

        return lines;
    }

    /**
     * Adds the specified line to the output sequence, performing line wrapping if necessary.
     *
     * @param lines      The sequence of display lines, must not be <code>null</code>.
     * @param line       The line to add, must not be <code>null</code>.
     * @param indentSize The size of each indentation, must not be negative.
     * @param lineLength The length of the line, must not be negative.
     */
    private static void toLines(List<String> lines, String line, int indentSize, int lineLength) {
        int lineIndent = getIndentLevel(line);
        StringBuilder buf = new StringBuilder(256);

        String[] tokens = line.split(" +");

        for (String token : tokens) {
            if (buf.length() > 0) {
                if (buf.length() + token.length() >= lineLength) {
                    lines.add(buf.toString());
                    buf.setLength(0);
                    buf.append(" ".repeat(lineIndent * indentSize));
                } else {
                    buf.append(' ');
                }
            }

            for (int j = 0; j < token.length(); j++) {
                char c = token.charAt(j);
                if (c == '\t') {
                    buf.append(" ".repeat(indentSize - buf.length() % indentSize));
                } else if (c == ' ') {
                    buf.append(' ');
                } else {
                    buf.append(c);
                }
            }
        }
        lines.add(buf.toString());
    }

    /**
     * Gets the indentation level of the specified line.
     *
     * @param line The line whose indentation level should be retrieved, must not be <code>null</code>.
     * @return The indentation level of the line.
     */
    private static int getIndentLevel(String line) {
        int level = 0;
        for (int i = 0; i < line.length() && line.charAt(i) == '\t'; i++) {
            level++;
        }
        for (int i = level + 1; i <= level + 4 && i < line.length(); i++) {
            if (line.charAt(i) == '\t') {
                level++;
                break;
            }
        }
        return level;
    }

    /**
     * Append a description to the buffer by respecting the indentSize and lineLength parameters.
     * <b>Note</b>: The last character is always a new line.
     *
     * @param sb          The buffer to append the description, not <code>null</code>.
     * @param description The description, not <code>null</code>.
     * @param indent      The base indentation level of each line, must not be negative.
     */
    private static void append(StringBuilder sb, String description, int indent) {
        if (description == null || description.isEmpty()) {
            sb.append(UNKNOWN).append(LS);
            return;
        }

        for (String line : toLines(description, indent, INDENT_SIZE, LINE_LENGTH)) {
            sb.append(line).append(LS);
        }
    }

    /**
     * Append a description to the buffer by respecting the indentSize and lineLength parameters.
     * <b>Note</b>: The last character is always a new line.
     *
     * @param sb     The buffer to append the description, not <code>null</code>.
     * @param key    The key, not <code>null</code>.
     * @param value  The value associated to the key, could be <code>null</code>.
     * @param indent The base indentation level of each line, must not be negative.
     */
    private static void append(StringBuilder sb, String key, String value, int indent) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Key is required!");
        }

        if (value == null || value.isEmpty()) {
            value = UNKNOWN;
        }

        String description = key + ": " + value;
        for (String line : toLines(description, indent, INDENT_SIZE, LINE_LENGTH)) {
            sb.append(line).append(LS);
        }
    }

    /**
     * Append a description to the buffer by respecting the indentSize and lineLength parameters for the first line,
     * and append the next lines with <code>indent + 1</code> like a paragraph.
     * <b>Note</b>: The last character is always a new line.
     *
     * @param sb     The buffer to append the description, not <code>null</code>.
     * @param key    The key, not <code>null</code>.
     * @param value  The value, could be <code>null</code>.
     * @param indent The base indentation level of each line, must not be negative.
     */
    private static void appendAsParagraph(StringBuilder sb, String key, String value, int indent) {
        if (value == null || value.isEmpty()) {
            value = UNKNOWN;
        }

        String description;
        if (key == null) {
            description = value;
        } else {
            description = key + ": " + value;
        }

        List<String> l1 = toLines(description, indent, INDENT_SIZE, LINE_LENGTH - INDENT_SIZE);
        List<String> l2 = toLines(description, indent + 1, INDENT_SIZE, LINE_LENGTH);
        l2.set(0, l1.get(0));
        for (String line : l2) {
            sb.append(line).append(LS);
        }
    }

    /**
     * Determines if this Mojo should be used as a report or not. This resolves the plugin along with all of its
     * transitive dependencies to determine if the Java class of this goal implements <code>MavenReport</code>.
     *
     * @param loaded the plugin descriptor
     * @param md Mojo descriptor
     * @return Whether or not this goal should be used as a report.
     */
    private boolean isReportGoal(LoadedPlugin loaded, MojoDescriptor md) {
        PluginDescriptor pd = loaded.descriptor;
        try {
            List<RemoteRepository> repositories =
                    session.getService(ProjectManager.class).getRemotePluginRepositories(project);
            Coordinates coordinates = new Coordinates(pd.getGroupId(), pd.getArtifactId(), pd.getVersion());
            Path jar = resolveArtifactPath(coordinates, "jar", repositories);
            List<URL> urls = new ArrayList<>();
            urls.add(jar.toUri().toURL());
            List<Path> classpath;
            try {
                Path pom = resolveArtifactPath(coordinates, "pom", repositories);
                Project pluginProject = session.getService(ProjectBuilder.class)
                        .build(ProjectBuilderRequest.builder()
                                .session(session)
                                .path(pom)
                                .build())
                        .getProject()
                        .orElseThrow(() -> new MojoException("No project was built for " + pom));
                // the compile classpath of the plugin: it has the Maven API the goal implements or extends
                classpath = session.resolveDependencies(pluginProject, PathScope.MAIN_COMPILE);
            } catch (RuntimeException e) {
                // an old POM that Maven 4 cannot build: the runtime dependencies are all there is
                log.debug("Cannot build the POM of " + pd.getId() + ", using its runtime dependencies", e);
                classpath = session.resolveDependencies(
                        session.createDependencyCoordinates(session.createArtifactCoordinates(
                                pd.getGroupId(), pd.getArtifactId(), pd.getVersion(), "jar")));
            }
            for (Path path : classpath) {
                urls.add(path.toUri().toURL());
            }
            try (URLClassLoader classLoader =
                    new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader())) {
                return MavenReport.class.isAssignableFrom(Class.forName(md.getImplementation(), false, classLoader));
            }
        } catch (Exception | LinkageError e) {
            log.warn("Couldn't identify if this goal is a report goal: " + e.getMessage());
            return false;
        }
    }

    /**
     * Gets the effective string to use for the plugin/mojo/parameter description.
     *
     * @param description The description of the element, may be <code>null</code>.
     * @return The effective description string, never <code>null</code>.
     */
    private static String toDescription(String description) {
        if (description != null && !description.isEmpty()) {
            return new HtmlToPlainTextConverter().convert(description);
        }

        return "(no description available)";
    }

    /**
     * Class to wrap Plugin information.
     */
    static class PluginInfo {
        private String prefix;

        private String groupId;

        private String artifactId;

        private String version;

        /**
         * @return the prefix
         */
        public String getPrefix() {
            return prefix;
        }

        /**
         * @param prefix the prefix to set
         */
        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }

        /**
         * @return the groupId
         */
        public String getGroupId() {
            return groupId;
        }

        /**
         * @param groupId the groupId to set
         */
        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        /**
         * @return the artifactId
         */
        public String getArtifactId() {
            return artifactId;
        }

        /**
         * @param artifactId the artifactId to set
         */
        public void setArtifactId(String artifactId) {
            this.artifactId = artifactId;
        }

        /**
         * @return the version
         */
        public String getVersion() {
            return version;
        }

        /**
         * @param version the version to set
         */
        public void setVersion(String version) {
            this.version = version;
        }
    }
}
