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
package edu.snu.cay.dolphin.bsp.mlapps.data;

import edu.snu.cay.dolphin.bsp.mlapps.sub.DenseVectorCodec;
import edu.snu.cay.services.em.serialize.Serializer;
import org.apache.reef.io.serialization.Codec;
import org.apache.reef.tang.annotations.Parameter;

import javax.inject.Inject;

/**
 * A Serializer for Dolphin jobs with a single DenseVector dataType.
 * For example, use this if the job only uses data from {@link ClusteringDataParser}.
 */
public final class ClusteringSerializer implements Serializer {

  /**
   * Type used in Elastic Memory to put/get the data.
   */
  private final String dataType;

  private final DenseVectorCodec denseVectorCodec;

  @Inject
  private ClusteringSerializer(@Parameter(ClusteringDataType.class) final String dataType,
                               final DenseVectorCodec denseVectorCodec) {
    this.dataType = dataType;
    this.denseVectorCodec = denseVectorCodec;
  }

  @Override
  public Codec getCodec(final String name) {
    if (name.equals(dataType)) {
      return denseVectorCodec;
    } else {
      throw new RuntimeException("Unknown name " + name);
    }
  }
}