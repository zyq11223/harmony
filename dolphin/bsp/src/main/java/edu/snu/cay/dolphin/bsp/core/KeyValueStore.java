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
package edu.snu.cay.dolphin.bsp.core;

import javax.inject.Inject;
import java.util.HashMap;

/**
 * Simple Key-value store.
 */
public final class KeyValueStore {
  private final HashMap<Class<? extends Key>, Object> hashMap;

  @Inject
  private KeyValueStore() {
    hashMap = new HashMap<>();
  }

  /**
   * Put a data item that maps to certain {@code key} to this store.
   * @param key a key of mapping
   * @param value a value of mapping
   * @param <T> a type of value
   */
  public <T> void put(final Class<? extends Key<T>> key, final T value) {
    hashMap.put(key, value);
  }

  /**
   * Get a data item mapping to {@code key} from this store.
   * @param key a key of mapping
   * @param <T> a type of value
   * @return a data item, which is a value of mapping
   */
  public <T> T get(final Class<? extends Key<T>> key) {
    return (T) hashMap.get(key);
  }
}