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
package edu.snu.cay.services.et.examples.simple;

import edu.snu.cay.services.et.common.util.concurrent.ListenableFuture;
import edu.snu.cay.services.et.configuration.ExecutorConfiguration;
import edu.snu.cay.services.et.configuration.ResourceConfiguration;
import edu.snu.cay.services.et.configuration.TableConfiguration;
import edu.snu.cay.services.et.configuration.TaskletConfiguration;
import edu.snu.cay.services.et.configuration.parameters.NumTotalBlocks;
import edu.snu.cay.services.et.driver.api.AllocatedExecutor;
import edu.snu.cay.services.et.driver.api.ETMaster;
import edu.snu.cay.services.et.driver.api.AllocatedTable;
import edu.snu.cay.services.et.driver.impl.RunningTasklet;
import edu.snu.cay.services.et.driver.impl.MigrationResult;
import edu.snu.cay.services.et.evaluator.impl.DefaultDataParser;
import edu.snu.cay.services.et.exceptions.NotAssociatedException;
import edu.snu.cay.utils.CatchableExecutors;
import edu.snu.cay.utils.StreamingSerializableCodec;
import org.apache.reef.tang.annotations.Parameter;
import org.apache.reef.tang.annotations.Unit;
import org.apache.reef.wake.EventHandler;
import org.apache.reef.wake.time.event.StartTime;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import static edu.snu.cay.services.et.common.util.TaskletUtils.waitAndCheckTaskletResult;

/**
 * Driver code for simple example.
 */
@Unit
final class SimpleETDriver {
  private static final Logger LOG = Logger.getLogger(SimpleETDriver.class.getName());
  private static final String GET_TASKLET_ID_PREFIX = "Simple-get-task-";
  private static final String PUT_TASKLET_ID_PREFIX = "Simple-put-task-";
  private static final String SCAN_TASKLET_ID_PREFIX = "Simple-scan-task-";
  static final int NUM_ASSOCIATORS = 2; // should be at least 2
  static final int NUM_SUBSCRIBERS = 1; // should be at least 1
  static final String HASHED_TABLE_ID = "Hashed_Table";
  static final String ORDERED_TABLE_ID = "Ordered_Table";
  static final String ORDERED_TABLE_WITH_FILE_ID = "Ordered_Table_With_File";
  static final String DROP_TEST_TABLE_ID = "Drop_Test_Table";

  private static final ExecutorConfiguration EXECUTOR_CONF = ExecutorConfiguration.newBuilder()
      .setResourceConf(
          ResourceConfiguration.newBuilder()
              .setNumCores(1)
              .setMemSizeInMB(128)
              .build())
      .build();

  private final ETMaster etMaster;

  private final String tableInputPath;

  @Inject
  private SimpleETDriver(final ETMaster etMaster,
                         @Parameter(SimpleET.TableInputPath.class) final String tableInputPath) {
    this.etMaster = etMaster;
    this.tableInputPath = tableInputPath;
  }

  private TableConfiguration buildTableConf(final String tableId, final String inputPath,
                                            final boolean isOrderedTable) {
    final TableConfiguration.Builder tableConfBuilder = TableConfiguration.newBuilder()
        .setId(tableId)
        .setKeyCodecClass(StreamingSerializableCodec.class)
        .setValueCodecClass(StreamingSerializableCodec.class)
        .setUpdateValueCodecClass(StreamingSerializableCodec.class)
        .setUpdateFunctionClass(SimpleUpdateFunction.class)
        .setIsMutableTable(true)
        .setIsOrderedTable(isOrderedTable);

    if (!inputPath.equals(SimpleET.TableInputPath.EMPTY)) {
      tableConfBuilder.setDataParserClass(DefaultDataParser.class);
    }

    return tableConfBuilder.build();
  }

