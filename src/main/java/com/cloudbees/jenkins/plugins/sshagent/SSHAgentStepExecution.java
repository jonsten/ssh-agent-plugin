package com.cloudbees.jenkins.plugins.sshagent;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.google.inject.Inject;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Functions;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.slaves.WorkspaceList;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.steps.*;

import javax.annotation.CheckReturnValue;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;

public class SSHAgentStepExecution extends AbstractStepExecutionImpl {

    private static final long serialVersionUID = 1L;

    @Inject(optional = true)
    private SSHAgentStep step;

    /**
     * Value for SSH_AUTH_SOCK environment variable.
     */
    private String socket;

    /**
     * Listing of socket files created. Will be used by {@link #purgeSockets()} and {@link #initRemoteAgent()}
     */
    private List<String> sockets;

    /**
     * The proxy for the real remote agent that is on the other side of the channel (as the agent needs to
     * run on a remote machine)
     */
    private transient RemoteAgent agent = null;

    @Inject
    protected SSHAgentStepExecution(StepContext context) {
        super(context);
    }

    @Override
    public boolean start() throws Exception {
        StepContext context = getContext();
        sockets = new ArrayList<String>();
        initRemoteAgent();
        context.newBodyInvoker().
                withContext(EnvironmentExpander.merge(getContext().get(EnvironmentExpander.class), new ExpanderImpl(this))).
                withCallback(new Callback(this)).start();
        return false;
    }

    @Override
    public void stop(Throwable cause) throws Exception {
        if (agent != null) {
            agent.stop(getLauncher(), getListener());
            getLogger().println(Messages.SSHAgentBuildWrapper_Stopped());
        }
        purgeSockets();
    }

    @Override
    public void onResume() {
        try {
            purgeSockets();
            initRemoteAgent();
        } catch (IOException | InterruptedException x) {
            getLogger().println(Messages.SSHAgentBuildWrapper_CouldNotStartAgent());
            Functions.printStackTrace(x, getLogger());
        }
    }

    // TODO use 1.652 use WorkspaceList.tempDir
    static FilePath tempDir(FilePath ws) {
        return ws.sibling(ws.getName() + System.getProperty(WorkspaceList.class.getName(), "@") + "tmp");
    }

    private static class Callback extends BodyExecutionCallback.TailCall {

        private static final long serialVersionUID = 1L;

        private final SSHAgentStepExecution execution;

        Callback (SSHAgentStepExecution execution) {
            this.execution = execution;
        }

        @Override
        protected void finished(StepContext context) throws Exception {
            execution.cleanUp();
        }

    }

    private static final class ExpanderImpl extends EnvironmentExpander {

        private static final long serialVersionUID = 1L;

        private final SSHAgentStepExecution execution;

        ExpanderImpl(SSHAgentStepExecution execution) {
            this.execution = execution;
        }

        @Override
        public void expand(EnvVars env) throws IOException, InterruptedException {
            env.override("SSH_AUTH_SOCK", execution.getSocket());
        }
    }

