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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.maven.api.Lifecycle;
import org.apache.maven.api.Packaging;
import org.apache.maven.api.model.Plugin;
import org.apache.maven.api.model.PluginContainer;
import org.apache.maven.api.model.PluginExecution;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.services.LifecycleRegistry;
import org.apache.maven.api.services.PackagingRegistry;

/**
 * Displays the list of packaging that are supported by Maven.
 *
 * @since 3.5.2
 */
@Mojo(name = "list-packaging", projectRequired = false, aggregator = true)
public class ListPackagingMojo extends AbstractHelpMojo {

    /**
     * The packagings Maven itself defines. The Maven 4 API can look a packaging up by its id only, it cannot list
     * the registered ones, so these ids are probed.
     */
    static final List<String> KNOWN_PACKAGINGS =
            List.of("pom", "jar", "maven-plugin", "ejb", "ejb3", "war", "ear", "rar");

    // ----------------------------------------------------------------------
    // Public methods
    // ----------------------------------------------------------------------

    @Override
    public void execute() throws MojoException {
        try {
            LifecycleRegistry lifecycleRegistry = session.getService(LifecycleRegistry.class);
            PackagingRegistry packagingRegistry = session.getService(PackagingRegistry.class);

            Map<String, Packaging> packagings = new TreeMap<>();
            for (String id : KNOWN_PACKAGINGS) {
                packagingRegistry.lookup(id).ifPresent(p -> packagings.put(id, p));
            }

            StringBuilder descriptionBuffer = new StringBuilder();
            for (Map.Entry<String, Packaging> packagingEntry : packagings.entrySet()) {
                for (Map.Entry<String, PluginContainer> lifecycleEntry :
                        new TreeMap<>(packagingEntry.getValue().plugins()).entrySet()) {
                    descriptionBuffer
                            .append(packagingEntry.getKey())
                            .append(" (lifecycle: ")
                            .append(lifecycleEntry.getKey())
                            .append(")")
                            .append(LS);
                    Lifecycle lifecycle = lifecycleRegistry.require(lifecycleEntry.getKey());
                    for (String phase : lifecycleRegistry.computePhases(lifecycle)) {
                        List<String> goals = getGoals(lifecycleEntry.getValue(), lifecycle, phase);
                        if (!goals.isEmpty()) {
                            descriptionBuffer.append("  * ").append(phase).append(LS);
                            for (String goal : goals) {
                                descriptionBuffer.append("    - ").append(goal).append(LS);
                            }
                        }
                    }
                }
                descriptionBuffer.append(LS);
            }
            log.info(LS + "Maven packaging defined:" + LS + LS + descriptionBuffer);
            writeFile(output, descriptionBuffer);
        } catch (IOException e) {
            throw new MojoException(e);
        }
    }

    /**
     * @param container the plugins bound for one lifecycle of a packaging
     * @param lifecycle the lifecycle
     * @param phase a phase name of the lifecycle
     * @return the goals bound to the phase, as <code>groupId:artifactId[:version]:goal</code>
     */
    static List<String> getGoals(PluginContainer container, Lifecycle lifecycle, String phase) {
        // the packagings bind goals to the phases by their Maven 3 names, like process-resources
        Set<String> names = new HashSet<>();
        names.add(phase);
        for (Lifecycle.Alias alias : lifecycle.aliases()) {
            if (alias.v4Phase().equals(phase)) {
                names.add(alias.v3Phase());
            }
        }
        List<String> goals = new ArrayList<>();
        for (Plugin plugin : container.getPlugins()) {
            for (PluginExecution execution : plugin.getExecutions()) {
                if (names.contains(execution.getPhase())) {
                    for (String goal : execution.getGoals()) {
                        goals.add(plugin.getGroupId() + ":" + plugin.getArtifactId()
                                + (plugin.getVersion() != null ? ":" + plugin.getVersion() : "")
                                + ":" + goal);
                    }
                }
            }
        }
        return goals;
    }
}
