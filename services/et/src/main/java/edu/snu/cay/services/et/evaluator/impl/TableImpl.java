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
package edu.snu.cay.services.et.evaluator.impl;

import edu.snu.cay.services.et.avro.OpType;
import edu.snu.cay.services.et.configuration.parameters.ExecutorIdentifier;
import edu.snu.cay.services.et.configuration.parameters.KeyCodec;
import edu.snu.cay.services.et.configuration.parameters.TableIdentifier;
import edu.snu.cay.services.et.evaluator.api.*;
import edu.snu.cay.services.et.exceptions.BlockNotExistsException;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.reef.annotations.audience.EvaluatorSide;
import org.apache.reef.io.network.impl.StreamingCodec;
import org.apache.reef.tang.annotations.Parameter;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;

/**
 * An implementation of {@link Table}.
 * It provides a shared table interface to users, by providing transparent access to distributed tablets.
 * Specifically, it routes accesses to local tablet or remote executors that own other tablets
 * based on the block ownership status using {@link OwnershipCache}.
 */
@EvaluatorSide
@ThreadSafe
public final class TableImpl<K, V, U> implements Table<K, V, U> {
  private static final String NULL_VALUE_ERR_MSG = "Value should not be null";

  /**
   * Table identifier.
   */
  private final String tableId;

  /**
   * Local executor identifier.
   */
  private final String executorId;

  /**
   * A key codec.
   */
  private final StreamingCodec<K> keyCodec;

  /**
   * Internal components for a table.
   */
  private final TableComponents tableComponents;

  /**
   * A tablet which means an abstraction of local portion of table.
   */
  private final Tablet<K, V, U> tablet;

  /**
   * A component for accessing remote blocks.
   */
  private final RemoteAccessOpSender remoteAccessOpSender;

  /**
   * Partition function that resolves key into block id.
   */
  private final BlockPartitioner<K> blockPartitioner;

  @Inject
  private TableImpl(@Parameter(TableIdentifier.class) final String tableId,
                    @Parameter(ExecutorIdentifier.class) final String executorId,
                    @Parameter(KeyCodec.class) final StreamingCodec<K> keyCodec,
                    final TableComponents tableComponents,
                    final Tablet<K, V, U> tablet,
                    final RemoteAccessOpSender remoteAccessOpSender,
                    final BlockPartitioner<K> blockPartitioner) {
    this.tableId = tableId;
    this.executorId = executorId;
    this.keyCodec = keyCodec;
    this.tableComponents = tableComponents;
    this.tablet = tablet;
    this.remoteAccessOpSender = remoteAccessOpSender;
    this.blockPartitioner = blockPartitioner;
  }

  @Override
  public DataOpResult<V> put(final K key, @Nonnull final V value) {
    return putInternal(key, value, true);
  }

  @Override
  public void putNoReply(final K key, @Nonnull final V value) {
    putInternal(key, value, false);
  }

  private DataOpResult<V> putInternal(final K key, @Nonnull final V value, final boolean replyRequired) {
    if (value == null) {
      throw new NullPointerException(NULL_VALUE_ERR_MSG);
    }

    final EncodedKey<K> encodedKey = new EncodedKey<>(key, keyCodec);

    final int blockId = blockPartitioner.getBlockId(encodedKey);
    final Optional<String> remoteIdOptional;

    final Pair<Optional<String>, Lock> remoteIdWithLock =
        tableComponents.getOwnershipCache().resolveExecutorWithLock(blockId);
    try {
      remoteIdOptional = remoteIdWithLock.getKey();

      // execute operation in local, holding ownershipLock
      if (!remoteIdOptional.isPresent()) {
        final V result = tablet.put(blockId, key, value);
        return new SingleKeyDataOpResult<>(result, true);
      } else {
        final DataOpResult<V> dataOpResult = new SingleKeyDataOpResult<>();
        // send operation to remote
        remoteAccessOpSender.sendSingleKeyOpToRemote(
            OpType.PUT, tableId, blockId, key, value, null,
            remoteIdOptional.get(), replyRequired, tableComponents, dataOpResult);

        return dataOpResult;
      }
    } catch (final BlockNotExistsException e) {
      throw new RuntimeException(e);
    } finally {
      final Lock ownershipLock = remoteIdWithLock.getValue();
      ownershipLock.unlock();
    }
  }

