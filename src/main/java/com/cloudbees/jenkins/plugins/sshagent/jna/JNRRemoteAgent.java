/*
 * The MIT License
 *
 * Copyright (c) 2012, CloudBees, Inc., Stephen Connolly.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.cloudbees.jenkins.plugins.sshagent.jna;

import com.cloudbees.jenkins.plugins.sshagent.Messages;
import com.cloudbees.jenkins.plugins.sshagent.RemoteAgent;
import hudson.Launcher;
import hudson.model.TaskListener;
import jenkins.bouncycastle.api.PEMEncodable;

import java.io.File;

import java.io.IOException;
import java.security.KeyPair;
import javax.annotation.CheckForNull;

/**
 * An implementation that uses Apache SSH to provide the Agent over JNR's UnixSocket implementation.
 */
public class JNRRemoteAgent implements RemoteAgent {
    /**
     * Our agent.
     */
    private final AgentServer agent;
    /**
     * The socket bound by the agent.
     */
    private final String socket;
    /**
     * The listener in case we need to report exceptions
     */
    private final TaskListener listener;

    /**
     * Constructor.
     *
     * @param listener the listener.
     * @throws Exception if the agent could not start.
     */
    public JNRRemoteAgent(TaskListener listener, @CheckForNull File temp) throws Exception {
        this.listener = listener;
        agent = new AgentServer(temp);
        socket = agent.start();
    }

    /**
     * {@inheritDoc}
     */
    public String getSocket() {
        return socket;
    }

    /**
     * {@inheritDoc}
     */
    public void addIdentity(String privateKey, final String passphrase, String comment, Launcher launcher) throws IOException {
        try {
            KeyPair keyPair = PEMEncodable.decode(privateKey, passphrase == null ? null : passphrase.toCharArray()).toKeyPair();
            agent.getAgent().addIdentity(keyPair, comment);
        } catch (Exception e) {
            listener.getLogger().println(Messages.SSHAgentBuildWrapper_UnableToReadKey(e.getMessage()));
            e.printStackTrace(listener.getLogger());
        }
    }

    /**
     * {@inheritDoc}
     */
    public void stop(Launcher launcher) {
        agent.close();
    }
}
