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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.api.MojoExecution;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;

/**
 * Evaluates the Maven expressions of the <code>evaluate</code> goal.
 * <p>
 * Maven 4 has no public expression evaluator for plugins, so this navigates the public API objects of the
 * session: <code>project</code>, <code>session</code>, <code>settings</code>, <code>mojo</code>, <code>basedir</code>,
 * <code>rootDirectory</code>, <code>localRepository</code> and <code>reactorProjects</code> with the getters of the
 * objects (<code>a.b</code>, <code>list[0]</code> and <code>map(key)</code>), then the properties of the project, the
 * properties of the session and the environment variables (<code>env.NAME</code>).
 */
class ExpressionEvaluator {

    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{([^}]*)}");

    private static final Object NOT_FOUND = new Object();

    private final Session session;

    private final Project project;

    private final MojoExecution mojoExecution;

    ExpressionEvaluator(Session session, Project project, MojoExecution mojoExecution) {
        this.session = session;
        this.project = project;
        this.mojoExecution = mojoExecution;
    }

    /**
     * @param expression a string with <code>${...}</code> expressions
     * @return the value when the string is one expression, else the string with the evaluated expressions, where an
     * expression that cannot be evaluated is left as it is
     */
    Object evaluate(String expression) {
        if (expression == null) {
            return null;
        }
        Matcher matcher = EXPRESSION.matcher(expression);
        if (matcher.matches()) {
            Object value = resolve(matcher.group(1));
            return value == NOT_FOUND ? null : value;
        }
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            Object value = resolve(matcher.group(1));
            matcher.appendReplacement(
                    sb,
                    Matcher.quoteReplacement(value == NOT_FOUND || value == null ? matcher.group() : value.toString()));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private Object resolve(String expression) {
        String root = expression;
        String rest = null;
        int dot = expression.indexOf('.');
        if (dot >= 0) {
            root = expression.substring(0, dot);
            rest = expression.substring(dot + 1);
        }
        int bracket = firstIndexOf(root, '[', '(');
        String rootIndex = null;
        if (bracket >= 0) {
            rootIndex = root.substring(bracket);
            root = root.substring(0, bracket);
        }

        Object value = NOT_FOUND;
        switch (root) {
            case "project":
            case "executedProject":
                value = project;
                break;
            case "session":
                value = session;
                break;
            case "settings":
                value = session.getSettings();
                break;
            case "mojo":
            case "mojoExecution":
                value = mojoExecution;
                break;
            case "plugin":
                value = mojoExecution != null ? mojoExecution.getPlugin() : null;
                break;
            case "localRepository":
                value = session.getLocalRepository();
                break;
            case "reactorProjects":
                value = session.getProjects();
                break;
            case "basedir":
                value = project != null ? project.getBasedir() : null;
                break;
            case "rootDirectory":
                value = project != null ? project.getRootDirectory() : null;
                break;
            case "env":
                if (rest != null) {
                    String env = System.getenv(rest);
                    return env != null ? env : NOT_FOUND;
                }
                break;
            default:
                break;
        }
        if (value != NOT_FOUND) {
            if (rootIndex != null) {
                value = index(value, rootIndex);
            }
            return rest == null || value == NOT_FOUND || value == null ? value : navigate(value, rest);
        }
        return property(expression);
    }

    private Object property(String name) {
        Object value = project != null ? project.getModel().getProperties().get(name) : null;
        if (value == null) {
            value = session.getEffectiveProperties().get(name);
        }
        if (value == null) {
            value = System.getProperty(name);
        }
        return value != null ? value : NOT_FOUND;
    }

    private Object navigate(Object target, String path) {
        Object current = target;
        for (String token : split(path)) {
            if (current == null) {
                return null;
            }
            String index = null;
            int bracket = firstIndexOf(token, '[', '(');
            if (bracket >= 0) {
                index = token.substring(bracket);
                token = token.substring(0, bracket);
            }
            Object next = get(current, token);
            if (next == NOT_FOUND && current instanceof Project) {
                // the properties and the plain model elements of a project are the ones of its model
                next = get(((Project) current).getModel(), token);
            }
            if (next == NOT_FOUND) {
                return NOT_FOUND;
            }
            current = index != null ? index(next, index) : next;
        }
        return current;
    }

    /**
     * Splits a path at its dots, except the ones in a key: <code>a.b(c.d).e</code> is <code>a</code>,
     * <code>b(c.d)</code> and <code>e</code>.
     */
    private static List<String> split(String path) {
        List<String> tokens = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            } else if (c == '.' && depth == 0) {
                tokens.add(path.substring(start, i));
                start = i + 1;
            }
        }
        tokens.add(path.substring(start));
        return tokens;
    }

    private static int firstIndexOf(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        return i < 0 ? j : j < 0 ? i : Math.min(i, j);
    }

    private static Object index(Object value, String index) {
        if (value == null || index.length() < 3) {
            return NOT_FOUND;
        }
        String key = index.substring(1, index.length() - 1);
        if (index.charAt(0) == '[' && value instanceof List) {
            try {
                List<?> list = (List<?>) value;
                int i = Integer.parseInt(key);
                return i >= 0 && i < list.size() ? list.get(i) : NOT_FOUND;
            } catch (NumberFormatException e) {
                return NOT_FOUND;
            }
        }
        if (index.charAt(0) == '(' && value instanceof Map) {
            Object v = ((Map<?, ?>) value).get(key);
            return v != null ? v : NOT_FOUND;
        }
        return NOT_FOUND;
    }

    private static Object get(Object target, String name) {
        if (target instanceof Map) {
            Object v = ((Map<?, ?>) target).get(name);
            return v != null ? v : NOT_FOUND;
        }
        String method = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String prefix : new String[] {"get", "is"}) {
            Optional<Method> m = findMethod(target.getClass(), prefix + method);
            if (m.isPresent()) {
                try {
                    return m.get().invoke(target);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    return NOT_FOUND;
                }
            }
        }
        return NOT_FOUND;
    }

    /**
     * Finds a public method without parameters that can be invoked, looking at the public super types when the class
     * of the object is not public.
     */
    private static Optional<Method> findMethod(Class<?> type, String name) {
        if (type == null) {
            return Optional.empty();
        }
        if (Modifier.isPublic(type.getModifiers())) {
            try {
                Method m = type.getMethod(name);
                if (m.getReturnType() != void.class && m.getDeclaringClass() != Object.class) {
                    return Optional.of(m);
                }
            } catch (NoSuchMethodException e) {
                // look at the super types
            }
        }
        for (Class<?> i : type.getInterfaces()) {
            Optional<Method> m = findMethod(i, name);
            if (m.isPresent()) {
                return m;
            }
        }
        return findMethod(type.getSuperclass(), name);
    }
}