  @Override
  public DataOpResult<V> putIfAbsent(final K key, @Nonnull final V value) {
    return putIfAbsentInternal(key, value, true);
  }

  @Override
  public void putIfAbsentNoReply(final K key, @Nonnull final V value) {
    putIfAbsentInternal(key, value, false);
  }

  @Override
  public DataOpResult<Map<K, V>> multiPut(final List<Pair<K, V>> kvList) {

    final Map<Integer, List<Pair<K, V>>> blockToPairListMap = new HashMap<>();
    for (final Pair<K, V> kvPair : kvList) {
      if (kvPair.getRight() == null) {
        throw new NullPointerException(NULL_VALUE_ERR_MSG);
      }

      final K key = kvPair.getLeft();
      final int blockId = blockPartitioner.getBlockId(key);
      blockToPairListMap.computeIfAbsent(blockId, b -> new ArrayList<>()).add(kvPair);
    }

    final DataOpResult<Map<K, V>> aggregateDataOpResult = new MultiKeyDataOpResult<>(blockToPairListMap.size());
    blockToPairListMap.forEach((blockId, kvPairList) -> {
      final Pair<Optional<String>, Lock> remoteIdWithLock =
          tableComponents.getOwnershipCache().resolveExecutorWithLock(blockId);
      final Optional<String> remoteIdOptional;
      remoteIdOptional = remoteIdWithLock.getKey();
      try {
        // execute operation in local
        if (!remoteIdOptional.isPresent()) {
          final Map<K, V> localResultMap = new HashMap<>();
          for (final Pair<K, V> pair : kvPairList) {
            final V output = tablet.put(blockId, pair.getKey(), pair.getValue());
            if (output != null) {
              localResultMap.put(pair.getKey(), output);
            }
          }
          aggregateDataOpResult.onCompleted(localResultMap, true);
        } else {

          final List<K> keyList = new ArrayList<>(kvPairList.size());
          final List<V> valueList = new ArrayList<>(kvPairList.size());
          kvPairList.forEach(pair -> {
            keyList.add(pair.getKey());
            valueList.add(pair.getValue());
          });

          // send operation to remote
          remoteAccessOpSender.sendMultiKeyOpToRemote(OpType.PUT, tableId, blockId, keyList, valueList,
              Collections.emptyList(), remoteIdOptional.get(), true, tableComponents, aggregateDataOpResult);
        }
      } catch (BlockNotExistsException e) {
        throw new RuntimeException(e);
      } finally {
        final Lock ownershipLock = remoteIdWithLock.getValue();
        ownershipLock.unlock();
      }
    });

    return aggregateDataOpResult;
  }

  private DataOpResult<V> putIfAbsentInternal(final K key, @Nonnull final V value, final boolean replyRequired) {
    if (value == null) {
      throw new NullPointerException(NULL_VALUE_ERR_MSG);
    }

    final EncodedKey<K> encodedKey = new EncodedKey<>(key, keyCodec);

    final int blockId = blockPartitioner.getBlockId(encodedKey);
    final Optional<String> remoteIdOptional;

    final Pair<Optional<String>, Lock> remoteIdWithLock =
        tableComponents.getOwnershipCache().resolveExecutorWithLock(blockId);
    try {
      remoteIdOptional = remoteIdWithLock.getKey();

      // execute operation in local, holding ownershipLock
      if (!remoteIdOptional.isPresent()) {
        final V result = tablet.putIfAbsent(blockId, key, value);
        return new SingleKeyDataOpResult<>(result, true);
      } else {

        final DataOpResult<V> dataOpResult = new SingleKeyDataOpResult<>();
        // send operation to remote
        remoteAccessOpSender.sendSingleKeyOpToRemote(
            OpType.PUT_IF_ABSENT, tableId, blockId, key, value, null,
            remoteIdOptional.get(), replyRequired, tableComponents, dataOpResult);
        return dataOpResult;
      }
    } catch (final BlockNotExistsException e) {
      throw new RuntimeException(e);
    } finally {
      final Lock ownershipLock = remoteIdWithLock.getValue();
      ownershipLock.unlock();
    }

  }

