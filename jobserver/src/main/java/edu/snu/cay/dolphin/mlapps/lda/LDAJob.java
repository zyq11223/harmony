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
package edu.snu.cay.dolphin.mlapps.lda;

import edu.snu.cay.dolphin.core.client.ETDolphinConfiguration;
import edu.snu.cay.dolphin.jobserver.DolphinJobLauncher;
import edu.snu.cay.utils.IntegerCodec;
import edu.snu.cay.utils.LongCodec;
import org.apache.reef.io.serialization.SerializableCodec;

import javax.inject.Inject;

/**
 * Run Latent Dirichlet Allocation algorithm on dolphin JobServer.
 * Input dataset should be preprocessed to have continuous (no missing) vocabulary indices.
 */
public final class LDAJob {

  @Inject
  private LDAJob() {
  }

  public static void main(final String[] args) {
    DolphinJobLauncher.submitJob("LDA", args, ETDolphinConfiguration.newBuilder()
        .setTrainerClass(LDATrainer.class)
        .setInputParserClass(LDAETDataParser.class)
        .setInputKeyCodecClass(LongCodec.class)
        .setInputValueCodecClass(LDADataCodec.class)
        .setModelKeyCodecClass(IntegerCodec.class)
        .setModelValueCodecClass(SparseArrayCodec.class)
        .setModelUpdateValueCodecClass(SerializableCodec.class)
        .setModelUpdateFunctionClass(LDAETModelUpdateFunction.class)
        .setHasLocalModelTable()
        .setLocalModelKeyCodecClass(LongCodec.class)
        .setLocalModelValueCodecClass(LDALocalModelCodec.class)
        .addParameterClass(LDAParameters.Alpha.class)
        .addParameterClass(LDAParameters.Beta.class)
        .addParameterClass(LDAParameters.NumTopics.class)
        .addParameterClass(LDAParameters.NumVocabs.class)
        .build());
  }
}
