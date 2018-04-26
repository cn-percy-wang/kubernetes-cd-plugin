/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.kubernetes.credentials;

import java.io.OutputStream;
import java.util.Collections;

import javax.annotation.Nonnull;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.microsoft.jenkins.azurecommons.remote.SSHClient;
import com.microsoft.jenkins.kubernetes.KubernetesClientWrapper;
import com.microsoft.jenkins.kubernetes.Messages;
import com.microsoft.jenkins.kubernetes.util.Constants;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

/**
 * @deprecated Use {@link KubeconfigCredentials}.
 */
@Deprecated
public class SSHCredentials
                            extends AbstractDescribableImpl<SSHCredentials>
                            implements ClientWrapperFactory.Builder {

    private String sshServer;
    private String sshCredentialsId;

    @DataBoundConstructor
    public SSHCredentials() {}

    public String getSshServer() {
        return sshServer;
    }

    @DataBoundSetter
    public void setSshServer(String sshServer) {
        this.sshServer = StringUtils.trimToEmpty(sshServer);
    }

    public String getSshCredentialsId() {
        return sshCredentialsId;
    }

    @DataBoundSetter
    public void setSshCredentialsId(String sshCredentialsId) {
        this.sshCredentialsId = sshCredentialsId;
    }

    @Nonnull
    public StandardUsernameCredentials getSshCredentials(Item owner) {
        StandardUsernameCredentials creds = CredentialsMatchers.firstOrNull(
            CredentialsProvider.lookupCredentials(
                StandardUsernameCredentials.class,
                owner,
                ACL.SYSTEM,
                Collections.<DomainRequirement> emptyList()),
            CredentialsMatchers.withId(getSshCredentialsId()));
        if (creds == null) {
            throw new IllegalStateException("Cannot find SSH credentials with ID " + getSshCredentialsId());
        }
        return creds;
    }

    public String getHost() {
        int colonIndex = sshServer.lastIndexOf(':');
        if (colonIndex >= 0) {
            return sshServer.substring(0, colonIndex);
        }
        return sshServer;
    }

    public int getPort() {
        int colonIndex = sshServer.indexOf(':');
        if (colonIndex >= 0) {
            return Integer.parseInt(sshServer.substring(colonIndex + 1));
        }
        return Constants.DEFAULT_SSH_PORT;
    }

    @Override
    public ClientWrapperFactory buildClientWrapperFactory(Item owner) {
        return new ClientWrapperFactoryImpl(getHost(), getPort(), getSshCredentials(owner));
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<SSHCredentials> {
        public ListBoxModel doFillSshCredentialsIdItems(@AncestorInPath Item owner) {
            StandardListBoxModel model = new StandardListBoxModel();
            model.add(Messages.SSHCredentials_selectCredentials(), Constants.INVALID_OPTION);
            model.includeAs(ACL.SYSTEM, owner, SSHUserPrivateKey.class);
            model.includeAs(ACL.SYSTEM, owner, StandardUsernamePasswordCredentials.class);
            return model;
        }

        public FormValidation doCheckSshServer(@QueryParameter String value) {
            if (StringUtils.isBlank(value)) {
                return FormValidation.error(Messages.SSHCredentials_serverRequired());
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckSshCredentialsId(@QueryParameter String value) {
            if (StringUtils.isBlank(value) || Constants.INVALID_OPTION.equals(value)) {
                return FormValidation.error(Messages.SSHCredentials_credentialsIdRequired());
            }
            return FormValidation.ok();
        }
    }

    private static class ClientWrapperFactoryImpl implements ClientWrapperFactory {
        private static final long                 serialVersionUID = 1L;

        private final String                      host;
        private final int                         port;
        private final StandardUsernameCredentials credentials;

        ClientWrapperFactoryImpl(String host, int port, StandardUsernameCredentials credentials) {
            this.host = host;
            this.port = port;
            this.credentials = credentials;
        }

        @Override
        public KubernetesClientWrapper buildClient(FilePath workspace) throws Exception {
            FilePath kubeconfig = fetchConfig(workspace);
            try {
                return new KubernetesClientWrapper(kubeconfig.getRemote());
            } finally {
                kubeconfig.delete();
            }
        }

        private FilePath fetchConfig(FilePath workspace) throws Exception {
            SSHClient sshClient = new SSHClient(host, port, credentials);
            try (SSHClient ignore = sshClient.connect()) {
                FilePath configFile = workspace.createTempFile(Constants.KUBECONFIG_PREFIX, "");
                try (OutputStream out = configFile.write()) {
                    sshClient.copyFrom(Constants.KUBECONFIG_FILE, out);
                }
                return configFile;
            }
        }
    }
}
