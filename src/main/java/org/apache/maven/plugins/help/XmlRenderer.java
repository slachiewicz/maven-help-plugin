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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Writes an object of the Maven API as XML, for the <code>evaluate</code> goal: a bean is an element named after its
 * class with an element for each of its properties, a collection has an element for each item, and a map has an
 * element for each entry.
 */
final class XmlRenderer {

    private static final int MAX_DEPTH = 12;

    /** Getters that are not properties of the model. */
    private static final Set<String> IGNORED = Set.of(
            "getClass",
            "getLocation",
            "getLocations",
            "getLocationKeys",
            "getImportedFrom",
            "getModelEncoding",
            "getNamespaceUri");

    private XmlRenderer() {}

    /**
     * @param alias the name of the root element for a collection or map, or {@code null} to name it after the items
     * @param value not null
     * @return the XML
     */
    static String toXml(String alias, Object value) {
        StringBuilder sb = new StringBuilder();
        Set<Object> path = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        write(sb, alias != null ? alias : name(value), value, 0, path);
        return sb.toString();
    }

    /**
     * @param value a collection
     * @return the name of the element of a list: the plural of the simple name of its first item
     */
    static String pluralName(Collection<?> value) {
        if (value.isEmpty()) {
            return null;
        }
        String name = name(value.iterator().next());
        if (name.endsWith("y")) {
            return name.substring(0, name.length() - 1) + "ies";
        } else if (name.endsWith("s")) {
            return name;
        } else {
            return name + "s";
        }
    }

    private static String name(Object value) {
        if (value instanceof Collection) {
            String plural = pluralName((Collection<?>) value);
            return plural != null ? plural : "list";
        }
        String simple = value.getClass().getSimpleName();
        if (value instanceof Map) {
            return "map";
        }
        if (simple.isEmpty()) {
            simple = "object";
        }
        return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
    }

    private static void write(StringBuilder sb, String name, Object value, int depth, Set<Object> path) {
        String indent = "  ".repeat(depth);
        if (value == null) {
            return;
        }
        if (isSimple(value)) {
            sb.append(indent)
                    .append('<')
                    .append(name)
                    .append('>')
                    .append(escape(value.toString()))
                    .append("</")
                    .append(name)
                    .append(">\n");
            return;
        }
        if (depth > MAX_DEPTH || !path.add(value)) {
            return;
        }
        try {
            sb.append(indent).append('<').append(name).append(">\n");
            if (value instanceof Collection) {
                for (Object item : (Collection<?>) value) {
                    write(sb, item == null ? "null" : name(item), item, depth + 1, path);
                }
            } else if (value instanceof Map) {
                for (Map.Entry<?, ?> entry : new TreeMap<>((Map<?, ?>) value).entrySet()) {
                    write(sb, String.valueOf(entry.getKey()), entry.getValue(), depth + 1, path);
                }
            } else {
                for (Method getter : getters(value.getClass())) {
                    Object property;
                    try {
                        property = getter.invoke(value);
                    } catch (IllegalAccessException | InvocationTargetException e) {
                        continue;
                    }
                    String propertyName = getter.getName().startsWith("is")
                            ? getter.getName().substring(2)
                            : getter.getName().substring(3);
                    write(
                            sb,
                            Character.toLowerCase(propertyName.charAt(0)) + propertyName.substring(1),
                            property,
                            depth + 1,
                            path);
                }
            }
            sb.append(indent).append("</").append(name).append(">\n");
        } finally {
            path.remove(value);
        }
    }

    private static boolean isSimple(Object value) {
        return value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Enum
                || value instanceof Path
                || value instanceof java.io.File;
    }

    private static List<Method> getters(Class<?> type) {
        List<Method> getters = new ArrayList<>();
        Class<?> publicType = type;
        // the methods must be invoked through a public type
        while (publicType != null && !Modifier.isPublic(publicType.getModifiers())) {
            publicType = publicType.getSuperclass();
        }
        if (publicType == null) {
            return getters;
        }
        for (Method m : publicType.getMethods()) {
            String n = m.getName();
            if (m.getParameterCount() == 0
                    && !Modifier.isStatic(m.getModifiers())
                    && m.getReturnType() != void.class
                    && !IGNORED.contains(n)
                    && ((n.startsWith("get") && n.length() > 3)
                            || (n.startsWith("is") && n.length() > 2 && m.getReturnType() == boolean.class))) {
                getters.add(m);
            }
        }
        getters.sort((a, b) -> a.getName().compareTo(b.getName()));
        return getters;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