  @Override
  public DataOpResult<V> get(final K key, final boolean copy) {
    final EncodedKey<K> encodedKey = new EncodedKey<>(key, keyCodec);

    final int blockId = blockPartitioner.getBlockId(encodedKey);
    final Optional<String> remoteIdOptional;

    final Pair<Optional<String>, Lock> remoteIdWithLock =
        tableComponents.getOwnershipCache().resolveExecutorWithLock(blockId);
    try {
      remoteIdOptional = remoteIdWithLock.getKey();

      // execute operation in local, holding ownershipLock
      if (!copy && !remoteIdOptional.isPresent()) {
        final V result = tablet.get(blockId, key);
        return new SingleKeyDataOpResult<>(result, true);
      } else {
        // TODO #11: Optimize local access routine of get operation
        final String targetExecutorId = remoteIdOptional.orElse(executorId);

        final DataOpResult<V> dataOpResult = new SingleKeyDataOpResult<>();
        // send operation to remote
        remoteAccessOpSender.sendSingleKeyOpToRemote(
            OpType.GET, tableId, blockId, key, null, null,
            targetExecutorId, true, tableComponents, dataOpResult);

        return dataOpResult;
      }
    } catch (final BlockNotExistsException e) {
      throw new RuntimeException(e);
    } finally {
      final Lock ownershipLock = remoteIdWithLock.getValue();
      ownershipLock.unlock();
    }
  }

  @Override
  public Future<Map<K, V>> multiGet(final List<K> keys, final boolean copy) {
    final Map<Integer, List<K>> blockToKeyListMap = new HashMap<>();
    for (final K key : keys) {
      final int blockId = blockPartitioner.getBlockId(key);
      blockToKeyListMap.computeIfAbsent(blockId, b -> new ArrayList<>()).add(key);
    }

    final DataOpResult<Map<K, V>> aggregateDataOpResult = new MultiKeyDataOpResult<>(blockToKeyListMap.size());
    blockToKeyListMap.forEach((blockId, keyList) -> {
      final Pair<Optional<String>, Lock> remoteIdWithLock =
          tableComponents.getOwnershipCache().resolveExecutorWithLock(blockId);
      final Optional<String> remoteIdOptional;
      remoteIdOptional = remoteIdWithLock.getKey();
      try {
        // execute operation in local
        if (!copy && !remoteIdOptional.isPresent()) {
          final Map<K, V> localResultMap = new HashMap<>();
          for (final K key : keyList) {
            final V output = tablet.get(blockId, key);
            if (output != null) {
              localResultMap.put(key, output);
            }
          }
          aggregateDataOpResult.onCompleted(localResultMap, true);
        } else {
          // TODO #11: Optimize local access routine of get operation
          final String targetExecutorId = remoteIdOptional.orElse(executorId);

          // send operation to remote
          remoteAccessOpSender.sendMultiKeyOpToRemote(OpType.GET, tableId, blockId, keyList,
              Collections.emptyList(), Collections.emptyList(), targetExecutorId,
              true, tableComponents, aggregateDataOpResult);
        }
      } catch (BlockNotExistsException e) {
        throw new RuntimeException(e);
      } finally {
        final Lock ownershipLock = remoteIdWithLock.getValue();
        ownershipLock.unlock();
      }
    });

    return aggregateDataOpResult;
  }

