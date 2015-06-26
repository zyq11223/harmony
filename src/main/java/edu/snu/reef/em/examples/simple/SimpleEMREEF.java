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

package edu.snu.reef.em.examples.simple;

import edu.snu.reef.em.driver.ElasticMemoryConfiguration;
import org.apache.reef.client.DriverConfiguration;
import org.apache.reef.client.DriverLauncher;
import org.apache.reef.client.LauncherStatus;
import org.apache.reef.io.network.naming.NameServerConfiguration;
import org.apache.reef.runtime.local.client.LocalRuntimeConfiguration;
import org.apache.reef.runtime.yarn.client.YarnClientConfiguration;
import org.apache.reef.tang.*;
import org.apache.reef.tang.annotations.Name;
import org.apache.reef.tang.annotations.NamedParameter;
import org.apache.reef.tang.exceptions.InjectionException;
import org.apache.reef.tang.formats.CommandLine;
import org.apache.reef.util.EnvironmentUtils;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client code for SimpleEM
 */
final class SimpleEMREEF {
  private static final Logger LOG = Logger.getLogger(SimpleEMREEF.class.getName());
  private static final int TIMEOUT = 100000;
  private static final Tang TANG = Tang.Factory.getTang();

  @NamedParameter(doc = "Whether or not to run on the local runtime", short_name = "local", default_value = "true")
  public static final class OnLocal implements Name<Boolean> {
  }

  /**
   * Check onLocal option by parsing the command line
   */
  private static boolean parseOnLocalFromCommmandLine(final String[] args) throws InjectionException, IOException {
    final JavaConfigurationBuilder cb = TANG.newConfigurationBuilder();
    final CommandLine cl = new CommandLine(cb);
    cl.registerShortNameOfClass(OnLocal.class);
    cl.processCommandLine(args);

    final Injector injector = TANG.newInjector(cb.build());
    return injector.getNamedInstance(OnLocal.class);
  }

  public static Configuration getDriverConfiguration() {
    final Configuration driverConfiguration = DriverConfiguration.CONF
        .set(DriverConfiguration.GLOBAL_LIBRARIES, EnvironmentUtils.getClassLocation(SimpleEMDriver.class))
        .set(DriverConfiguration.DRIVER_IDENTIFIER, "SimpleEMDriver")
        .set(DriverConfiguration.ON_EVALUATOR_ALLOCATED, SimpleEMDriver.EvaluatorAllocatedHandler.class)
        .set(DriverConfiguration.ON_DRIVER_STARTED, SimpleEMDriver.DriverStartHandler.class)
        .set(DriverConfiguration.ON_CONTEXT_ACTIVE, SimpleEMDriver.ActiveContextHandler.class)
        .set(DriverConfiguration.ON_TASK_MESSAGE, SimpleEMDriver.TaskMessageHandler.class)
        .build();

    final Configuration emConfiguration = ElasticMemoryConfiguration.getDriverConfiguration();

    // spawn the name server at the driver
    return Configurations.merge(driverConfiguration, emConfiguration, NameServerConfiguration.CONF.build());
  }

  public static LauncherStatus runSimpleEM(final Configuration runtimeConf, final int timeOut, final boolean onLocal)
      throws InjectionException {
    final Configuration driverConf = getDriverConfiguration();

    return DriverLauncher.getLauncher(runtimeConf).run(driverConf, timeOut);
  }

  public static void main(final String[] args) throws InjectionException, IOException {
    final boolean onLocal = parseOnLocalFromCommmandLine(args);
    final Configuration runtimeConf = onLocal ?
      LocalRuntimeConfiguration.CONF.build():
      YarnClientConfiguration.CONF.build();

    final LauncherStatus status = runSimpleEM(runtimeConf, TIMEOUT, onLocal);
    LOG.log(Level.INFO, "REEF job completed: {0}", status);
  }
}
