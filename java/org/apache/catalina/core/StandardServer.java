/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.core;

import org.apache.catalina.*;
import org.apache.catalina.deploy.NamingResourcesImpl;
import org.apache.catalina.mbeans.MBeanFactory;
import org.apache.catalina.startup.Catalina;
import org.apache.catalina.util.ExtensionValidator;
import org.apache.catalina.util.LifecycleMBeanBase;
import org.apache.catalina.util.ServerInfo;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.buf.StringCache;
import org.apache.tomcat.util.res.StringManager;

import javax.management.ObjectName;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.security.AccessControlException;
import java.util.Random;


/**
 * Standard implementation of the <b>Server</b> interface, available for use
 * (but not required) when deploying and starting Catalina.
 *
 * @author Craig R. McClanahan
 */
public final class StandardServer extends LifecycleMBeanBase implements Server {

    private static final Log log = LogFactory.getLog(StandardServer.class);


    // ------------------------------------------------------------ Constructor
    /**
     * The string manager for this package.
     */
    private static final StringManager sm =
        StringManager.getManager(Constants.Package);


    // ----------------------------------------------------- Instance Variables
    /**
     * The property change support for this component.
     */
    protected final PropertyChangeSupport support = new PropertyChangeSupport(this);
    /**
     * The naming context listener for this web application.
     */
    private final NamingContextListener namingContextListener;
    private final Object servicesLock = new Object();
    private final Object namingToken = new Object();
    /**
     * Global naming resources context.
     */
    private javax.naming.Context globalNamingContext = null;
    /**
     * Global naming resources.
     */
    private NamingResourcesImpl globalNamingResources = null;
    /**
     * The port number on which we wait for shutdown commands.
     */
    private int port = 8005;
    /**
     * The address on which we wait for shutdown commands.
     */
    private String address = "localhost";
    /**
     * A random number generator that is <strong>only</strong> used if
     * the shutdown command string is longer than 1024 characters.
     */
    private Random random = null;
    /**
     * The set of Services associated with this Server.
     */
    private Service[] services = new Service[0];
    /**
     * The shutdown command string we are looking for.
     */
    private String shutdown = "SHUTDOWN";
    private volatile boolean stopAwait = false;

    private Catalina catalina = null;

    private ClassLoader parentClassLoader = null;

    /**
     * Thread that currently is inside our await() method.
     */
    private volatile Thread awaitThread = null;

    /**
     * Server socket that is used to wait for the shutdown command.
     */
    private volatile ServerSocket awaitSocket = null;

    private File catalinaHome = null;

    private File catalinaBase = null;
    private ObjectName onameStringCache;


    // ------------------------------------------------------------- Properties
    private ObjectName onameMBeanFactory;


    /**
     * Construct a default instance of this class.
     */
    public StandardServer() {

        super();

        globalNamingResources = new NamingResourcesImpl();
        globalNamingResources.setContainer(this);

        if (isUseNaming()) {
            namingContextListener = new NamingContextListener();
            addLifecycleListener(namingContextListener);
        } else {
            namingContextListener = null;
        }

    }

    @Override
    public Object getNamingToken() {
        return namingToken;
    }

    /**
     * Return the global naming resources context.
     */
    @Override
    public javax.naming.Context getGlobalNamingContext() {

        return (this.globalNamingContext);

    }

    /**
     * Set the global naming resources context.
     *
     * @param globalNamingContext The new global naming resource context
     */
    public void setGlobalNamingContext
        (javax.naming.Context globalNamingContext) {

        this.globalNamingContext = globalNamingContext;

    }

    /**
     * Return the global naming resources.
     */
    @Override
    public NamingResourcesImpl getGlobalNamingResources() {

        return (this.globalNamingResources);

    }

    /**
     * Set the global naming resources.
     *
     * @param globalNamingResources The new global naming resources
     */
    @Override
    public void setGlobalNamingResources
        (NamingResourcesImpl globalNamingResources) {

        NamingResourcesImpl oldGlobalNamingResources =
            this.globalNamingResources;
        this.globalNamingResources = globalNamingResources;
        this.globalNamingResources.setContainer(this);
        support.firePropertyChange("globalNamingResources",
                                   oldGlobalNamingResources,
                                   this.globalNamingResources);

    }

