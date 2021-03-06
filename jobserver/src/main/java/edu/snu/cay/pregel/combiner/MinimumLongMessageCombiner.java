/*
 * Copyright (C) 2017 Seoul National University
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
package edu.snu.cay.pregel.combiner;

import javax.inject.Inject;

/**
 * A combiner that finds the message with the minimum Long value.
 */
public final class MinimumLongMessageCombiner implements MessageCombiner<Long, Long> {

  @Inject
  private MinimumLongMessageCombiner() {

  }

  @Override
  public Long combine(final Long vertexId,
                      final Long originalMessage, final Long messageToCombine) {
    return Math.min(originalMessage, messageToCombine);
  }
}
