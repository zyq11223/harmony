/*
 * Copyright (C) 2015 Seoul National University
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.snu.cay.services.em.ns;

import edu.snu.cay.services.em.ns.impl.NSWrapperImpl;
import org.apache.reef.driver.context.ServiceConfiguration;
import org.apache.reef.io.network.group.impl.driver.ExceptionHandler;
import org.apache.reef.io.network.naming.NameServer;
import org.apache.reef.tang.Configuration;
import org.apache.reef.tang.Configurations;
import org.apache.reef.tang.Tang;
import org.apache.reef.tang.exceptions.InjectionException;
import org.apache.reef.wake.EventHandler;
import org.apache.reef.wake.remote.Codec;
import org.apache.reef.wake.remote.address.LocalAddressProvider;

import javax.inject.Inject;

/**
 * Configuration class for NSWrapper.
 */
public final class NSWrapperConfiguration {

  private final String localNameServerAddr;
  private final Integer localNameServerPort;

  @Inject
  private NSWrapperConfiguration(final NameServer nameServer,
                                 final LocalAddressProvider localAddressProvider) throws InjectionException {
    this.localNameServerAddr = localAddressProvider.getLocalAddress();
    this.localNameServerPort = nameServer.getPort();
  }

  /**
   * Non-static method for NSWrapper configuration when only {@code codecClass} and
   * {@code recvHandlerClass} can be specified. Uses default values for
   * {@code exHandlerClass} and {@code networkServicePort}. Also asks Tang for
   * a NameServer injection to resolve NameServerAddr and NameServerPort.
   */
  public Configuration getConfiguration(final Class<? extends Codec<?>> codecClass,
                                        final Class<? extends EventHandler<?>> recvHandlerClass) {
    return getConfiguration(codecClass, recvHandlerClass, ExceptionHandler.class, 0);
  }

  /**
   * Non-static method for NSWrapper configuration when NameServerAddr and
   * NameServerPort cannot be known beforehand. Uses a NameServer instance
   * injected by Tang to compute NameServer-related parameters.
   */
  public Configuration getConfiguration(final Class<? extends Codec<?>> codecClass,
                                        final Class<? extends EventHandler<?>> recvHandlerClass,
                                        final Class<? extends EventHandler<?>> exHandlerClass,
                                        final Integer networkServicePort) {
    return getConfiguration(codecClass, recvHandlerClass, exHandlerClass, networkServicePort, localNameServerAddr, localNameServerPort);
  }

  /**
   * Static method for NSWrapper configuration when all parameters can be
   * specified by caller.
   */
  public static Configuration getConfiguration(final Class<? extends Codec<?>> codecClass,
                                               final Class<? extends EventHandler<?>> recvHandlerClass,
                                               final Class<? extends EventHandler<?>> exHandlerClass,
                                               final Integer networkServicePort,
                                               final String nameServerAddr,
                                               final Integer nameServerPort) {
    final Configuration parameterConf = Tang.Factory.getTang().newConfigurationBuilder()
        .bindNamedParameter(NSWrapperParameters.NetworkServiceCodec.class, codecClass)
        .bindNamedParameter(NSWrapperParameters.NetworkServiceHandler.class, recvHandlerClass)
        .bindNamedParameter(NSWrapperParameters.NetworkServiceExceptionHandler.class, exHandlerClass)
        .bindNamedParameter(NSWrapperParameters.NetworkServicePort.class, Integer.toString(networkServicePort))
        .bindNamedParameter(NSWrapperParameters.NameServerAddr.class, nameServerAddr)
        .bindNamedParameter(NSWrapperParameters.NameServerPort.class, Integer.toString(nameServerPort))
        .build();

    final Configuration serviceConf = ServiceConfiguration.CONF
        .set(ServiceConfiguration.SERVICES, NSWrapperImpl.class)
        .set(ServiceConfiguration.ON_CONTEXT_STOP, NSWrapperClosingHandler.class)
        .build();

    return Configurations.merge(parameterConf, serviceConf);
  }
}