    /**
     * Initializes a SSH Agent.
     *
     * @throws IOException
     */
    private void initRemoteAgent() throws IOException, InterruptedException {

        List<SSHUserPrivateKey> userPrivateKeys = new ArrayList<SSHUserPrivateKey>();
        for (String id : new LinkedHashSet<String>(step.getCredentials())) {
            final SSHUserPrivateKey c = CredentialsProvider.findCredentialById(id, SSHUserPrivateKey.class, getBuild());
            CredentialsProvider.track(getBuild(), c);
            if (c == null && !step.isIgnoreMissing()) {
                getListener().fatalError(Messages.SSHAgentBuildWrapper_CredentialsNotFound());
            }
            if (c != null && !userPrivateKeys.contains(c)) {
                userPrivateKeys.add(c);
            }
        }
        for (SSHUserPrivateKey userPrivateKey : userPrivateKeys) {
            getLogger().println(Messages.SSHAgentBuildWrapper_UsingCredentials(SSHAgentBuildWrapper.description(userPrivateKey)));
        }

        getLogger().println("[ssh-agent] Looking for ssh-agent implementation...");
        Map<String, Throwable> faults = new LinkedHashMap<String, Throwable>();
        for (RemoteAgentFactory factory : Jenkins.getActiveInstance().getExtensionList(RemoteAgentFactory.class)) {
            if (factory.isSupported(getLauncher(), getListener())) {
                try {
                    getLogger().println("[ssh-agent]   " + factory.getDisplayName());
                    agent = factory.start(getLauncher(), getListener(), tempDir(getWorkspace()));
                    break;
                } catch (Throwable t) {
                    faults.put(factory.getDisplayName(), t);
                }
            }
        }
        if (agent == null) {
            getLogger().println("[ssh-agent] FATAL: Could not find a suitable ssh-agent provider");
            getLogger().println("[ssh-agent] Diagnostic report");
            for (Map.Entry<String, Throwable> fault : faults.entrySet()) {
                getLogger().println("[ssh-agent] * " + fault.getKey());
                StringWriter sw = new StringWriter();
                fault.getValue().printStackTrace(new PrintWriter(sw));
                for (String line : StringUtils.split(sw.toString(), "\n")) {
                    getLogger().println("[ssh-agent]     " + line);
                }
            }
            throw new RuntimeException("[ssh-agent] Could not find a suitable ssh-agent provider.");
        }

        for (SSHUserPrivateKey userPrivateKey : userPrivateKeys) {
            final Secret passphrase = userPrivateKey.getPassphrase();
            final String effectivePassphrase = passphrase == null ? null : passphrase.getPlainText();
            for (String privateKey : userPrivateKey.getPrivateKeys()) {
                agent.addIdentity(privateKey, effectivePassphrase, SSHAgentBuildWrapper.description(userPrivateKey),
                        getLauncher(), getListener());
            }
        }

        getLogger().println(Messages.SSHAgentBuildWrapper_Started());
        socket = agent.getSocket();
        sockets.add(socket);
    }

    /**
     * Shuts down the current SSH Agent and purges socket files.
     */
    private void cleanUp() throws Exception {
        try {
            if (agent != null) {
                agent.stop(getLauncher(), getListener());
                getLogger().println(Messages.SSHAgentBuildWrapper_Stopped());
            }
        } finally {
            purgeSockets();
        }
    }

    /**
     * Purges all socket files created previously.
     * Especially useful when Jenkins is restarted during the execution of this step.
     */
    private void purgeSockets() {
        Iterator<String> it = sockets.iterator();
        while (it.hasNext()) {
            File socket = new File(it.next());
            if (socket.exists()) {
                if (!socket.delete()) {
                    getLogger().format("It was a problem removing this socket file %s", socket.getAbsolutePath());
                }
            }
            it.remove();
        }
    }

    /**
     * Returns the socket.
     *
     * @return The value that SSH_AUTH_SOCK should be set to.
     */
    @CheckReturnValue private String getSocket() {
        return socket;
    }

    /**
     * Retrieves the workspace reference in the current context.
     *
     * @return File path object for the workspace
     *
     * @throws IOException See {@link StepContext#get(Class)}
     * @throws InterruptedException See {@link StepContext#get(Class)}
     */
    private FilePath getWorkspace() throws IOException, InterruptedException {
        return getContext().get(FilePath.class);
    }

    /**
     * Retrieves the launcher reference in the current context.
     *
     * @return Current launcher object
     *
     * @throws IOException See {@link StepContext#get(Class)}
     * @throws InterruptedException See {@link StepContext#get(Class)}
     */
    private Launcher getLauncher() throws IOException, InterruptedException {
        return getContext().get(Launcher.class);
    }

    /**
     * Retrieves the build reference in the current context.
     *
     * @return Current build/run object
     *
     * @throws IOException See {@link StepContext#get(Class)}
     * @throws InterruptedException See {@link StepContext#get(Class)}
     */
    private Run<?, ?> getBuild() throws IOException, InterruptedException {
        return getContext().get(Run.class);
    }

    /**
     * Retrieves the task listener reference in the current context or NULL
     * listener if there is no listener in current context.
     *
     * @return Current task listener object
     */
    private TaskListener getListener() {
        try {
            return getContext().get(TaskListener.class);
        } catch (IOException | InterruptedException e) {
            return TaskListener.NULL;
        }
    }

    /**
     * Retrieves the logger reference in the current context or NULL logger
     * if there is no logger in current context.
     *
     * @return Current logger object
     */
    private PrintStream getLogger() {
        return getListener().getLogger();
    }
}