    /**
     * Report the current Tomcat Server Release number
     * @return Tomcat release identifier
     */
    public String getServerInfo() {

        return ServerInfo.getServerInfo();
    }

    /**
     * Return the port number we listen to for shutdown commands.
     */
    @Override
    public int getPort() {

        return (this.port);

    }

    /**
     * Set the port number we listen to for shutdown commands.
     *
     * @param port The new port number
     */
    @Override
    public void setPort(int port) {

        this.port = port;

    }

    /**
     * Return the address on which we listen to for shutdown commands.
     */
    @Override
    public String getAddress() {

        return (this.address);

    }

    /**
     * Set the address on which we listen to for shutdown commands.
     *
     * @param address The new address
     */
    @Override
    public void setAddress(String address) {

        this.address = address;

    }

    /**
     * Return the shutdown command string we are waiting for.
     */
    @Override
    public String getShutdown() {

        return (this.shutdown);

    }

    /**
     * Set the shutdown command we are waiting for.
     *
     * @param shutdown The new shutdown command
     */
    @Override
    public void setShutdown(String shutdown) {

        this.shutdown = shutdown;

    }

    // --------------------------------------------------------- Server Methods

    /**
     * Return the outer Catalina startup/shutdown component if present.
     */
    @Override
    public Catalina getCatalina() {
        return catalina;
    }

    /**
     * Set the outer Catalina startup/shutdown component if present.
     */
    @Override
    public void setCatalina(Catalina catalina) {
        this.catalina = catalina;
    }

    /**
     * Add a new Service to the set of defined Services.
     *
     * @param service The Service to be added
     */
    @Override
    public void addService(Service service) {

        service.setServer(this);

        synchronized (servicesLock) {
            Service[] results = new Service[services.length + 1];
            System.arraycopy(services, 0, results, 0, services.length);
            results[services.length] = service;
            services = results;

            if (getState().isAvailable()) {
                try {
                    service.start();
                } catch (LifecycleException e) {
                    // Ignore
                }
            }

            // Report this property change to interested listeners
            support.firePropertyChange("service", null, service);
        }

    }

