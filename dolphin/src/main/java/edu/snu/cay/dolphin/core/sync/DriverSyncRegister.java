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
package edu.snu.cay.dolphin.core.sync;

import org.apache.reef.driver.parameters.DriverIdentifier;
import org.apache.reef.tang.annotations.Parameter;
import org.apache.reef.tang.annotations.Unit;
import org.apache.reef.wake.EventHandler;
import org.apache.reef.wake.IdentifierFactory;
import org.apache.reef.wake.time.event.StartTime;

import javax.inject.Inject;

/**
 * Register a driver id to a NetworkConnectionService when driver starts.
 */
@Unit
public final class DriverSyncRegister {

  private final SyncNetworkSetup syncNetworkSetup;
  private final IdentifierFactory identifierFactory;
  private final String driverId;

  @Inject
  private DriverSyncRegister(final SyncNetworkSetup syncNetworkSetup,
                             final IdentifierFactory identifierFactory,
                             @Parameter(DriverIdentifier.class) final String driverId) {
    this.syncNetworkSetup = syncNetworkSetup;
    this.identifierFactory = identifierFactory;
    this.driverId = driverId;
  }

  public final class RegisterDriverHandler implements EventHandler<StartTime> {
    @Override
    public void onNext(final StartTime value) {
      syncNetworkSetup.registerConnectionFactory(identifierFactory.getNewInstance(driverId));
    }
  }
}
