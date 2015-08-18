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
package edu.snu.cay.services.shuffle.evaluator.operator.impl;

import edu.snu.cay.services.shuffle.evaluator.DataReceiver;
import edu.snu.cay.services.shuffle.evaluator.operator.PushShuffleReceiver;
import org.apache.reef.io.Tuple;

import javax.inject.Inject;
import java.util.List;

/**
 *
 */
public final class PushShuffleReceiverImpl<K, V> implements PushShuffleReceiver<K, V> {

  private final DataReceiver<K, V> dataReceiver;
  @Inject
  private PushShuffleReceiverImpl(
      final DataReceiver<K, V> dataReceiver) {
    this.dataReceiver = dataReceiver;
  }

  @Override
  public List<Tuple<K, V>> receive() {
    return null;
  }
}
