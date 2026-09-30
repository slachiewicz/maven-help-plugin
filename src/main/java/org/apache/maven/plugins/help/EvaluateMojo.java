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
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;

import org.apache.maven.api.MojoExecution;
import org.apache.maven.api.Project;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.Prompter;
import org.apache.maven.api.services.PrompterException;
import org.apache.maven.api.services.xml.ModelXmlFactory;
import org.apache.maven.api.services.xml.SettingsXmlFactory;
import org.apache.maven.api.settings.Settings;

/**
 * Evaluates Maven expressions given by the user in an interactive mode.
 *
 * @author <a href="mailto:vincent.siveton@gmail.com">Vincent Siveton</a>
 * @since 2.1
 */
@Mojo(name = "evaluate", projectRequired = false)
public class EvaluateMojo extends AbstractHelpMojo {

    // ----------------------------------------------------------------------
    // Mojo parameters
    // ----------------------------------------------------------------------

    // we need to hide the 'output' defined in AbstractHelpMojo to have a correct "since".
    /**
     * Optional parameter to write the output of this help in a given file, instead of writing to the console.
     * This parameter will be ignored if no <code>expression</code> is specified.
     * <br/>
     * <b>Note</b>: Could be a relative path.
     *
     * @since 3.0.0
     */
    @Parameter(property = "output")
    private Path output;

    /**
     * This options gives the option to output information in cases where the output has been suppressed by using
     * <code>-q</code> (quiet option) in Maven. This is useful if you like to use
     * <code>maven-help-plugin:evaluate</code> in a script call (for example in bash) like this:
     *
     * <pre>
     * RESULT=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
     * echo $RESULT
     * </pre>
     *
     * This will only printout the information which has been requested by <code>expression</code> to
     * <code>stdout</code>.
     *
     * @since 3.1.0
     */
    @Parameter(property = "forceStdout", defaultValue = "false")
    private boolean forceStdout;

    /**
     * An artifact for evaluating Maven expressions. <br/>
     * <b>Note</b>: Should respect the Maven format, i.e. <code>groupId:artifactId[:version]</code>. The latest version
     * of the artifact will be used when no version is specified.
     */
    @Parameter(property = "artifact")
    private String artifact;

    /**
     * An expression to evaluate instead of prompting. Note that this <i>may not</i> include the surrounding ${...}.
     */
    @Parameter(property = "expression")
    private String expression;

    /**
     * The current mojo execution, used to create the expression evaluator.
     */
    @Inject
    private MojoExecution mojoExecution;

    // ----------------------------------------------------------------------
    // Instance variables
    // ----------------------------------------------------------------------

    /** lazy loading evaluator variable */
    private ExpressionEvaluator evaluator;

    // ----------------------------------------------------------------------
    // Mojo components
    // ----------------------------------------------------------------------

    /**
     * Prompter, needed for command line handling.
     */
    @Inject
    private Prompter prompter;

    // ----------------------------------------------------------------------
    // Public methods
    // ----------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    public void execute() throws MojoException {
        if (expression == null && !session.getSettings().isInteractiveMode()) {

            log.error("Maven is configured to NOT interact with the user for input. "
                    + "This Mojo requires that 'interactiveMode' in your settings file is flag to 'true'.");
            return;
        }

        validateParameters();

        if (artifact != null && !artifact.isEmpty()) {
            project = getMavenProject(artifact);
        }

