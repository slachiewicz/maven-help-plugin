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
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.TreeMap;

import org.apache.maven.api.JavaPathType;
import org.apache.maven.api.Type;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.services.TypeRegistry;

/**
 * Displays the list of dependency types that are defined in Maven.
 *
 * @since 3.5.2
 */
@Mojo(name = "list-dependency-types", projectRequired = false, aggregator = true)
public class ListDependencyTypesMojo extends AbstractHelpMojo {

    // ----------------------------------------------------------------------
    // Public methods
    // ----------------------------------------------------------------------

    @Override
    public void execute() throws MojoException {
        try {
            TypeRegistry typeRegistry = session.getService(TypeRegistry.class);
            Map<String, Type> types = new TreeMap<>();
            // The Maven 4 API can look a type up by its id only, it cannot list the registered ones:
            // probe the ids Maven itself defines, the constants of Type and the legacy ones.
            for (String id : knownTypeIds()) {
                typeRegistry.lookup(id).ifPresent(t -> types.put(id, t));
            }

            StringBuilder descriptionBuffer = new StringBuilder();
            for (Map.Entry<String, Type> typeEntry : types.entrySet()) {
                Type type = typeEntry.getValue();
                descriptionBuffer.append(typeEntry.getKey()).append(LS);
                descriptionBuffer
                        .append(" - Extension: ")
                        .append("*.")
                        .append(type.getExtension())
                        .append(LS);
                if (type.getClassifier() != null && !type.getClassifier().isEmpty()) {
                    descriptionBuffer
                            .append(" - Classifier: ")
                            .append(type.getClassifier())
                            .append(LS);
                }
                if (type.getPathTypes().contains(JavaPathType.CLASSES)
                        || type.getPathTypes().contains(JavaPathType.MODULES)) {
                    descriptionBuffer.append(" - Added to classpath").append(LS);
                }
                if (type.isIncludesDependencies()) {
                    descriptionBuffer.append(" - Includes dependencies").append(LS);
                }
                descriptionBuffer.append(LS);
            }
            if (output != null) {
                writeFile(output, descriptionBuffer);
            } else {
                log.info(LS + "Maven Dependency Types defined:" + LS + LS + descriptionBuffer);
            }
        } catch (IOException e) {
            throw new MojoException(e);
        }
    }

    private static Iterable<String> knownTypeIds() {
        Map<String, String> ids = new TreeMap<>();
        for (Field field : Type.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                try {
                    String id = (String) field.get(null);
                    ids.put(id, id);
                } catch (IllegalAccessException e) {
                    // not a constant we can read, skip it
                }
            }
        }
        for (String id : new String[] {"ejb", "ejb-client", "war", "ear", "rar", "par", "sources", "test-sources"}) {
            ids.put(id, id);
        }
        return ids.keySet();
    }
}
