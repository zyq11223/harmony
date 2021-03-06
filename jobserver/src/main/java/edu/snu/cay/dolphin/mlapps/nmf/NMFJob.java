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
package edu.snu.cay.dolphin.mlapps.nmf;

import edu.snu.cay.dolphin.core.client.ETDolphinConfiguration;
import edu.snu.cay.dolphin.jobserver.DolphinJobLauncher;
import edu.snu.cay.dolphin.mlapps.serialization.DenseVectorCodec;
import edu.snu.cay.utils.IntegerCodec;

import static edu.snu.cay.dolphin.mlapps.nmf.NMFParameters.*;

/**
 * Client for non-negative matrix factorization via SGD with JobServer.
 */
public final class NMFJob {

  /**
   * Should not be instantiated.
   */
  private NMFJob() {
  }

  public static void main(final String[] args) {
    DolphinJobLauncher.submitJob("NMF", args, ETDolphinConfiguration.newBuilder()
        .setTrainerClass(NMFTrainer.class)
        .setHasInputDataKey()
        .setInputParserClass(NMFETDataParser.class)
        .setInputKeyCodecClass(IntegerCodec.class)
        .setInputValueCodecClass(NMFDataCodec.class)
        .setModelKeyCodecClass(IntegerCodec.class)
        .setModelValueCodecClass(DenseVectorCodec.class)
        .setModelUpdateValueCodecClass(DenseVectorCodec.class)
        .setModelUpdateFunctionClass(NMFETModelUpdateFunction.class)
        .setHasLocalModelTable()
        .setLocalModelKeyCodecClass(IntegerCodec.class)
        .setLocalModelValueCodecClass(DenseVectorCodec.class)
        .setLocalModelUpdateValueCodecClass(DenseVectorCodec.class)
        .setLocalModelUpdateFunctionClass(NMFETModelUpdateFunction.class)
        .addParameterClass(Rank.class)
        .addParameterClass(PrintMatrices.class)
        .build());
  }
}