        if (expression == null) {
            if (output != null) {
                log.warn(
                        "When prompting for input, the result will be written to the console, " + "ignoring 'output'.");
            }
            while (true) {
                try {
                    String userExpression =
                            prompter.prompt("Enter the Maven expression i.e. ${project.groupId} or 0 to exit?:");
                    if (userExpression == null
                            || userExpression.toLowerCase(Locale.ENGLISH).equals("0")) {
                        break;
                    }

                    handleResponse(userExpression, null);
                } catch (PrompterException e) {
                    throw new MojoException("Unable to read from standard input.", e);
                }
            }
        } else {
            if (!expression.contains("${")) {
                expression = "${" + expression + "}";
            }
            handleResponse(expression, output);
        }
    }

    // ----------------------------------------------------------------------
    // Private methods
    // ----------------------------------------------------------------------

    /**
     * Validate Mojo parameters.
     */
    private void validateParameters() {
        if (artifact == null) {
            // using project if found or super-pom
            log.info("No artifact parameter specified, using '" + project.getId() + "' as project.");
        }
    }

    /**
     * @return a lazy loading evaluator object.
     */
    private ExpressionEvaluator getEvaluator() {
        if (evaluator == null) {
            // the project may have been changed by the "artifact" parameter
            evaluator = new ExpressionEvaluator(session, project, mojoExecution);
        }

        return evaluator;
    }

    /**
     * @param expr the user expression asked.
     * @param output the file where to write the result, or <code>null</code> to print in standard output.
     * @throws MojoException if any
     */
    private void handleResponse(String expr, Path output) throws MojoException {
        StringBuilder response = new StringBuilder();

        Object obj = getEvaluator().evaluate(expr);

        if (obj != null && expr.equals(obj.toString())) {
            log.warn("The Maven expression was invalid. Please use a valid expression.");
            return;
        }

        // handle null
        if (obj == null) {
            response.append("null object or invalid expression");
        }
        // handle primitives objects
        else if (obj instanceof CharSequence
                || obj instanceof Boolean
                || obj instanceof Character
                || obj instanceof Number) {
            response.append(obj);
        }
        // handle specific objects
        else if (obj instanceof Path) {
            response.append(((Path) obj).toAbsolutePath());
        } else if (obj instanceof java.io.File) {
            response.append(((java.io.File) obj).getAbsolutePath());
        }
        // handle Maven pom object
        else if (obj instanceof Project) {
            response.append(writeModel(((Project) obj).getModel()));
        } else if (obj instanceof Model) {
            response.append(writeModel((Model) obj));
        }
        // handle Maven Settings object
        else if (obj instanceof Settings) {
            StringWriter sWriter = new StringWriter();
            try {
                session.getService(SettingsXmlFactory.class).write((Settings) obj, sWriter);
            } catch (RuntimeException e) {
                throw new MojoException("Error when writing settings", e);
            }

            response.append(sWriter);
        } else {
            // others Maven objects
            response.append(toXML(expr, obj));
        }

        if (output != null) {
            try {
                writeFile(output, response);
            } catch (IOException e) {
                throw new MojoException("Cannot write evaluation of expression to output: " + output, e);
            }
            log.info("Result of evaluation written to: " + output);
        } else {
            if (log.isInfoEnabled()) {
                log.info(LS + response);
            } else {
                if (forceStdout) {
                    System.out.print(response);
                    System.out.flush();
                }
            }
        }
    }

    private String writeModel(Model model) throws MojoException {
        StringWriter sWriter = new StringWriter();
        try {
            session.getService(ModelXmlFactory.class).write(model, sWriter);
        } catch (RuntimeException e) {
            throw new MojoException("Error when writing pom", e);
        }
        return sWriter.toString();
    }

    /**
     * @param expr the user expression.
     * @param obj a not null.
     * @return the XML for the given object.
     */
    private static String toXML(String expr, Object obj) {
        String alias = null;
        // beautify list
        if (obj instanceof Collection) {
            Collection<?> list = (Collection<?>) obj;
            if (list.isEmpty() && expr.indexOf('.') != -1 && expr.indexOf('}') > expr.indexOf('.')) {
                // try to detect the alias from question
                alias = expr.substring(expr.indexOf('.') + 1, expr.indexOf('}'));
            }
        } else if (obj instanceof Map) {
            alias = "map";
        }

        return XmlRenderer.toXml(alias, obj);
    }
}
