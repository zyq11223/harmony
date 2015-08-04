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
package edu.snu.cay.services.shuffle.evaluator.operator;

import edu.snu.cay.services.shuffle.description.ShuffleDescription;
import edu.snu.cay.services.shuffle.network.ShuffleTupleMessage;
import edu.snu.cay.services.shuffle.network.ShuffleTupleMessageHandler;
import edu.snu.cay.services.shuffle.strategy.ShuffleStrategy;
import org.apache.reef.io.network.Message;
import org.apache.reef.wake.EventHandler;

import javax.inject.Inject;
import java.util.List;

/**
 * Base implementation for ShuffleReceiver.
 */
public final class BaseShuffleReceiver<K, V> implements ShuffleReceiver<K, V> {

  private final String shuffleName;
  private final ShuffleDescription shuffleDescription;
  private final ShuffleStrategy<K> shuffleStrategy;
  private final ShuffleTupleMessageHandler shuffleTupleMessageHandler;

  @Inject
  private BaseShuffleReceiver(
      final ShuffleDescription shuffleDescription,
      final ShuffleStrategy<K> shuffleStrategy,
      final ShuffleTupleMessageHandler shuffleTupleMessageHandler) {
    this.shuffleName = shuffleDescription.getShuffleName();
    this.shuffleDescription = shuffleDescription;
    this.shuffleStrategy = shuffleStrategy;
    this.shuffleTupleMessageHandler = shuffleTupleMessageHandler;
  }

  @Override
  public void registerTupleMessageHandler(final EventHandler<Message<ShuffleTupleMessage<K, V>>> messageHandler) {
    shuffleTupleMessageHandler.registerMessageHandler(shuffleName, messageHandler);
  }

  @Override
  public ShuffleStrategy<K> getShuffleStrategy() {
    return shuffleStrategy;
  }

  @Override
  public List<String> getSelectedReceiverIdList(final K key) {
    return shuffleStrategy.selectReceivers(key, shuffleDescription.getReceiverIdList());
  }
}