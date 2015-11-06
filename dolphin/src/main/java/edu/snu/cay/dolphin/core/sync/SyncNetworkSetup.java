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
package edu.snu.cay.dolphin.core.sync;

import edu.snu.cay.dolphin.core.sync.avro.AvroSyncMessage;
import edu.snu.cay.dolphin.core.sync.parameters.SyncMessageHandler;
import edu.snu.cay.dolphin.core.sync.parameters.SyncCodec;
import org.apache.reef.exception.evaluator.NetworkException;
import org.apache.reef.io.network.ConnectionFactory;
import org.apache.reef.io.network.Message;
import org.apache.reef.io.network.NetworkConnectionService;
import org.apache.reef.tang.Configuration;
import org.apache.reef.tang.Tang;
import org.apache.reef.tang.annotations.Parameter;
import org.apache.reef.wake.EventHandler;
import org.apache.reef.wake.Identifier;
import org.apache.reef.wake.IdentifierFactory;
import org.apache.reef.wake.remote.Codec;

import javax.inject.Inject;

public final class SyncNetworkSetup {
  private static final String PAUSE_IDENTIFIER = "PAUSE";

  private final NetworkConnectionService networkConnectionService;
  private final Identifier connectionFactoryIdentifier;
  private final Codec<AvroSyncMessage> codec;
  private final EventHandler<Message<AvroSyncMessage>> handler;
  private ConnectionFactory<AvroSyncMessage> connectionFactory;

  @Inject
  private SyncNetworkSetup(
      final NetworkConnectionService networkConnectionService,
      final IdentifierFactory identifierFactory,
      @Parameter(SyncCodec.class) final Codec<AvroSyncMessage> codec,
      @Parameter(SyncMessageHandler.class) final EventHandler<Message<AvroSyncMessage>> handler
  ) throws NetworkException {
    this.networkConnectionService = networkConnectionService;
    this.connectionFactoryIdentifier = identifierFactory.getNewInstance(PAUSE_IDENTIFIER);
    this.codec = codec;
    this.handler = handler;
  }

  public ConnectionFactory<AvroSyncMessage> registerConnectionFactory(
      final Identifier localEndPointId) {
    connectionFactory = networkConnectionService.registerConnectionFactory(connectionFactoryIdentifier,
        codec, handler, null, localEndPointId);
    return connectionFactory;
  }

  public void unregisterConnectionFactory() {
    networkConnectionService.unregisterConnectionFactory(connectionFactoryIdentifier);
  }

  public ConnectionFactory<AvroSyncMessage> getConnectionFactory() {
    return connectionFactory;
  }

  public Identifier getMyId() {
    return connectionFactory.getLocalEndPointId();
  }

  public static Configuration getDriverConfiguration() {
    return Tang.Factory.getTang().newConfigurationBuilder()
        .bindNamedParameter(SyncCodec.class, SyncMessageCodec.class)
        .bindNamedParameter(SyncMessageHandler.class, DriverSync.class)
        .build();
  }

  public static Configuration getControllerTaskConfiguration() {
    return Tang.Factory.getTang().newConfigurationBuilder()
        .bindNamedParameter(SyncCodec.class, SyncMessageCodec.class)
        .bindNamedParameter(SyncMessageHandler.class, ControllerTaskSync.class)
        .build();
  }
}