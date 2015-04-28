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
package edu.snu.reef.flexion.examples.ml.algorithms.clustering;

import edu.snu.reef.flexion.core.StageInfo;
import edu.snu.reef.flexion.examples.ml.sub.VectorListCodec;

public class ClusteringPreStageBuilder {
  public static StageInfo build() {
    return StageInfo.newBuilder(ClusteringPreCmpTask.class, ClusteringPreCtrlTask.class, ClusteringPreCommGroup.class)
        .setGather(VectorListCodec.class).build();
  }
}
