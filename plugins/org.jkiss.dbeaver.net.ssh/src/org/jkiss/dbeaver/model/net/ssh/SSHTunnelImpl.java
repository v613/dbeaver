/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.model.net.ssh;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ModelPreferences;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.net.DBWHandlerConfiguration;
import org.jkiss.dbeaver.model.net.DBWTunnel;
import org.jkiss.dbeaver.model.net.ssh.registry.SSHImplementationDescriptor;
import org.jkiss.dbeaver.model.net.ssh.registry.SSHImplementationRegistry;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.registry.RegistryConstants;
import org.jkiss.dbeaver.utils.RuntimeUtils;
import org.jkiss.utils.Base64;
import org.jkiss.utils.CommonUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * SSH tunnel
 */
public class SSHTunnelImpl implements DBWTunnel {

    private static final Log log = Log.getLog(SSHTunnelImpl.class);
    private static final String DEF_IMPLEMENTATION = "sshj";

    private DBWHandlerConfiguration configuration;
    private SSHImplementation implementation;
    private final List<Runnable> listeners = new ArrayList<>();

    public SSHImplementation getImplementation() {
        return implementation;
    }

    @Override
    public void addCloseListener(Runnable listener) {
        this.listeners.add(listener);
    }

    @Override
    public DBPConnectionConfiguration initializeHandler(DBRProgressMonitor monitor, DBWHandlerConfiguration configuration, DBPConnectionConfiguration connectionInfo)
        throws DBException, IOException
    {
        this.configuration = configuration;
        String implId = configuration.getStringProperty(SSHConstants.PROP_IMPLEMENTATION);
        if (CommonUtils.isEmpty(implId)) {
            // Backward compatibility
            implId = DEF_IMPLEMENTATION;
        }

        try {
            SSHImplementationDescriptor implDesc = SSHImplementationRegistry.getInstance().getDescriptor(implId);
            if (implDesc == null) {
                implDesc = SSHImplementationRegistry.getInstance().getDescriptor(DEF_IMPLEMENTATION);
            }
            if (implDesc == null) {
                throw new DBException("Can't find SSH tunnel implementation '" + implId + "'");
            }
            if (implementation == null || implementation.getClass() != implDesc.getImplClass().getObjectClass()) {
                implementation = implDesc.createImplementation();
            }
        } catch (Throwable e) {
            throw new DBException("Can't create SSH tunnel implementation '" + implId + "'", e);
        }
        return implementation.initTunnel(monitor, configuration, connectionInfo);
    }

    @Override
    public void closeTunnel(DBRProgressMonitor monitor) throws DBException, IOException
    {
        if (implementation != null) {
            implementation.closeTunnel(monitor);
            // Do not nullify tunnel to keep saved tunnel port number (#7952)
        }
        for (Runnable listener : this.listeners) {
            listener.run();
        }
        this.listeners.clear();
    }

    @Override
    public boolean matchesParameters(String host, int port) {
        if (host.equals(configuration.getStringProperty(DBWHandlerConfiguration.PROP_HOST))) {
            int sshPort = configuration.getIntProperty(DBWHandlerConfiguration.PROP_PORT);
            return sshPort == port;
        }
        return false;
    }

    @Override
    public AuthCredentials getRequiredCredentials(@NotNull DBWHandlerConfiguration configuration, @Nullable String prefix) {
        String start = prefix;
        if (start == null) {
            start = "";
        }
        if (!configuration.isEnabled() || !configuration.isSecured()) {
            return AuthCredentials.NONE;
        }
        if (configuration.getBooleanProperty(start + RegistryConstants.ATTR_SAVE_PASSWORD)) {
            return AuthCredentials.NONE;
        }

        String sshAuthType = configuration.getStringProperty(start + SSHConstants.PROP_AUTH_TYPE);
        SSHConstants.AuthType authType = SSHConstants.AuthType.PASSWORD;
        if (sshAuthType != null) {
            authType = SSHConstants.AuthType.valueOf(sshAuthType);
        }
        if (authType == SSHConstants.AuthType.PUBLIC_KEY) {
            String privKeyValue = configuration.getSecureProperty(start + SSHConstants.PROP_KEY_VALUE);
            if (privKeyValue != null) {
                byte[] pkBinary = Base64.decode(privKeyValue);
                if (SSHUtils.isKeyEncrypted(pkBinary)) {
                    return AuthCredentials.PASSWORD;
                }
            }
            // Check whether this key is encrypted
            String privKeyPath = configuration.getStringProperty(start + SSHConstants.PROP_KEY_PATH);
            if (!CommonUtils.isEmpty(privKeyPath) && SSHUtils.isKeyFileEncrypted(privKeyPath)) {
                return AuthCredentials.PASSWORD;
            }
            return AuthCredentials.NONE;
        }
        if (authType == SSHConstants.AuthType.AGENT) {
            return AuthCredentials.NONE;
        }
        return AuthCredentials.CREDENTIALS;
    }

    @Override
    public void invalidateHandler(DBRProgressMonitor monitor, DBPDataSource dataSource) throws DBException, IOException {
        if (implementation != null) {
            RuntimeUtils.runTask(monitor1 -> {
                monitor1.beginTask("Invalidate SSH tunnel", 1);
                try {
                    implementation.invalidateTunnel(monitor1);
                } catch (Exception e) {
                    log.debug("Error invalidating SSH tunnel. Closing.", e);
                    try {
                        closeTunnel(monitor);
                    } catch (Exception e1) {
                        log.error("Error closing broken tunnel", e1);
                    }
                } finally {
                    monitor.done();
                }
            },
            "Ping SSH tunnel " + dataSource.getContainer().getName(),
            dataSource.getContainer().getPreferenceStore().getInt(ModelPreferences.CONNECTION_VALIDATION_TIMEOUT));
        }
    }

}
