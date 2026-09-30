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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.maven.api.Project;
import org.apache.maven.api.model.Profile;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Displays a list of available profiles under the current project.
 * <br>
 * <b>Note</b>: it will list <b>all</b> profiles for a project. If a
 * profile comes up with a status <b>inactive</b> then there might be a need to
 * set profile activation switches/property.
 *
 * @author <a href="mailto:rahul.thakur.xdev@gmail.com">Rahul Thakur</a>
 * @since 2.1
 */
@Mojo(name = "all-profiles", projectRequired = false)
public class AllProfilesMojo extends AbstractHelpMojo {

    // ----------------------------------------------------------------------
    // Public methods
    // ----------------------------------------------------------------------

    @Override
    public void execute() throws MojoException {
        StringBuilder descriptionBuffer = new StringBuilder();

        for (Project project : session.getProjects()) {
            descriptionBuffer
                    .append("Listing Profiles for Project: ")
                    .append(project.getId())
                    .append(LS);

            Map<String, Profile> allProfilesByIds = new HashMap<>();
            Map<String, Profile> activeProfilesByIds = new HashMap<>();
            addSettingsProfiles(allProfilesByIds);
            addProjectPomProfiles(project, allProfilesByIds, activeProfilesByIds);

            // now display
            if (allProfilesByIds.isEmpty()) {
                log.warn("No profiles detected!");
            } else {
                // active Profiles will be a subset of *all* profiles
                allProfilesByIds.keySet().removeAll(activeProfilesByIds.keySet());

                writeProfilesDescription(descriptionBuffer, activeProfilesByIds, true);
                writeProfilesDescription(descriptionBuffer, allProfilesByIds, false);
            }
        }

        if (output != null) {
            try {
                writeFile(output, descriptionBuffer);
            } catch (IOException e) {
                throw new MojoException("Cannot write profiles description to output: " + output, e);
            }

            log.info("Wrote descriptions to: " + output);
        } else {
            log.info(descriptionBuffer.toString());
        }
    }

    // ----------------------------------------------------------------------
    // Private methods
    // ----------------------------------------------------------------------

    private void writeProfilesDescription(StringBuilder sb, Map<String, Profile> profilesByIds, boolean active) {
        for (Profile p : profilesByIds.values()) {
            sb.append("  Profile Id: ").append(p.getId());
            sb.append(" (Active: ")
                    .append(active)
                    .append(", Source: ")
                    .append(p.getSource())
                    .append(")");
            sb.append(LS);
        }
    }

    private void addProjectPomProfiles(
            Project project, Map<String, Profile> allProfiles, Map<String, Profile> activeProfiles) {
        if (project == null) {
            // shouldn't happen as this mojo requires a project
            log.debug("No pom.xml found to read Profiles from.");
            return;
        }

        log.debug("Attempting to read profiles from pom.xml...");

        while (project != null) {
            for (Profile profile : project.getDeclaredProfiles()) {
                allProfiles.put(profile.getId(), profile);
            }
            for (Profile profile : project.getEffectiveActiveProfiles()) {
                activeProfiles.put(profile.getId(), profile);
            }
            Optional<Project> parent = project.getParent();
            project = parent.orElse(null);
        }
    }

    private void addSettingsProfiles(Map<String, Profile> allProfiles) {
        log.debug("Attempting to read profiles from settings.xml...");
        for (org.apache.maven.api.settings.Profile settingsProfile :
                session.getSettings().getProfiles()) {
            Profile profile = Profile.newBuilder().id(settingsProfile.getId()).build();
            profile.setSource(Profile.SOURCE_SETTINGS);
            allProfiles.put(profile.getId(), profile);
        }
    }
}
