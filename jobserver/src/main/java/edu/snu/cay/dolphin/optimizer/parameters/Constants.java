/*
 * Copyright (C) 2016 Seoul National University
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
package edu.snu.cay.dolphin.optimizer.parameters;

/**
 * Constants used for optimization in driver.
 */
public final class Constants {
  /**
   * Empty private constructor to prohibit instantiation of utility class.
   */
  private Constants() {
  }

  public static final String NAMESPACE_SERVER = "SERVER";
  public static final String NAMESPACE_WORKER = "WORKER";
  
  public static final String TOTAL_PULLS_PER_MINI_BATCH = "PARAMETER_WORKER_TOTAL_PULLS_PER_BATCH";
  public static final String AVG_PULL_SIZE_PER_MINI_BATCH = "PARAMETER_WORKER_AVG_PULL_SIZE_PER_MINI_BATCH";
  public static final String NUM_MODEL_BLOCKS = "NUM_MODEL_BLOCKS";
  public static final String NUM_DATA_BLOCKS = "NUM_DATA_BLOCKS";

}
