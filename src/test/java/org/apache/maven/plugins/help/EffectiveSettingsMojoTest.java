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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.maven.api.Session;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.testing.InjectMojo;
import org.apache.maven.api.plugin.testing.MojoParameter;
import org.apache.maven.api.plugin.testing.MojoTest;
import org.apache.maven.api.services.xml.SettingsXmlFactory;
import org.apache.maven.api.settings.Profile;
import org.apache.maven.api.settings.Proxy;
import org.apache.maven.api.settings.Server;
import org.apache.maven.api.settings.Settings;
import org.apache.maven.impl.DefaultSettingsXmlFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.apache.maven.api.plugin.testing.MojoExtension.setVariableValueToObject;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test class for the effective-settings mojo of the Help Plugin.
 */
@MojoTest
class EffectiveSettingsMojoTest {

    private final Session session = mock(Session.class);

    @TempDir
    private Path tempDir;

    private Path output;

    @BeforeEach
    void setup() {
        Settings settings = Settings.newBuilder()
                .servers(List.of(Server.newBuilder()
                        .id("my-server")
                        .username("user")
                        .password("secret-password")
                        .passphrase("secret-passphrase")
                        .build()))
                .proxies(List.of(Proxy.newBuilder()
                        .id("my-proxy")
                        .host("proxy.example.org")
                        .password("proxy-password")
                        .build()))
                .profiles(List.of(Profile.newBuilder()
                        .id("my-profile")
                        .properties(Map.of("z", "1", "a", "2"))
                        .build()))
                .build();
        when(session.getSettings()).thenReturn(settings);
        when(session.getService(SettingsXmlFactory.class)).thenReturn(new DefaultSettingsXmlFactory());
        output = tempDir.resolve("effective-settings.xml");
    }

    private void prepare(EffectiveSettingsMojo mojo) throws Exception {
        setVariableValueToObject(mojo, "session", session);
        setVariableValueToObject(mojo, "log", mock(Log.class));
        setVariableValueToObject(mojo, "output", output);
    }

    @Test
    @InjectMojo(goal = "effective-settings")
    void passwordsAreHiddenByDefault(EffectiveSettingsMojo mojo) throws Exception {
        prepare(mojo);

        mojo.execute();

        String xml = Files.readString(output);
        assertTrue(xml.contains("<id>my-server</id>"), xml);
        assertTrue(xml.contains("<password>***</password>"), xml);
        assertTrue(xml.contains("<passphrase>***</passphrase>"), xml);
        assertFalse(xml.contains("secret-password"), xml);
        assertFalse(xml.contains("secret-passphrase"), xml);
        assertFalse(xml.contains("proxy-password"), xml);
    }

    @Test
    @InjectMojo(goal = "effective-settings")
    @MojoParameter(name = "showPasswords", value = "true")
    void passwordsAreShownOnRequest(EffectiveSettingsMojo mojo) throws Exception {
        prepare(mojo);

        mojo.execute();

        String xml = Files.readString(output);
        assertTrue(xml.contains("<password>secret-password</password>"), xml);
        assertTrue(xml.contains("<password>proxy-password</password>"), xml);
    }

    @Test
    @InjectMojo(goal = "effective-settings")
    void propertiesAreSorted(EffectiveSettingsMojo mojo) throws Exception {
        prepare(mojo);

        mojo.execute();

        String xml = Files.readString(output);
        assertTrue(xml.indexOf("<a>2</a>") < xml.indexOf("<z>1</z>"), xml);
    }
}
