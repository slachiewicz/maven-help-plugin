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
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.xml.SettingsXmlFactory;
import org.apache.maven.api.settings.Profile;
import org.apache.maven.api.settings.Proxy;
import org.apache.maven.api.settings.Server;
import org.apache.maven.api.settings.Settings;
import org.codehaus.plexus.util.xml.PrettyPrintXMLWriter;
import org.codehaus.plexus.util.xml.XMLWriter;
import org.codehaus.plexus.util.xml.XmlWriterUtil;

/**
 * Displays the calculated settings as XML for this project, given any profile enhancement and the inheritance
 * of the global settings into the user-level settings.
 *
 * @since 2.0
 */
@Mojo(name = "effective-settings", projectRequired = false)
public class EffectiveSettingsMojo extends AbstractEffectiveMojo {
    // ----------------------------------------------------------------------
    // Mojo parameters
    // ----------------------------------------------------------------------

    /**
     * For security reasons, all passwords are hidden by default. Set this to <code>true</code> to show all passwords.
     *
     * @since 2.1
     */
    @Parameter(property = "showPasswords", defaultValue = "false")
    private boolean showPasswords;

    // ----------------------------------------------------------------------
    // Public methods
    // ----------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    public void execute() throws MojoException {
        // The system settings for Maven. This is the instance resulting from
        // merging global and user-level settings files.
        Settings settings = session.getSettings();

        Settings copySettings = showPasswords ? settings : hidePasswords(settings);

        StringWriter w = new StringWriter();
        String encoding = output != null && copySettings != null
                ? copySettings.getModelEncoding()
                : System.getProperty("file.encoding");
        XMLWriter writer =
                new PrettyPrintXMLWriter(w, " ".repeat(XmlWriterUtil.DEFAULT_INDENTATION_SIZE), encoding, null);

        writeHeader(writer);

        writeEffectiveSettings(copySettings, writer);

        String effectiveSettings = prettyFormat(w.toString(), encoding, false);

        if (output != null) {
            try {
                writeXmlFile(output, effectiveSettings);
            } catch (IOException e) {
                throw new MojoException("Cannot write effective-settings to output: " + output, e);
            }

            log.info("Effective-settings written to: " + output);
        } else {
            log.info(LS + "Effective user-specific configuration settings:" + LS + LS + effectiveSettings + LS);
        }
    }

    // ----------------------------------------------------------------------
    // Private methods
    // ----------------------------------------------------------------------

    /**
     * Hide proxy and server passwords.
     *
     * @param settings could be {@code null}
     * @return a copy of the settings without passwords, or {@code null} if settings was {@code null}.
     */
    private static Settings hidePasswords(Settings settings) {
        if (settings == null) {
            return null;
        }

        List<Proxy> proxies = new ArrayList<>(settings.getProxies().size());
        for (Proxy proxy : settings.getProxies()) {
            if (proxy.getPassword() != null && !proxy.getPassword().isEmpty()) {
                proxy = Proxy.newBuilder(proxy, true).password("***").build();
            }
            proxies.add(proxy);
        }

        List<Server> servers = new ArrayList<>(settings.getServers().size());
        for (Server server : settings.getServers()) {
            Server.Builder builder = Server.newBuilder(server, true);
            // Password
            if (server.getPassword() != null && !server.getPassword().isEmpty()) {
                builder.password("***");
            }
            // Passphrase
            if (server.getPassphrase() != null && !server.getPassphrase().isEmpty()) {
                builder.passphrase("***");
            }
            servers.add(builder.build());
        }

        return Settings.newBuilder(settings, true)
                .proxies(proxies)
                .servers(servers)
                .build();
    }

    /**
     * Method for writing the effective settings informations.
     *
     * @param settings the settings, not null.
     * @param writer the XML writer used, not null.
     * @throws MojoException if any
     */
    private void writeEffectiveSettings(Settings settings, XMLWriter writer) throws MojoException {
        settings = cleanSettings(settings);

        StringWriter sWriter = new StringWriter();
        try {
            session.getService(SettingsXmlFactory.class).write(settings, sWriter);
        } catch (RuntimeException e) {
            throw new MojoException("Cannot serialize Settings to XML.", e);
        }

        // This removes the XML declaration written by the settings writer
        String effectiveSettings = prettyFormat(sWriter.toString(), null, true);

        writeComment(writer, "Effective Settings for '" + getUserName() + "' on '" + getHostName() + "'");

        writer.writeMarkup(effectiveSettings);
    }

    /**
     * Apply some logic to clean the settings before writing them.
     *
     * @param settings not null
     * @return the settings to write
     */
    private static Settings cleanSettings(Settings settings) {
        List<Profile> profiles = new ArrayList<>(settings.getProfiles().size());
        for (Profile profile : settings.getProfiles()) {
            profiles.add(Profile.newBuilder(profile, true)
                    .properties(new TreeMap<>(profile.getProperties()))
                    .build());
        }
        return Settings.newBuilder(settings, true).profiles(profiles).build();
    }

    /**
     * @return the current host name or <code>unknown</code> if error
     * @see InetAddress#getLocalHost()
     */
    private static String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }

    /**
     * @return the user name or <code>unknown</code> if <code>user.name</code> is not a system property.
     */
    private static String getUserName() {
        String userName = System.getProperty("user.name");
        if (userName == null || userName.isEmpty()) {
            return "unknown";
        }

        return userName;
    }
}