    public void stopAwait() {
        stopAwait=true;
        Thread t = awaitThread;
        if (t != null) {
            ServerSocket s = awaitSocket;
            if (s != null) {
                awaitSocket = null;
                try {
                    s.close();
                } catch (IOException e) {
                    // Ignored
                }
            }
            t.interrupt();
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                // Ignored
            }
        }
    }

    /**
     * Wait until a proper shutdown command is received, then return.
     * This keeps the main thread alive - the thread pool listening for http
     * connections is daemon threads.
     *
     * await()方法的调用让服务器进入等待状态
     */
    @Override
    public void await() {
        // Negative values - don't wait on port - tomcat is embedded or we just don't like ports
        // 如果端口是-2则不进入循环，直接退出
        if( port == -2 ) {
            // undocumented yet - for embedding apps that are around, alive.
            return;
        }

        //如果端口是-1则进入循环，而且无法通过网络退出
        if( port==-1 ) {
            try {
                awaitThread = Thread.currentThread();
                while(!stopAwait) {
                    try {
                        Thread.sleep( 10000 );
                    } catch( InterruptedException ex ) {
                        // continue and check the flag
                    }
                }
            } finally {
                awaitThread = null;
            }
            return;
        }

        //如果端口不是-1也不是-2，而是大于0，则新建一个监听关闭命令的ServerSocket
        // Set up a server socket to wait on
        try {
            awaitSocket = new ServerSocket(port, 1,
                    InetAddress.getByName(address));
        } catch (IOException e) {
            log.error("StandardServer.await: create[" + address
                               + ":" + port
                               + "]: ", e);
            return;
        }

        try {
            awaitThread = Thread.currentThread();

            // Loop waiting for a connection and a valid command
            while (!stopAwait) {
                ServerSocket serverSocket = awaitSocket;
                if (serverSocket == null) {
                    break;
                }

                // Wait for the next connection
                Socket socket = null;
                StringBuilder command = new StringBuilder();
                try {
                    InputStream stream;
                    long acceptStartTime = System.currentTimeMillis();
                    try {
                        socket = serverSocket.accept();
                        socket.setSoTimeout(10 * 1000);  // Ten seconds
                        stream = socket.getInputStream();
                    } catch (SocketTimeoutException ste) {
                        // This should never happen but bug 56684 suggests that
                        // it does.
                        log.warn(sm.getString("standardServer.accept.timeout",
                                Long.valueOf(System.currentTimeMillis() - acceptStartTime)), ste);
                        continue;
                    } catch (AccessControlException ace) {
                        log.warn("StandardServer.accept security exception: "
                                + ace.getMessage(), ace);
                        continue;
                    } catch (IOException e) {
                        if (stopAwait) {
                            // Wait was aborted with socket.close()
                            break;
                        }
                        log.error("StandardServer.await: accept: ", e);
                        break;
                    }

                    // Read a set of characters from the socket
                    int expected = 1024; // Cut off to avoid DoS attack
                    while (expected < shutdown.length()) {
                        if (random == null)
                            random = new Random();
                        expected += (random.nextInt() % 1024);
                    }
                    while (expected > 0) {
                        int ch = -1;
                        try {
                            ch = stream.read();
                        } catch (IOException e) {
                            log.warn("StandardServer.await: read: ", e);
                            ch = -1;
                        }
                        if (ch < 32)  // Control character or EOF terminates loop
                            break;
                        command.append((char) ch);
                        expected--;
                    }
                } finally {
                    // Close the socket now that we are done with it
                    try {
                        if (socket != null) {
                            socket.close();
                        }
                    } catch (IOException e) {
                        // Ignore
                    }
                }

                // 检查是否在指定的端口接收到和shutdown命令匹配的命令
                boolean match = command.toString().equals(shutdown);
                //匹配则跳出循环
                if (match) {
                    log.info(sm.getString("standardServer.shutdownViaPort"));
                    break;
                } else
                    log.warn("StandardServer.await: Invalid command '"
                            + command.toString() + "' received");
            }
        } finally {
            ServerSocket serverSocket = awaitSocket;
            awaitThread = null;
            awaitSocket = null;

            // Close the server socket and return
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Return the specified Service (if it exists); otherwise return
     * <code>null</code>.
     *
     * @param name Name of the Service to be returned
     */
    @Override
    public Service findService(String name) {

        if (name == null) {
            return (null);
        }
        synchronized (servicesLock) {
            for (int i = 0; i < services.length; i++) {
                if (name.equals(services[i].getName())) {
                    return (services[i]);
                }
            }
        }
        return (null);

    }

    /**
     * Return the set of Services defined within this Server.
     */
    @Override
    public Service[] findServices() {

        return services;

    }

    /**
     * Return the JMX service names.
     */
    public ObjectName[] getServiceNames() {
        ObjectName[] onames = new ObjectName[services.length];
        for( int i=0; i<services.length; i++ ) {
            onames[i]=((StandardService)services[i]).getObjectName();
        }
        return onames;
    }

    /**
     * Remove the specified Service from the set associated from this
     * Server.
     *
     * @param service The Service to be removed
     */
    @Override
    public void removeService(Service service) {

        synchronized (servicesLock) {
            int j = -1;
            for (int i = 0; i < services.length; i++) {
                if (service == services[i]) {
                    j = i;
                    break;
                }
            }
            if (j < 0)
                return;
            try {
                services[j].stop();
            } catch (LifecycleException e) {
                // Ignore
            }
            int k = 0;
            Service[] results = new Service[services.length - 1];
            for (int i = 0; i < services.length; i++) {
                if (i != j)
                    results[k++] = services[i];
            }
            services = results;

            // Report this property change to interested listeners
            support.firePropertyChange("service", service, null);
        }

    }

    @Override
    public File getCatalinaBase() {
        if (catalinaBase != null) {
            return catalinaBase;
        }

        catalinaBase = getCatalinaHome();
        return catalinaBase;
    }

    @Override
    public void setCatalinaBase(File catalinaBase) {
        this.catalinaBase = catalinaBase;
    }


    // --------------------------------------------------------- Public Methods

    @Override
    public File getCatalinaHome() {
        return catalinaHome;
    }

    @Override
    public void setCatalinaHome(File catalinaHome) {
        this.catalinaHome = catalinaHome;
    }

    /**
     * Add a property change listener to this component.
     *
     * @param listener The listener to add
     */
    public void addPropertyChangeListener(PropertyChangeListener listener) {

        support.addPropertyChangeListener(listener);

    }

    /**
     * Remove a property change listener from this component.
     *
     * @param listener The listener to remove
     */
    public void removePropertyChangeListener(PropertyChangeListener listener) {

        support.removePropertyChangeListener(listener);

    }

    /**
     * Return a String representation of this component.
     */
    @Override
    public String toString() {

        StringBuilder sb = new StringBuilder("StandardServer[");
        sb.append(getPort());
        sb.append("]");
        return (sb.toString());

    }

    /**
     * Write the configuration information for this entire <code>Server</code>
     * out to the server.xml configuration file.
     *
     * @exception   javax.management.InstanceNotFoundException
     *              if the managed resource object cannot be found
     * @exception   javax.management.MBeanException
     *              if the initializer of the object throws an exception, or
     *              persistence is not supported
     * @exception   javax.management.RuntimeOperationsException
     *              if an exception is reported by the persistence mechanism
     */
    public synchronized void storeConfig() throws Exception {
        try {
            // Note: Hard-coded domain used since this object is per Server/JVM
            ObjectName sname = new ObjectName("Catalina:type=StoreConfig");
            if (mserver.isRegistered(sname)) {
                mserver.invoke(sname, "storeConfig", null, null);
            } else {
                log.error(sm.getString("standardServer.storeConfig.notAvailable", sname));
            }
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log.error(t);
        }
    }

    /**
     * Write the configuration information for <code>Context</code>
     * out to the specified configuration file.
     *
     * @exception javax.management.InstanceNotFoundException if the managed resource object
     *  cannot be found
     * @exception javax.management.MBeanException if the initializer of the object throws
     *  an exception, or persistence is not supported
     * @exception javax.management.RuntimeOperationsException if an exception is reported
     *  by the persistence mechanism
     */
    public synchronized void storeContext(Context context) throws Exception {
        try {
            // Note: Hard-coded domain used since this object is per Server/JVM
            ObjectName sname = new ObjectName("Catalina:type=StoreConfig");
            if (mserver.isRegistered(sname)) {
                mserver.invoke(sname, "store",
                    new Object[] {context},
                    new String [] { "java.lang.String"});
            } else {
                log.error(sm.getString("standardServer.storeConfig.notAvailable", sname));
            }
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log.error(t);
        }
    }

    /**
     * Return true if naming should be used.
     */
    private boolean isUseNaming() {
        boolean useNaming = true;
        // Reading the "catalina.useNaming" environment variable
        String useNamingProperty = System.getProperty("catalina.useNaming");
        if ((useNamingProperty != null)
            && (useNamingProperty.equals("false"))) {
            useNaming = false;
        }
        return useNaming;
    }

    /**
     * Start nested components ({@link Service}s) and implement the requirements
     * of {@link org.apache.catalina.util.LifecycleBase#startInternal()}.
     *
     * @exception LifecycleException if this component detects a fatal error
     *  that prevents this component from being used
     */
    @Override
    protected void startInternal() throws LifecycleException {

        //触发监听事件
        fireLifecycleEvent(CONFIGURE_START_EVENT, null);
        setState(LifecycleState.STARTING);

        globalNamingResources.start();

        // Start our defined Services
        synchronized (servicesLock) {
            for (int i = 0; i < services.length; i++) {
                services[i].start();
            }
        }
    }

    /**
     * Stop nested components ({@link Service}s) and implement the requirements
     * of {@link org.apache.catalina.util.LifecycleBase#stopInternal()}.
     *
     * @exception LifecycleException if this component detects a fatal error
     *  that needs to be reported
     */
    @Override
    protected void stopInternal() throws LifecycleException {

        setState(LifecycleState.STOPPING);
        fireLifecycleEvent(CONFIGURE_STOP_EVENT, null);

        // Stop our defined Services
        for (int i = 0; i < services.length; i++) {
            services[i].stop();
        }

        globalNamingResources.stop();

        stopAwait();
    }

    /**
     * Invoke a pre-startup initialization. This is used to allow connectors
     * to bind to restricted ports under Unix operating environments.
     */
    @Override
    protected void initInternal() throws LifecycleException {

        super.initInternal();

        // Register global String cache
        // Note although the cache is global, if there are multiple Servers
        // present in the JVM (may happen when embedding) then the same cache
        // will be registered under multiple names
        onameStringCache = register(new StringCache(), "type=StringCache");

        // Register the MBeanFactory
        MBeanFactory factory = new MBeanFactory();
        factory.setContainer(this);
        onameMBeanFactory = register(factory, "type=MBeanFactory");

        // Register the naming resources
        globalNamingResources.init();

        // Populate the extension validator with JARs from common and shared
        // class loaders
        if (getCatalina() != null) {
            ClassLoader cl = getCatalina().getParentClassLoader();
            // Walk the class loader hierarchy. Stop at the system class loader.
            // This will add the shared (if present) and common class loaders
            while (cl != null && cl != ClassLoader.getSystemClassLoader()) {
                if (cl instanceof URLClassLoader) {
                    URL[] urls = ((URLClassLoader) cl).getURLs();
                    for (URL url : urls) {
                        if (url.getProtocol().equals("file")) {
                            try {
                                File f = new File (url.toURI());
                                if (f.isFile() &&
                                        f.getName().endsWith(".jar")) {
                                    ExtensionValidator.addSystemResource(f);
                                }
                            } catch (URISyntaxException e) {
                                // Ignore
                            } catch (IOException e) {
                                // Ignore
                            }
                        }
                    }
                }
                cl = cl.getParent();
            }
        }
        // Initialize our defined Services
        for (int i = 0; i < services.length; i++) {
            services[i].init();
        }
    }

    @Override
    protected void destroyInternal() throws LifecycleException {
        // Destroy our defined Services
        for (int i = 0; i < services.length; i++) {
            services[i].destroy();
        }

        globalNamingResources.destroy();

        unregister(onameMBeanFactory);

        unregister(onameStringCache);

        super.destroyInternal();
    }

    /**
     * Return the parent class loader for this component.
     */
    @Override
    public ClassLoader getParentClassLoader() {
        if (parentClassLoader != null)
            return (parentClassLoader);
        if (catalina != null) {
            return (catalina.getParentClassLoader());
        }
        return (ClassLoader.getSystemClassLoader());
    }

    /**
     * Set the parent class loader for this server.
     *
     * @param parent The new parent class loader
     */
    @Override
    public void setParentClassLoader(ClassLoader parent) {
        ClassLoader oldParentClassLoader = this.parentClassLoader;
        this.parentClassLoader = parent;
        support.firePropertyChange("parentClassLoader", oldParentClassLoader,
                                   this.parentClassLoader);
    }

    /**
     * Obtain the MBean domain for this server. The domain is obtained using
     * the following search order:
     * <ol>
     * <li>Name of first {@link org.apache.catalina.Engine}.</li>
     * <li>Name of first {@link Service}.</li>
     * </ol>
     */
    @Override
    protected String getDomainInternal() {

        String domain = null;

        Service[] services = findServices();
        if (services.length > 0) {
            Service service = services[0];
            if (service != null) {
                domain = service.getDomain();
            }
        }
        return domain;
    }


    @Override
    protected final String getObjectNameKeyProperties() {
        return "type=Server";
    }

}
