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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.apache.maven.api.ArtifactCoordinates;
import org.apache.maven.api.Project;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.api.Session;
import org.apache.maven.api.Version;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.MessageBuilderFactory;
import org.apache.maven.api.services.ProjectBuilder;
import org.apache.maven.api.services.ProjectBuilderRequest;
import org.apache.maven.api.services.ProjectManager;

/**
 * Base class with some Help Mojo functionalities.
 *
 * @author <a href="mailto:vincent.siveton@gmail.com">Vincent Siveton</a>
 * @since 2.1
 */
public abstract class AbstractHelpMojo implements org.apache.maven.api.plugin.Mojo {

    /** The maximum length of a display line. */
    protected static final int LINE_LENGTH = 79;

    /** The line separator for the current OS. */
    protected static final String LS = System.lineSeparator();

    /**
     * Current Maven project.
     */
    @Inject
    protected Project project;

    /**
     * The current build session instance.
     */
    @Inject
    protected Session session;

    @Inject
    protected Log log;

    /**
     * Optional parameter to write the output of this help to a given file using UTF-8 encoding,
     * instead of writing to the console.
     * <br>
     * <b>Note</b>: Could be a relative path.
     */
    @Parameter(property = "output")
    protected Path output;

    /**
     * Utility method to write a content to a given file.
     *
     * @param output is the wanted output file.
     * @param content contains the content to be written to the file.
     * @throws IOException if any
     * @see #writeFile(Path, String)
     */
    protected static void writeFile(Path output, StringBuilder content) throws IOException {
        writeFile(output, content.toString());
    }

    /**
     * Utility method to write a content to a given file.
     *
     * @param output is the wanted output file.
     * @param content contains the content to be written to the file.
     * @throws IOException if any
     */
    protected static void writeFile(Path output, String content) throws IOException {
        if (output == null) {
            return;
        }

        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(output, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Coordinates of an artifact given on the command line: <code>groupId:artifactId[:version]</code>.
     */
    static final class Coordinates {
        final String groupId;
        final String artifactId;
        /** The version, or {@code null} when the latest version is wanted. */
        final String version;

        Coordinates(String groupId, String artifactId, String version) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
        }
    }

    /**
     * Parses the given String into GAV artifact coordinate information.
     *
     * @param artifactString should respect the format <code>groupId:artifactId[:version]</code>
     * @return the coordinates, the version is {@code null} if the latest version is wanted.
     * @throws MojoException if the <code>artifactString</code> doesn't respect the format.
     */
    protected static Coordinates parseCoordinates(String artifactString) throws MojoException {
        if (artifactString == null || artifactString.isEmpty()) {
            throw new IllegalArgumentException("artifact parameter could not be empty");
        }

        String[] artifactParts = artifactString.split(":");
        switch (artifactParts.length) {
            case 2:
                return new Coordinates(artifactParts[0], artifactParts[1], null);
            case 3:
                return new Coordinates(artifactParts[0], artifactParts[1], artifactParts[2]);
            default:
                throw new MojoException("The artifact parameter '" + artifactString + "' should be conform to: "
                        + "'groupId:artifactId[:version]'.");
        }
    }

    /**
     * Resolves the file of an artifact, the highest release is used when the version is not given.
     *
     * @param coordinates not null
     * @param extension the extension of the artifact, not null
     * @param repositories the repositories to use
     * @return the local file of the artifact
     */
    protected Path resolveArtifactPath(Coordinates coordinates, String extension, List<RemoteRepository> repositories) {
        String version = coordinates.version;
        if (version == null) {
            version = resolveLatestVersion(coordinates, repositories).toString();
        }
        ArtifactCoordinates artifactCoordinates =
                session.createArtifactCoordinates(coordinates.groupId, coordinates.artifactId, version, extension);
        return session.resolveArtifact(artifactCoordinates, repositories).getPath();
    }

    /**
     * Finds the latest version of an artifact, preferring releases over snapshots.
     */
    protected Version resolveLatestVersion(Coordinates coordinates, List<RemoteRepository> repositories) {
        ArtifactCoordinates range =
                session.createArtifactCoordinates(coordinates.groupId, coordinates.artifactId, "[0,)", "pom");
        List<Version> versions = session.resolveVersionRange(range, repositories);
        Optional<Version> release = versions.stream()
                .filter(v -> !session.isVersionSnapshot(v.toString()))
                .max(Version::compareTo);
        return release.or(() -> versions.stream().max(Version::compareTo))
                .orElseThrow(() -> new MojoException(
                        "No version found for '" + coordinates.groupId + ":" + coordinates.artifactId + "'"));
    }

    /**
     * Retrieves the Maven Project associated with the given artifact String, in the form of
     * <code>groupId:artifactId[:version]</code>. This resolves the POM artifact at those coordinates and then builds
     * the Maven project from it.
     *
     * @param artifactString Coordinates of the Maven project to get.
     * @return New Maven project.
     * @throws MojoException If there was an error while getting the Maven project.
     */
    protected Project getMavenProject(String artifactString) throws MojoException {
        try {
            Path pom = resolveArtifactPath(
                    parseCoordinates(artifactString),
                    "pom",
                    session.getService(ProjectManager.class).getRemoteProjectRepositories(project));

            return session.getService(ProjectBuilder.class)
                    .build(ProjectBuilderRequest.builder()
                            .session(session)
                            .path(pom)
                            .build())
                    .getProject()
                    .orElseThrow(() -> new MojoException("No project was built for " + pom));
        } catch (Exception e) {
            throw new MojoException(
                    "Unable to get the POM for the artifact '" + artifactString + "'. Verify the artifact parameter.",
                    e);
        }
    }

    /**
     * @param text the text to style
     * @return the text in a strong style if colors are enabled
     */
    protected String strong(String text) {
        return session.getService(MessageBuilderFactory.class)
                .builder()
                .strong(text)
                .build();
    }

    /**
     * @param text the text to style
     * @return the text in a warning style if colors are enabled
     */
    protected String warning(String text) {
        return session.getService(MessageBuilderFactory.class)
                .builder()
                .warning(text)
                .build();
    }
}
