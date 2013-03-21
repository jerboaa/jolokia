package org.jolokia.handler.notification;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.management.MBeanException;
import javax.management.ReflectionException;

import org.jolokia.backend.executor.MBeanServerExecutor;
import org.jolokia.notification.*;
import org.jolokia.notification.pull.PullNotificationBackend;
import org.jolokia.request.notification.*;
import org.json.simple.JSONObject;

import static org.jolokia.request.notification.NotificationCommandType.*;

/**
 * Dispatcher for notification commands. Commands are dispatcher  to
 * the appropriate command in a {@link NotificationListenerDelegate}.
 *
 * @author roland
 * @since 18.03.13
 */
public class NotificationDispatcher {

    // TODO: Currently hard wired, should be looked up later on
    private final NotificationBackend[] backends = new NotificationBackend[]{
            new PullNotificationBackend()
    };

    // Map mode to Backend and configs
    private final Map<String, NotificationBackend> backendMap = new HashMap<String, NotificationBackend>();
    private final Map<String, Map<String, ?>> backendConfigMap = new HashMap<String, Map<String, ?>>();

    // Map dispatcher action to command typ
    private final Map<NotificationCommandType, Dispatchable> commandMap = new HashMap<NotificationCommandType, Dispatchable>();

    // Delegate for doing the actual registration stuff
    private NotificationListenerDelegate listenerDelegate;

    /**
     * Initialize backends and delegate
     */
    public NotificationDispatcher() {
        initBackend();
        listenerDelegate = new NotificationListenerDelegate();

        commandMap.put(REGISTER, new RegisterAction());
        commandMap.put(UNREGISTER, new UnregisterAction());
        commandMap.put(ADD, new AddAction());
        commandMap.put(REMOVE, new RemoveAction());
        commandMap.put(PING, new PingAction());
        commandMap.put(LIST, new ListAction());
    }

    /**
     * Dispatch a command to the appropriate method in the action in the delegate
     *
     * @param pExecutor executor providing access to the MBeanServers
     * @param pCommand the command to execute
     * @return the result generated by the dispatched actions
     *
     * @throws MBeanException
     * @throws IOException
     * @throws ReflectionException
     */
    public Object dispatch(MBeanServerExecutor pExecutor,NotificationCommand pCommand) throws MBeanException, IOException, ReflectionException {
        Dispatchable dispatchable = commandMap.get(pCommand.getType());
        if (dispatchable == null) {
            throw new IllegalArgumentException("Internal: No dispatch action for " + pCommand.getType() + " registered");
        }
        return dispatchable.execute(pExecutor,pCommand);
    }

    // =======================================================================================

    // Lookup backends and remember
    private void initBackend() {
        for (NotificationBackend backend : backends) {
            backendMap.put(backend.getType(), backend);
            backendConfigMap.put(backend.getType(),backend.getConfig());
        }
    }

    // Internal interface for dispatch actions
    private interface Dispatchable<T extends NotificationCommand> {
        /**
         * Execute a specific command
         * @param executor access to MBeanServers
         * @param command the command to execute
         * @return result from the command execution
         * @throws MBeanException
         * @throws IOException
         * @throws ReflectionException
         */
        Object execute(MBeanServerExecutor executor, T command) throws MBeanException, IOException, ReflectionException;
    }

    private class RegisterAction implements Dispatchable<RegisterCommand> {
        /**
         * Register a new client
         */
        public Object execute(MBeanServerExecutor executor, RegisterCommand command)
                throws MBeanException, IOException, ReflectionException {
            String id = listenerDelegate.register();
            JSONObject ret = new JSONObject();
            ret.put("backend",backendConfigMap);
            ret.put("id",id);
            return ret;
        }

    }
    private class UnregisterAction implements Dispatchable<UnregisterCommand> {
        /**
         * Unregister a client.
         */
        public Object execute(MBeanServerExecutor executor, UnregisterCommand command) throws MBeanException, IOException, ReflectionException {
            listenerDelegate.unregister(executor, command.getClient());
            return null;
        }

    }
    /**
     */
    private class AddAction implements Dispatchable<AddCommand> {
        /**
         * Add a new notification listener for a given client and MBean.
         * The command has the following properties:
         * <ul>
         *     <li>
         *         <b>client</b> client id identifying the current client.
         *     </li>
         *     <li>
         *         <b>mbean</b> the MBean on which to register the listener
         *     </li>
         *     <li>
         *         <b>mode</b> specifies the notification backend/model.
         *         Something like "pull", "sockjs", "ws" or "push"
         *     </li>
         *     <li>
         *         Optional <b>filter</b> and <b>handback</b>
         *     </li>
         * </ul>
         * @param executor access to mbean server
         * @param command add command
         * @return a map containing the handler id and the freshness interval (i.e. how often ping must be called before
         *         the listener is considered to be stale.
         */
        public Object execute(MBeanServerExecutor executor, AddCommand command) throws MBeanException, IOException, ReflectionException {
            NotificationBackend backend = getBackend(command.getMode());
            return listenerDelegate.addListener(executor, backend, command);
        }
    }

    private class RemoveAction implements Dispatchable<RemoveCommand> {
        /**
         * Remove a notification listener
         *
         * @param executor access to mbean server
         * @param command remove command
         */
        public Object execute(MBeanServerExecutor executor, RemoveCommand command) throws MBeanException, IOException, ReflectionException {
            listenerDelegate.removeListener(executor, command.getClient(),command.getHandle());
            return null;
        }
    }

    private class PingAction implements Dispatchable<PingCommand> {
        /**
         * Updated freshness of this client. Since we use a stateless model, the server
         * needs to know somehow, when a client fades away without unregistering all its
         * listeners. The idea is, that a client have to send a ping within certain
         * intervals and if this ping is missing, the client is considered as stale and
         * all its listeners get removed automatically then.
         *
         * @param executor access to mbean server
         * @param command remove command
         */
        public Object execute(MBeanServerExecutor executor, PingCommand command) throws MBeanException, IOException, ReflectionException {
            listenerDelegate.refresh(command.getClient());
            return null;
        }

    }

    private class ListAction implements Dispatchable<ListCommand> {
        /**
         * List all listener registered by a client along with its configuration parameters
         *
         * @param executor access to mbean server
         * @param command remove command
         * @return a JSON object describing all listeners. Keys are probably the handles
         *         created during addListener(). Values are the configuration of the
         *         listener jobs.
         */
        public Object execute(MBeanServerExecutor executor, ListCommand command) throws MBeanException, IOException, ReflectionException {
            return listenerDelegate.list(command.getClient());
        }

    }

    // =====================================================================================================

    // Lookup backend from the pre generated map of backends
    private NotificationBackend getBackend(String type) {
        NotificationBackend backend = backendMap.get(type);
        if (backend == null) {
            throw new IllegalArgumentException("No backend of type '" + type + "' registered");
        }
        return backend;
    }


}
