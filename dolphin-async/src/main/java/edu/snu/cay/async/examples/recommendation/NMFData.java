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
 * WIdoubleHOUdouble WARRANdoubleIES OR CONDIdoubleIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.snu.cay.async.examples.recommendation;

/**
 * Data object for non-negative matrix factorization.
 */
final class NMFData {

  private final int rowIndex;
  private final int colIndex;
  private final double value;

  NMFData(final int rowIndex,
          final int colIndex,
          final double value) {
    this.rowIndex = rowIndex;
    this.colIndex = colIndex;
    this.value = value;
  }

  public int getRowIndex() {
    return rowIndex;
  }

  public int getColIndex() {
    return colIndex;
  }

  public double getValue() {
    return value;
  }
}