  /**
   * A driver start handler for requesting executors.
   */
  final class StartHandler implements EventHandler<StartTime> {
    @Override
    public void onNext(final StartTime startTime) {
      final List<AllocatedExecutor> associators;
      final List<AllocatedExecutor> subscribers;
      try {
        associators = etMaster.addExecutors(NUM_ASSOCIATORS, EXECUTOR_CONF).get();
        subscribers = etMaster.addExecutors(NUM_SUBSCRIBERS, EXECUTOR_CONF).get();
      } catch (InterruptedException | ExecutionException e) {
        throw new RuntimeException(e);
      }

      CatchableExecutors.newSingleThreadExecutor().submit(() -> {
        try {
          final AllocatedTable hashedTable = etMaster.createTable(buildTableConf(HASHED_TABLE_ID,
              SimpleET.TableInputPath.EMPTY, false), associators).get();
          final AllocatedTable orderedTable = etMaster.createTable(buildTableConf(ORDERED_TABLE_ID,
              SimpleET.TableInputPath.EMPTY, true), associators).get();


          hashedTable.subscribe(subscribers).get();
          orderedTable.subscribe(subscribers).get();

          final AtomicInteger taskletIdCount = new AtomicInteger(0);
          final List<Future<RunningTasklet>> taskletFutureList =
              new ArrayList<>(associators.size() + subscribers.size());

          // 1. First run a put task in a subscriber
          taskletFutureList.add(subscribers.get(0).submitTasklet(TaskletConfiguration.newBuilder()
              .setId(PUT_TASKLET_ID_PREFIX + taskletIdCount.getAndIncrement())
              .setTaskletClass(PutTasklet.class)
              .build()));

          waitAndCheckTaskletResult(taskletFutureList, true);

          // 2. Then run get tasks in all executors
          taskletFutureList.clear();

          associators.forEach(executor -> taskletFutureList.add(executor.submitTasklet(TaskletConfiguration.newBuilder()
              .setId(GET_TASKLET_ID_PREFIX + taskletIdCount.getAndIncrement())
              .setTaskletClass(GetTasklet.class)
              .build())));

          subscribers.forEach(executor -> taskletFutureList.add(executor.submitTasklet(TaskletConfiguration.newBuilder()
              .setId(GET_TASKLET_ID_PREFIX + taskletIdCount.getAndIncrement())
              .setTaskletClass(GetTasklet.class)
              .build())));

          waitAndCheckTaskletResult(taskletFutureList, true);

          // 3. migrate blocks between associators
          // move all blocks of hashedTable in the first associator to the second associator
          final ListenableFuture<MigrationResult> resultFuture0 = hashedTable.moveBlocks(
              associators.get(0).getId(), associators.get(1).getId(),
              Integer.parseInt(NumTotalBlocks.DEFAULT_VALUE_STR));

          // move all blocks of orderedTable in the second associator to the first associator
          final ListenableFuture<MigrationResult> resultFuture1 = orderedTable.moveBlocks(
              associators.get(1).getId(), associators.get(0).getId(),
              Integer.parseInt(NumTotalBlocks.DEFAULT_VALUE_STR));

          waitAndCheckMigrationResult(resultFuture0);
          waitAndCheckMigrationResult(resultFuture1);

          // 4. run get tasks in all executors again after migration
          taskletFutureList.clear();

          associators.forEach(executor -> taskletFutureList.add(executor.submitTasklet(TaskletConfiguration.newBuilder()
              .setId(GET_TASKLET_ID_PREFIX + taskletIdCount.getAndIncrement())
              .setTaskletClass(GetTasklet.class)
              .build())));

          subscribers.forEach(executor -> taskletFutureList.add(executor.submitTasklet(TaskletConfiguration.newBuilder()
              .setId(GET_TASKLET_ID_PREFIX + taskletIdCount.getAndIncrement())
              .setTaskletClass(GetTasklet.class)
              .build())));

          waitAndCheckTaskletResult(taskletFutureList, true);

          // 5. drop tables and run get tasks again to confirm that tasks fail
          hashedTable.drop().get();
          orderedTable.drop().get();

          taskletFutureList.clear();

          associators.forEach(executor -> taskletFutureList.add(executor.submitTasklet(TaskletConfiguration.newBuilder()
              .setId(GET_TASKLET_ID_PREFIX + taskletIdCount.getAndIncrement())
              .setTaskletClass(GetTasklet.class)
              .build())));

          subscribers.forEach(executor -> taskletFutureList.add(executor.submitTasklet(TaskletConfiguration.newBuilder()
              .setId(GET_TASKLET_ID_PREFIX + taskletIdCount.getAndIncrement())
              .setTaskletClass(GetTasklet.class)
              .build())));

          waitAndCheckTaskletResult(taskletFutureList, false);

          // 6. create a table with input file
          final AllocatedTable orderedTableWithFile = etMaster.createTable(buildTableConf(ORDERED_TABLE_WITH_FILE_ID,
              tableInputPath, true), associators).get();

          // 7. start scan tasks in associator executors
          taskletFutureList.clear();

          associators.forEach(executor -> taskletFutureList.add(executor.submitTasklet(TaskletConfiguration.newBuilder()
              .setId(SCAN_TASKLET_ID_PREFIX + taskletIdCount.getAndIncrement())
              .setTaskletClass(ScanTasklet.class)
              .build())));

          waitAndCheckTaskletResult(taskletFutureList, true);

          // 8. migrate blocks between associators
          // move all blocks of orderedTableWithFile in the second associator to the first associator
          final ListenableFuture<MigrationResult> resultFuture = orderedTableWithFile.moveBlocks(
              associators.get(1).getId(), associators.get(0).getId(),
              Integer.parseInt(NumTotalBlocks.DEFAULT_VALUE_STR));

          waitAndCheckMigrationResult(resultFuture);

          // 9. start scan tasks again after migration
          taskletFutureList.clear();

          associators.forEach(executor -> taskletFutureList.add(executor.submitTasklet(TaskletConfiguration.newBuilder()
                  .setId(SCAN_TASKLET_ID_PREFIX + taskletIdCount.getAndIncrement())
                  .setTaskletClass(ScanTasklet.class)
                  .build())));

          waitAndCheckTaskletResult(taskletFutureList, true);

          orderedTableWithFile.drop().get(); // not required step

          // 10. drop a table and create another table with the same identifier
          AllocatedTable dropTestTable = etMaster.createTable(buildTableConf(DROP_TEST_TABLE_ID,
              SimpleET.TableInputPath.EMPTY, true), associators).get();
          dropTestTable.drop().get();
          dropTestTable = etMaster.createTable(buildTableConf(DROP_TEST_TABLE_ID,
              SimpleET.TableInputPath.EMPTY, false), associators).get();
          dropTestTable.drop().get();

          // 11. close executors
          subscribers.forEach(AllocatedExecutor::close);
          associators.forEach(AllocatedExecutor::close);

        } catch (InterruptedException | ExecutionException | NotAssociatedException e) {
          throw new RuntimeException(e);
        }
      });
    }
  }

  private void waitAndCheckMigrationResult(final ListenableFuture<MigrationResult> migrationResultFuture) {
    try {
      final MigrationResult migrationResult = migrationResultFuture.get();

      LOG.log(Level.INFO, "Migration has been finished: {0}, {1}, {2}",
          new Object[]{migrationResult.isCompleted(), migrationResult.getMsg(), migrationResult.getMigratedBlocks()});
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    }
  }
}
