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
package edu.snu.cay.common.reef;

import org.apache.reef.runtime.common.driver.idle.DriverIdleManager;
import org.apache.reef.runtime.common.driver.idle.DriverIdlenessSource;
import org.apache.reef.runtime.common.driver.idle.IdleMessage;
import org.apache.reef.tang.InjectionFuture;

import javax.inject.Inject;

/**
 * It determines a termination of driver.
 */
public final class DriverStatusManager implements DriverIdlenessSource {
  private static final IdleMessage RUNNING_MSG =
      new IdleMessage(DriverStatusManager.class.getName(), "Driver is still running", false);
  private static final IdleMessage FINISH_MSG =
      new IdleMessage(DriverStatusManager.class.getName(), "Driver finished", true);

  private final InjectionFuture<DriverIdleManager> driverIdleManagerFuture;
  private volatile boolean isDriverRunning;

  @Inject
  private DriverStatusManager(final InjectionFuture<DriverIdleManager> driverIdleManagerFuture) {
    this.driverIdleManagerFuture = driverIdleManagerFuture;
    this.isDriverRunning = true;
  }

  @Override
  public IdleMessage getIdleStatus() {
    return isDriverRunning ? RUNNING_MSG : FINISH_MSG;
  }

  /**
   * Sends {@link IdleMessage} to {@link DriverIdleManager} to terminate the driver.
   */
  public void finishDriver() {
    isDriverRunning = false;
    driverIdleManagerFuture.get().onPotentiallyIdle(FINISH_MSG);
  }
}
