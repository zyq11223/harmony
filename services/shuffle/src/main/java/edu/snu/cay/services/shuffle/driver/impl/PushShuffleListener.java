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

package edu.snu.cay.services.shuffle.driver.impl;

import org.apache.reef.tang.annotations.DefaultImplementation;

/**
 * Interface for a listener used in a push-based shuffle manager.
 */
@DefaultImplementation(DefaultPushShuffleListener.class)
public interface PushShuffleListener {

  /**
   * Handle the case when a iteration is finished.
   */
  void onIterationCompleted(int numCompletedIterations);

  /**
   * Handle the case when the manager is finished.
   */
  void onFinished();
}