  @Override
  public Future<V> getOrInit(final K key, final boolean copy) {
    final EncodedKey<K> encodedKey = new EncodedKey<>(key, keyCodec);

    final int blockId = blockPartitioner.getBlockId(encodedKey);
    final Optional<String> remoteIdOptional;

    final Pair<Optional<String>, Lock> remoteIdWithLock =
        tableComponents.getOwnershipCache().resolveExecutorWithLock(blockId);
    try {
      remoteIdOptional = remoteIdWithLock.getKey();

      // execute operation in local, holding ownershipLock
      if (!copy && !remoteIdOptional.isPresent()) {
        final V result = tablet.getOrInit(blockId, key);
        return new SingleKeyDataOpResult<>(result, true);
      } else {
        // TODO #11: Optimize local access routine of get operation
        final String targetExecutorId = remoteIdOptional.orElse(executorId);

        final DataOpResult<V> dataOpResult = new SingleKeyDataOpResult<>();
        // send operation to remote
        remoteAccessOpSender.sendSingleKeyOpToRemote(
            OpType.GET_OR_INIT, tableId, blockId, key, null, null,
            targetExecutorId, true, tableComponents, dataOpResult);

        return dataOpResult;
      }
    } catch (final BlockNotExistsException e) {
      throw new RuntimeException(e);
    } finally {
      final Lock ownershipLock = remoteIdWithLock.getValue();
      ownershipLock.unlock();
    }

  }

  @Override
  public Future<Map<K, V>> multiGetOrInit(final List<K> keys, final boolean copy) {
    final Map<Integer, List<K>> blockToKeyListMap = new HashMap<>();
    for (final K key : keys) {
      final int blockId = blockPartitioner.getBlockId(key);
      blockToKeyListMap.computeIfAbsent(blockId, b -> new ArrayList<>()).add(key);
    }

    final DataOpResult<Map<K, V>> aggregateDataOpResult = new MultiKeyDataOpResult<>(blockToKeyListMap.size());
    blockToKeyListMap.forEach((blockId, keyList) -> {
      final Pair<Optional<String>, Lock> remoteIdWithLock =
          tableComponents.getOwnershipCache().resolveExecutorWithLock(blockId);
      final Optional<String> remoteIdOptional;
      remoteIdOptional = remoteIdWithLock.getKey();
      try {
        // execute operation in local
        if (!copy && !remoteIdOptional.isPresent()) {
          final Map<K, V> localResultMap = new HashMap<>();
          for (final K key : keyList) {
            final V output = tablet.getOrInit(blockId, key);
            if (output != null) {
              localResultMap.put(key, output);
            }
          }
          aggregateDataOpResult.onCompleted(localResultMap, true);
        } else {
          // TODO #11: Optimize local access routine of get operation
          final String targetExecutorId = remoteIdOptional.orElse(executorId);

          // send operation to remote
          remoteAccessOpSender.sendMultiKeyOpToRemote(OpType.GET_OR_INIT, tableId, blockId, keyList,
              Collections.emptyList(), Collections.emptyList(), targetExecutorId,
              true, tableComponents, aggregateDataOpResult);
        }
      } catch (BlockNotExistsException e) {
        throw new RuntimeException(e);
      } finally {
        final Lock ownershipLock = remoteIdWithLock.getValue();
        ownershipLock.unlock();
      }
    });

    return aggregateDataOpResult;
  }

  @Override
  public DataOpResult<V> update(final K key, @Nonnull final U updateValue) {
    return updateInternal(key, updateValue, true);
  }

  @Override
  public void updateNoReply(final K key, @Nonnull final U updateValue) {
    updateInternal(key, updateValue, false);
  }

  private DataOpResult<V> updateInternal(final K key, final U updateValue, final boolean replyRequired) {
    if (updateValue == null) {
      throw new NullPointerException(NULL_VALUE_ERR_MSG);
    }

    final EncodedKey<K> encodedKey = new EncodedKey<>(key, keyCodec);

    final int blockId = blockPartitioner.getBlockId(encodedKey);

    final Pair<Optional<String>, Lock> remoteIdWithLock =
        tableComponents.getOwnershipCache().resolveExecutorWithLock(blockId);

    final Optional<String> remoteIdOptional = remoteIdWithLock.getKey();
    // TODO #11: Optimize local access routine of update operation
    // since UPDATE is write operation, we cannot bypass operation queue even it's for local blocks
    final String targetExecutorId = remoteIdOptional.orElse(executorId);
    try {
      final DataOpResult<V> dataOpResult = new SingleKeyDataOpResult<>();
      // send operation to remote or local queue
      remoteAccessOpSender.sendSingleKeyOpToRemote(
          OpType.UPDATE, tableId, blockId, key, null,
          updateValue, targetExecutorId, replyRequired, tableComponents, dataOpResult);

      return dataOpResult;
    } finally {
      final Lock ownershipLock = remoteIdWithLock.getValue();
      ownershipLock.unlock();
    }
  }

