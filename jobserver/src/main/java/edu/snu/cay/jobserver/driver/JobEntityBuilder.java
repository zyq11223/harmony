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
package edu.snu.cay.jobserver.driver;

import org.apache.reef.tang.exceptions.InjectionException;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A builder interface for {@link JobEntity}.
 * Users should provide a job-specific builder implementation.
 */
public interface JobEntityBuilder {
  AtomicInteger JOB_COUNTER = new AtomicInteger(0);

  /**
   * Builds a JobEntity.
   * @return a {@link JobEntity}
   * @throws InjectionException
   * @throws IOException
   */
  JobEntity build() throws InjectionException, IOException;
}
