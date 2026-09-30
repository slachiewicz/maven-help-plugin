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

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.plugins.help.AbstractHelpMojo.Coordinates;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the coordinate parsing in {@link AbstractHelpMojo#parseCoordinates(String)}.
 */
class AbstractHelpMojoTest {

    @Test
    void coordinatesWithoutAVersionAskForTheLatestVersion() throws Exception {
        Coordinates coordinates = AbstractHelpMojo.parseCoordinates("org.apache.maven:maven-core");

        assertEquals("org.apache.maven", coordinates.groupId);
        assertEquals("maven-core", coordinates.artifactId);
        // null, not an empty version: the version has to be resolved to the latest one
        assertNull(coordinates.version);
    }

    @Test
    void coordinatesWithAVersionKeepIt() throws Exception {
        Coordinates coordinates = AbstractHelpMojo.parseCoordinates("org.apache.maven:maven-core:3.9.16");

        assertEquals("3.9.16", coordinates.version);
    }

    @Test
    void coordinatesWithTooManyPartsAreRejected() {
        assertThrows(
                MojoException.class,
                () -> AbstractHelpMojo.parseCoordinates("org.apache.maven:maven-core:3.9.16:extra"));
    }

    @Test
    void emptyCoordinatesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AbstractHelpMojo.parseCoordinates(""));
    }
}
