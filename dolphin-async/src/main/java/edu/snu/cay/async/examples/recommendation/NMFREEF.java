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
package edu.snu.cay.async.examples.recommendation;

import edu.snu.cay.async.examples.recommendation.NMFParameters.*;
import edu.snu.cay.async.AsyncDolphinConfiguration;
import edu.snu.cay.async.AsyncDolphinLauncher;

/**
 * Client for non-negative matrix factorization via SGD.
 */
public final class NMFREEF {

  /**
   * Should not be instantiated.
   */
  private NMFREEF() {
  }

  public static void main(final String[] args) {
    AsyncDolphinLauncher.launch("MatrixFactorizationREEF", args, AsyncDolphinConfiguration.newBuilder()
        .setWorkerClass(NMFWorker.class)
        .setUpdaterClass(NMFUpdater.class)
        .setPreValueCodecClass(DenseVectorCodec.class)
        .setValueCodecClass(DenseVectorCodec.class)
        .addParameterClass(NumRows.class)
        .addParameterClass(NumColumns.class)
        .addParameterClass(Rank.class)
        .addParameterClass(StepSize.class)
        .addParameterClass(Lambda.class)
        .build());
  }
}