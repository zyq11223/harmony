/**
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
package edu.snu.cay.services.shuffle.task;

import edu.snu.cay.services.shuffle.network.ShuffleTupleMessage;
import org.apache.reef.io.network.Message;
import org.apache.reef.tang.annotations.DefaultImplementation;
import org.apache.reef.wake.EventHandler;

/**
 * Receiving tuple operator. Users should register event handler for ShuffleTupleMessage to
 * receive tuples from other tasks to the shuffle of this receiver.
 */
@DefaultImplementation(BaseTupleReceiver.class)
public interface TupleReceiver<K, V> extends TupleOperator<K, V> {

  /**
   * Register a message handler takes the tuples arriving in the shuffle of the receiver.
   *
   * @param messageHandler event handler for ShuffleTupleMessage
   */
  void registerTupleMessageHandler(EventHandler<Message<ShuffleTupleMessage<K, V>>> messageHandler);

}
