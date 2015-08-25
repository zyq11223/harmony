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
package edu.snu.cay.services.shuffle.evaluator;

import edu.snu.cay.services.shuffle.network.*;
import edu.snu.cay.services.shuffle.params.ShuffleParameters;
import org.apache.reef.annotations.audience.EvaluatorSide;
import org.apache.reef.io.network.ConnectionFactory;
import org.apache.reef.io.network.NetworkConnectionService;
import org.apache.reef.io.network.naming.NameServerParameters;
import org.apache.reef.tang.annotations.Parameter;
import org.apache.reef.wake.Identifier;
import org.apache.reef.wake.IdentifierFactory;

import javax.inject.Inject;

/**
 * Evaluator-side helper class that register connection factories for
 * ShuffleTupleMessage and ShuffleControlMessage.
 */
@EvaluatorSide
// TODO (#63) : better naming.
public final class ShuffleNetworkSetup {

  private final NetworkConnectionService networkConnectionService;
  private final Identifier tupleMessageNetworkId;
  private final Identifier controlMessageNetworkId;
  private final ShuffleTupleMessageCodec tupleMessageCodec;
  private final ShuffleTupleMessageHandler tupleMessageHandler;
  private final ShuffleTupleLinkListener tupleLinkListener;
  private final ShuffleControlMessageCodec controlMessageCodec;
  private final ShuffleControlMessageHandler controlMessageHandler;
  private final ShuffleControlLinkListener controlLinkListener;

  @Inject
  private ShuffleNetworkSetup(
      @Parameter(NameServerParameters.NameServerIdentifierFactory.class) final IdentifierFactory idFactory,
      final NetworkConnectionService networkConnectionService,
      final ShuffleTupleMessageCodec tupleMessageCodec,
      final ShuffleTupleMessageHandler tupleMessageHandler,
      final ShuffleTupleLinkListener tupleLinkListener,
      final ShuffleControlMessageCodec controlMessageCodec,
      final ShuffleControlMessageHandler controlMessageHandler,
      final ShuffleControlLinkListener controlLinkListener) {
    this.networkConnectionService = networkConnectionService;
    this.tupleMessageNetworkId = idFactory.getNewInstance(ShuffleParameters.SHUFFLE_TUPLE_MSG_NETWORK_ID);
    this.controlMessageNetworkId = idFactory.getNewInstance(ShuffleParameters.SHUFFLE_CONTROL_MSG_NETWORK_ID);
    this.tupleMessageCodec = tupleMessageCodec;
    this.tupleMessageHandler = tupleMessageHandler;
    this.tupleLinkListener = tupleLinkListener;
    this.controlMessageCodec = controlMessageCodec;
    this.controlMessageHandler = controlMessageHandler;
    this.controlLinkListener = controlLinkListener;
  }

  /**
   * @return the connection factory for ShuffleControlMessage
   */
  public ConnectionFactory<ShuffleControlMessage> getControlConnectionFactory() {
    return networkConnectionService.getConnectionFactory(controlMessageNetworkId);
  }

  /**
   * @return the connection factory for ShuffleTupleMessage
   */
  public ConnectionFactory<ShuffleTupleMessage> getTupleConnectionFactory() {
    return networkConnectionService.getConnectionFactory(tupleMessageNetworkId);
  }

  /**
   * Register connection factories for ShuffleTupleMessage and ShuffleControlMessage.
   */
  public void registerConnectionFactories(final Identifier localEndPointId) {
    networkConnectionService.registerConnectionFactory(
        tupleMessageNetworkId, tupleMessageCodec, tupleMessageHandler, tupleLinkListener, localEndPointId);
    networkConnectionService.registerConnectionFactory(
        controlMessageNetworkId, controlMessageCodec, controlMessageHandler, controlLinkListener, localEndPointId);
  }

  /**
   * Unregister connection factories for ShuffleTupleMessage and ShuffleControlMessage.
   */
  public void unregisterConnectionFactories() {
    networkConnectionService.unregisterConnectionFactory(tupleMessageNetworkId);
    networkConnectionService.unregisterConnectionFactory(controlMessageNetworkId);
  }
}