  @Override
  public Future<Map<K, V>> multiUpdate(final Map<K, U> kuMap) {
    return multiUpdateInternal(kuMap, true);
  }

  @Override
  public void multiUpdateNoReply(final Map<K, U> kuMap) {
    multiUpdateInternal(kuMap, false);
  }

  private DataOpResult<Map<K, V>> multiUpdateInternal(final Map<K, U> kuMap, final boolean replyRequired) {

    final Map<Integer, Map<K, U>> blockToSubMaps = new HashMap<>();

    for (final Map.Entry<K, U> kuEntry : kuMap.entrySet()) {
      if (kuEntry.getValue() == null) {
        throw new NullPointerException(NULL_VALUE_ERR_MSG);
      }

      final K key = kuEntry.getKey();
      final int blockId = blockPartitioner.getBlockId(key);
      blockToSubMaps.putIfAbsent(blockId, new HashMap<>());
      blockToSubMaps.get(blockId).put(key, kuEntry.getValue());
    }

    final DataOpResult<Map<K, V>> aggregateDataOpResult = new MultiKeyDataOpResult<>(blockToSubMaps.size());
    blockToSubMaps.forEach((blockId, subMap) -> {
      final Pair<Optional<String>, Lock> remoteIdWithLock =
          tableComponents.getOwnershipCache().resolveExecutorWithLock(blockId);
      final Optional<String> remoteIdOptional = remoteIdWithLock.getKey();
      // TODO #11: Optimize local access routine of update operation
      // since UPDATE is write operation, we cannot bypass operation queue even it's for local blocks
      final String targetExecutorId = remoteIdOptional.orElse(executorId);
      try {
        final List<K> keyList = new ArrayList<>(subMap.size());
        final List<U> updateValueList = new ArrayList<>(subMap.size());
        subMap.forEach((key, value) -> {
          keyList.add(key);
          updateValueList.add(value);
        });

        // send operation to remote or local queue
        remoteAccessOpSender.sendMultiKeyOpToRemote(OpType.UPDATE, tableId, blockId, keyList, Collections.emptyList(),
            updateValueList, targetExecutorId, replyRequired, tableComponents, aggregateDataOpResult);
      } finally {
        final Lock ownershipLock = remoteIdWithLock.getValue();
        ownershipLock.unlock();
      }
    });

    return aggregateDataOpResult;
  }

  @Override
  public DataOpResult<V> remove(final K key) {
    return removeInternal(key, true);
  }

  @Override
  public void removeNoReply(final K key) {
    removeInternal(key, false);
  }

  private DataOpResult<V> removeInternal(final K key, final boolean replyRequired) {
    final EncodedKey<K> encodedKey = new EncodedKey<>(key, keyCodec);

    final int blockId = blockPartitioner.getBlockId(encodedKey);
    final Optional<String> remoteIdOptional;

    final Pair<Optional<String>, Lock> remoteIdWithLock =
        tableComponents.getOwnershipCache().resolveExecutorWithLock(blockId);
    try {
      remoteIdOptional = remoteIdWithLock.getKey();

      // execute operation in local, holding ownershipLock
      if (!remoteIdOptional.isPresent()) {
        final V result = tablet.remove(blockId, key);
        return new SingleKeyDataOpResult<>(result, true);
      } else {
        final DataOpResult<V> dataOpResult = new SingleKeyDataOpResult<>();
        // send operation to remote
        remoteAccessOpSender.sendSingleKeyOpToRemote(
            OpType.REMOVE, tableId, blockId, key, null,
            null, remoteIdOptional.get(), replyRequired, tableComponents, dataOpResult);

        return dataOpResult;
      }
    } catch (final BlockNotExistsException e) {
      throw new RuntimeException(e);
    } finally {
      final Lock ownershipLock = remoteIdWithLock.getValue();
      ownershipLock.unlock();
    }
  }

  @Override
  public Tablet<K, V, U> getLocalTablet() {
    return tablet;
  }
}
