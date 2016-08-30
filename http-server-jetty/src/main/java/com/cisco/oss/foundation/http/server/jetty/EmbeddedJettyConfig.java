package com.cisco.oss.foundation.http.server.jetty;

import com.cisco.oss.foundation.configuration.ConfigurationFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.webapp.WebAppContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.embedded.jetty.JettyEmbeddedServletContainerFactory;
import org.springframework.boot.context.embedded.jetty.JettyServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * Created by yaogen on 29/11/2015.
 */
@Configuration
public class EmbeddedJettyConfig {

    @Autowired
    private ConfigurationFactory configurationFactory = null;

    @Autowired(required = false)
    private ErrorHandler errorHandler;

    @Value("${spring.application.name}")
    private String serviceName = null;

    @Bean
    public JettyEmbeddedServletContainerFactory jettyEmbeddedServletContainerFactory() {

        int port = ConfigurationFactory.getConfiguration().getInt(serviceName + ".http.port", ConfigurationFactory.getConfiguration().getInt("server.port"));
        int maxThreads = ConfigurationFactory.getConfiguration().getInt(serviceName + ".http.maxThreads",100);
        int minThreads = ConfigurationFactory.getConfiguration().getInt(serviceName + ".http.minThreads",10);
        int idleTimeout = ConfigurationFactory.getConfiguration().getInt(serviceName + ".http.connectionIdleTime",180000);
        int acceptQueueSize = ConfigurationFactory.getConfiguration().getInt(serviceName + ".http.acceptQueueSize", 0);
        final JettyEmbeddedServletContainerFactory factory =  new JettyEmbeddedServletContainerFactory(Integer.valueOf(port));

        factory.addServerCustomizers(new JettyServerCustomizer() {
            @Override
            public void customize(final Server server) {
                // Tweak the connection pool used by Jetty to handle incoming HTTP connections
                final QueuedThreadPool threadPool = server.getBean(QueuedThreadPool.class);
                threadPool.setMaxThreads(Integer.valueOf(maxThreads));
                threadPool.setMinThreads(Integer.valueOf(minThreads));

                final ServerConnector connector = server.getBean(ServerConnector.class);
                connector.setIdleTimeout(idleTimeout);
                connector.setAcceptQueueSize(acceptQueueSize);

                if(errorHandler != null){
                    server.addBean(errorHandler);
                    Handler handler = server.getHandler();
                    if (handler instanceof WebAppContext){
                        ((WebAppContext)handler).setErrorHandler(errorHandler);
                    }
                }

//                threadPool.setIdleTimeout(Integer.valueOf(idleTimeout));
            }
        });

        return factory;
    }

}
