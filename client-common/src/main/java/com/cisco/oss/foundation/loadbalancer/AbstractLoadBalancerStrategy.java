/*
 * Copyright 2015 Cisco Systems, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.cisco.oss.foundation.loadbalancer;

import com.cisco.oss.foundation.configuration.ConfigurationFactory;
import com.google.common.collect.Lists;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.remoting.RemoteAccessException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * This Class is the abstract implementation of the load balancing strategy.
 *
 * @author Yair Ogen
 */
public abstract class AbstractLoadBalancerStrategy<S extends ClientRequest> implements LoadBalancerStrategy<S> {

    protected static final Logger LOGGER = LoggerFactory.getLogger(AbstractLoadBalancerStrategy.class);

    static{
        try {
            Configuration configuration = ConfigurationFactory.getConfiguration();
        } catch (Exception e) {
            LOGGER.error("Can't assign service Directory host and port properties: {}",e ,e);
        }
    }
    public static final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private static final long serialVersionUID = -4787963395573781601L;
    protected List<InternalServerProxy> serverProxies;
    long waitingTime;
    String clientName;
    long retryDelay;
    int numberOfAttempts;
    private String serviceName = "UNKNOWN";

    public AbstractLoadBalancerStrategy(String serviceName, boolean serviceDirectoryEnabled, long waitingTime, String clientName, long retryDelay, int numberOfAttempts) {
        this.serviceName = serviceName;
        this.waitingTime = waitingTime;
        this.clientName = clientName;
        this.retryDelay = retryDelay;
        this.numberOfAttempts = numberOfAttempts;

    }


    public String getServiceName() {
        return serviceName;
    }



    public Throwable handleException(final String apiName, final InternalServerProxy serverProxy, final Throwable throwable) {

        // if the caught exception is of timeout nature, audit and throw a new
        // RequestTimeoutException directly to the client
        handleTimeout(apiName, throwable);

        // in these cases only, throw the exception to the caller:
        // 1. if the exception is instanceof NoActiveServersIOException - used
        // in UDP support.
        // 2. if not instanceof RemoteAccessException and not instanceof
        // IOException - in these cases continue trying with the next servers.
        String hostPort = serverProxy != null ? " at [" + serverProxy.getHost() + ":" + serverProxy.getPort() + "]" : "";
        String errorMessage = "Failed to invoke  '" + apiName + "' " + hostPort;
        String warnMessage = "Error occurred while invoking '" + apiName + "' " + hostPort;

        if (throwable instanceof UnresolvedAddressException) {
            LOGGER.debug("retrying in special case of 'UnresolvedAddressException'");
        } else {

//			final boolean firstInChain = ConfigurationFactory.getConfiguration().getBoolean(LoadBalancerConstants.DEFAULT_FIRST_IN_CHAIN);

            // don't try and find another active server if:
            // 1. the exception you caught is NoActiveServersDeadEndException
            // 2. the exception you caught is NoActiveServersIOException
            // 3. the exception you caught is (not RemoteAccessException) and
            // (not IOException) and (not NoActiveServersException but is
            // firstInChain)
            // if you caught NoActiveServersException but you are NOT
            // firstInChain - then throw back error and don't try to reconnect
            if (throwable instanceof NoActiveServersDeadEndException || throwable instanceof NoActiveServersIOException
                    || (!(throwable instanceof RemoteAccessException) && !(throwable instanceof NoActiveServersException) && !(throwable instanceof IOException) && (throwable.getCause() != null && throwable.getCause().getCause() != null && !(throwable.getCause().getCause() instanceof IOException)) && (throwable.getCause() != null && !(throwable.getCause() instanceof IOException)) || (/*!firstInChain &&*/ throwable instanceof NoActiveServersException))) {
                LOGGER.error(errorMessage, throwable);
                throw new ClientException(throwable.toString(), throwable);
            }
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.warn(warnMessage + "(attempt " + serverProxy.getCurrentNumberOfAttempts() + ", will retry in " + serverProxy.getRetryDelay() + " milliseconds)", throwable);
        } else {
            LOGGER.warn(warnMessage + "(attempt " + serverProxy.getCurrentNumberOfAttempts() + ", will retry in " + serverProxy.getRetryDelay() + " milliseconds)");
        }

        serverProxy.processFailureAttempt();

        if (serverProxy.getCurrentNumberOfAttempts() >= serverProxy.getMaxNumberOfAttempts()) {
            serverProxy.passivate();
        }

        return throwable;
    }

    private void handleTimeout(final String apiName, final Throwable throwable) {
        if (throwable instanceof RequestTimeoutException) {
            throw (RequestTimeoutException) throwable;
        } else if (throwable instanceof SocketTimeoutException || (throwable != null && throwable.getMessage() != null && (throwable.getMessage().contains("Inactivity timeout passed during read operation") || (throwable.getCause() instanceof SocketTimeoutException)))) {

            final RequestTimeoutException requestTimeoutException = new RequestTimeoutException("Error occurred while invoking the api: " + apiName, throwable.getCause());

            LOGGER.warn(requestTimeoutException.toString(), requestTimeoutException);

            throw requestTimeoutException;
        }
    }

    public void handleNullserverProxy(final String apiName, final Throwable lastCaugtException) {

        final String causedBy = lastCaugtException != null ? ("\"Caused by: " + lastCaugtException) : "\"";
        final NoActiveServersException noActiveServersException = new NoActiveServersException("No active servers were found in the server proxies list. API: \"" + apiName + "\"." + causedBy, lastCaugtException);

        if (this instanceof FailOverStrategy) {
            FailOverStrategy failOverStrategy = (FailOverStrategy) this;
            failOverStrategy.lastActive = null;
        }



        throw noActiveServersException;

    }

    public List<InternalServerProxy> getServerProxies() {
        return serverProxies;
    }

    @Override
    public void setServerProxies(final List<InternalServerProxy> serverProxies) {
        this.serverProxies = serverProxies;
    }

    private InternalServerProxy createInternalServerProxy(String host, int port) {
        final InternalServerProxy internalServerProxy = new InternalServerProxy(waitingTime, clientName);
        internalServerProxy.setRetryDelay(retryDelay);
        internalServerProxy.setMaxNumberOfAttempts(numberOfAttempts);
        internalServerProxy.setHost(host);
        internalServerProxy.setPort(port);
        return internalServerProxy;
    }


}
