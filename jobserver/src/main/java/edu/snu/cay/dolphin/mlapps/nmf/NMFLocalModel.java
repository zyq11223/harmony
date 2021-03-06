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

import edu.snu.cay.common.math.linalg.Vector;

import java.util.Map;

/**
 * Encapsulates the worker-local model in NMF app.
 */
final class NMFLocalModel {
  /**
   * Row-wise representation of left matrix.
   * The map's key is a row index of matrix and value is a vector representing the row.
   */
  private final Map<Integer, Vector> lMatrix;

  NMFLocalModel(final Map<Integer, Vector> lMatrix) {
    this.lMatrix = lMatrix;
  }

  /**
   * @return the L matrix
   */
  Map<Integer, Vector> getLMatrix() {
    return lMatrix;
  }
}
