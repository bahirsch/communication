package com.cisco.vss.foundation.http.server.jetty;

import com.cisco.vss.foundation.configuration.ConfigurationFactory;
import com.cisco.vss.foundation.http.server.HttpServerFactory;
import com.cisco.vss.foundation.http.server.ServerFailedToStartException;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.Servlet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This is a factory that enables creating servers in a common way
 * 
 * @author Yair Ogen
 */
public enum JettyHttpServerFactory implements HttpServerFactory {

    INSTANCE;

	private final static Logger LOGGER = LoggerFactory.getLogger(JettyHttpServerFactory.class);

	private static final Map<String, Server> servers = new ConcurrentHashMap<String, Server>();

	private JettyHttpServerFactory() {
	}

	/**
	 * start a new http server
	 * 
	 * @param serviceName - the http logical service name
	 * @param servlets - a mapping between servlet path and servlet instance. This mapping uses the google collections {@link com.google.common.collect.ListMultimap}.
	 * <br>Example of usage:
	 * {@code ArrayListMultimap<String,Servlet> servletMap = ArrayListMultimap.create()}
	 */
    @Override
	public void startHttpServer(String serviceName, ListMultimap<String, Servlet> servlets) {

		ArrayListMultimap<String,Filter> filterMap = ArrayListMultimap.create();
		startHttpServer(serviceName, servlets, filterMap);
	}


	/**
	 * start a new http server
	 *
	 * @param serviceName - the http logical service name
	 * @param servlets - a mapping between servlet path and servlet instance. This mapping uses the google collections {@link com.google.common.collect.ListMultimap}.
	 * <br>Example of usage:
	 * {@code ArrayListMultimap<String,Servlet> servletMap = ArrayListMultimap.create()}
	 * @param filters - a mapping between filter path and filter instance. This mapping uses the google collections {@link com.google.common.collect.ListMultimap}.
	 * <br>Example of usage:
	 * {@code ArrayListMultimap<String,Filter> filterMap = ArrayListMultimap.create()}
	 */
    @Override
	public void startHttpServer(String serviceName, ListMultimap<String, Servlet> servlets, ListMultimap<String, Filter> filters) {

		startHttpServer(serviceName, servlets, filters, null, null);
	}



    @Override
	public void startHttpServer(String serviceName, ListMultimap<String, Servlet> servlets, ListMultimap<String, Filter> filters, String keyStorePath, String keyStorePassword) {

        if (servers.get(serviceName) != null) {
            throw new UnsupportedOperationException("you must first stop stop server: " + serviceName + " before you want to start it again!");
        }

        ContextHandlerCollection handler = new ContextHandlerCollection();

        for (Map.Entry<String, Servlet> entry : servlets.entries()) {
            ServletContextHandler context = new ServletContextHandler();
            context.addServlet(new ServletHolder(entry.getValue()), entry.getKey());


            //TODO: add filters
//            HttpServerUtil.addFiltersToServletContextHandler(serviceName, context);

            for (Map.Entry<String, Filter> filterEntry : filters.entries()) {
                context.addFilter(new FilterHolder(filterEntry.getValue()), filterEntry.getKey(), EnumSet.allOf(DispatcherType.class));
            }

            handler.addHandler(context);
        }

        JettyHttpThreadPool jettyHttpThreadPool = new JettyHttpThreadPool(serviceName);
        Server server = new Server(jettyHttpThreadPool.threadPool);

        try {
            Configuration configuration = ConfigurationFactory.getConfiguration();

            // set connectors
            String host = configuration.getString("service." + serviceName + ".http.host", "0.0.0.0");
            int port = configuration.getInt("service." + serviceName + ".http.port", 8080);
            int connectionIdleTime = configuration.getInt("service." + serviceName + ".http.connectionIdleTime", 180000);
            boolean isBlockingChannelConnector = configuration.getBoolean("service." + serviceName + ".http.isBlockingChannelConnector",false);
            int numberOfAcceptors = configuration.getInt("service." + serviceName + ".http.numberOfAcceptors", 1);
            int acceptQueueSize = configuration.getInt("service." + serviceName + ".http.acceptQueueSize", 0);

            ServerConnector connector = getServerConnector(serviceName, server, configuration, host, port, connectionIdleTime, numberOfAcceptors, acceptQueueSize);


            boolean isSSL = StringUtils.isNotBlank(keyStorePath) && StringUtils.isNotBlank(keyStorePassword);
            Connector[] connectors = null;

            if(isSSL){
                String sslHost = configuration.getString("service." + serviceName + ".https.host", "0.0.0.0");
                int sslPort = configuration.getInt("service." + serviceName + ".https.port", 8090);

                SslContextFactory sslContextFactory = new SslContextFactory();
                sslContextFactory.setKeyStorePath(keyStorePath);
                sslContextFactory.setKeyStorePassword(keyStorePassword);

                SslConnectionFactory sslConnectionFactory = new SslConnectionFactory(sslContextFactory,"HTTP/1.1");
                ServerConnector sslConnector = getServerConnector(serviceName, server, configuration, sslHost, sslPort, connectionIdleTime, numberOfAcceptors, acceptQueueSize);
                Collection<ConnectionFactory> connectionFactories = new ArrayList<>(1);
                connectionFactories.add(sslConnectionFactory);
                sslConnector.setConnectionFactories(connectionFactories);

                connectors = new Connector[] { connector, sslConnector };
            }else{
                connectors = new Connector[] { connector };

            }

            server.setConnectors(connectors);

            // set servlets/context handlers
            server.setHandler(handler);

            server.start();
            servers.put(serviceName, server);
            LOGGER.info("Http server: {} started on {}", serviceName, port);

            // server.join();
        } catch (Exception e) {
            LOGGER.error("Problem starting the http {} server. Error is {}.", new Object[]{serviceName, e,e});
            throw new ServerFailedToStartException(e);
        }


	}

    private ServerConnector getServerConnector(String serviceName, Server server, Configuration configuration, String host, int port, int connectionIdleTime, int numberOfAcceptors, int acceptQueueSize) {
        ServerConnector connector;
        connector = new ServerConnector(server);

        connector.setAcceptQueueSize(acceptQueueSize);
        connector.setAcceptors(numberOfAcceptors);
        connector.setPort(port);
        connector.setHost(host);
        connector.setMaxIdleTime(connectionIdleTime);
        connector.setRequestHeaderSize(configuration.getInt("service." + serviceName + ".http.requestHeaderSize", connector.getRequestHeaderSize()));
        return connector;
    }



    @Override
	public void startHttpServer(String serviceName, ListMultimap<String, Servlet> servlets, String keyStorePath, String keyStorePassword) {
        ArrayListMultimap<String,Filter> filterMap = ArrayListMultimap.create();

		startHttpServer(serviceName, servlets, filterMap, keyStorePath, keyStorePassword);
	}

	/**
	 * stop the http server
	 * @param serviceName - the http logical service name
	 */
    @Override
	public void stopHttpServer(String serviceName) {
		Server server = servers.get(serviceName);
		if (server != null) {
			try {
				server.stop();
				servers.remove(serviceName);
				LOGGER.info("Http server: {} stopped", serviceName);
			} catch (Exception e) {
				LOGGER.error("Problem stoping the http {} server. Error is {}.", serviceName, e);
			}
		}
	}

}
