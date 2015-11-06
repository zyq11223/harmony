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
package edu.snu.cay.dolphin.examples.sleep;

import edu.snu.cay.dolphin.core.UserParameters;
import edu.snu.cay.dolphin.examples.ml.parameters.MaxIterations;
import edu.snu.cay.services.em.serialize.Serializer;
import org.apache.reef.driver.parameters.DriverLocalFiles;
import org.apache.reef.tang.Configuration;
import org.apache.reef.tang.ConfigurationBuilder;
import org.apache.reef.tang.Tang;
import org.apache.reef.tang.annotations.Name;
import org.apache.reef.tang.annotations.NamedParameter;
import org.apache.reef.tang.annotations.Parameter;
import org.apache.reef.tang.formats.CommandLine;

import javax.inject.Inject;
import java.io.*;

/**
 * {@link UserParameters} for the SleepREEF application.
 *
 * <p>
 *   The user must provide a configuration file that specifies the
 *   initial workloads and computation rates of evaluators. This file
 *   is read and parsed at the driver. The driver then binds these
 *   values to named parameters and submits them when creating tasks.
 *   The configuration file does not necessarily have to
 *   be on a distributed file system even when running on YARN or Mesos.
 *
 *   Other parameters such as the size of a single serialized object, and
 *   encode/decode rates are also passed to evaluators here.
 * </p>
 *
 * <p>
 *   Following is an example of a configuration file.
 *   The first column represents the initial number of data units (workload) given to an evaluator.
 *   The second column is the computation rate of an evaluator (ms per data unit).
 *   When the number of evaluators start to exceed the number of lines,
 *   the default values (0, 1.0) are assigned.
 * </p>
 *
 * <pre>{@code
 *   100 10
 *   200 5
 *   300 8
 *   0 10
 *   0 12
 * }</pre>
 */
public final class SleepParameters implements UserParameters {

  public static final String KEY = "KEY";

  private final File confFile;
  private final int maxIterations;
  private final int encodedObjectSize;
  private final long encodeRate;
  private final long decodeRate;
  private BufferedReader bufferedReader;

  @Inject
  private SleepParameters(@Parameter(ConfigurationFilePath.class) final String confFilePath,
                          @Parameter(MaxIterations.class) final int maxIterations,
                          @Parameter(SerializedObjectSize.class) final int encodedObjectSize,
                          @Parameter(EncodeRate.class) final long encodeRate,
                          @Parameter(DecodeRate.class) final long decodeRate) {
    this.confFile = new File(confFilePath);
    this.maxIterations = maxIterations;
    this.encodedObjectSize = encodedObjectSize;
    this.encodeRate = encodeRate;
    this.decodeRate = decodeRate;
  }

  /**
   * The driver-side configuration for SleepREEF.
   * The local configuration file is passed to the container for the driver.
   */
  @Override
  public Configuration getDriverConf() {
    return Tang.Factory.getTang().newConfigurationBuilder()
        .bindSetEntry(DriverLocalFiles.class, confFile.getAbsolutePath())
        .bindNamedParameter(ConfigurationFilePath.class, String.format("reef/local/%s", confFile.getName()))
        .bindNamedParameter(MaxIterations.class, Integer.toString(maxIterations))
        .bindNamedParameter(SerializedObjectSize.class, Integer.toString(encodedObjectSize))
        .bindNamedParameter(EncodeRate.class, Long.toString(encodeRate))
        .bindNamedParameter(DecodeRate.class, Long.toString(decodeRate))
        .build();
  }

  /**
   * The service configuration for SleepREEF compute tasks and the SleepREEF controller task.
   */
  @Override
  public Configuration getServiceConf() {
    return Tang.Factory.getTang().newConfigurationBuilder()
        .bindImplementation(Serializer.class, SleepSerializer.class)
        .bindNamedParameter(SerializedObjectSize.class, Integer.toString(encodedObjectSize))
        .bindNamedParameter(EncodeRate.class, Long.toString(encodeRate))
        .bindNamedParameter(DecodeRate.class, Long.toString(decodeRate))
        .build();
  }

  /**
   * Returns a single line from the given configuration file.
   *
   * The '#' character and other characters following it are regarded as
   * inline comments and are removed, as well as leading and trailing whitespaces.
   * In case the processed string is empty, this method skips that line and
   * repeats until it finds a non-empty result string.
   *
   * This method is declared {@code synchronized} to prevent concurrent reads;
   * each caller should take a different line.
   *
   * @return a processed line from the given conf file, or {@code null} if EOF
   */
  private synchronized String readLineFromFile() {
    try {
      if (bufferedReader == null) {
        bufferedReader = new BufferedReader(new FileReader(confFile));
      }

      String str;
      do {
        str = bufferedReader.readLine();
        if (str == null) {
          return null;
        }

        final int indexOfComments = str.indexOf('#');
        if (indexOfComments != -1) {
          str = str.substring(0, indexOfComments);
        }
        str = str.trim();

      } while (str.isEmpty());
      return str;

    } catch (final FileNotFoundException e) {
      throw new RuntimeException(String.format("No file named %s", confFile), e);
    } catch (final IOException e) {
      throw new RuntimeException(String.format("Failed to read line from %s", confFile), e);
    }
  }

  /**
   * The configuration for a SleepREEF compute task.
   * This is called at the driver, once for each compute task.
   * The returned configuration contains named parameter configurations for
   * {@link InitialWorkload} and {@link ComputationRate}.
   */
  @Override
  public Configuration getUserCmpTaskConf() {
    final String initialWorkloadStr;
    final String computationRateStr;

    final String str = readLineFromFile();
    if (str == null) {
      // in case of EOF, return empty configuration
      return Tang.Factory.getTang().newConfigurationBuilder().build();

    } else {
      final String[] args = str.split("\\s+");

      initialWorkloadStr = args[0];
      final int initialWorkload = Integer.parseInt(initialWorkloadStr);
      if (initialWorkload < 0) {
        throw new RuntimeException(String.format("Initial workload is negative %d", initialWorkload));
      }

      computationRateStr = args[1];
      final long computationRate = Long.parseLong(computationRateStr);
      if (computationRate < 0) {
        throw new RuntimeException(String.format("Computation rate is negative %d", computationRate));
      }
    }

    return Tang.Factory.getTang().newConfigurationBuilder()
        .bindNamedParameter(InitialWorkload.class, initialWorkloadStr)
        .bindNamedParameter(ComputationRate.class, computationRateStr)
        .build();
  }

  /**
   * The configuration for the SleepREEF controller task.
   */
  @Override
  public Configuration getUserCtrlTaskConf() {
    return Tang.Factory.getTang().newConfigurationBuilder()
        .bindNamedParameter(MaxIterations.class, Integer.toString(maxIterations))
        .build();
  }

  /**
   * Read the configuration file path and the number of iterations via command line.
   */
  public static CommandLine getCommandLine() {
    final ConfigurationBuilder cb = Tang.Factory.getTang().newConfigurationBuilder();
    final CommandLine cl = new CommandLine(cb);
    cl.registerShortNameOfClass(ConfigurationFilePath.class);
    cl.registerShortNameOfClass(MaxIterations.class);
    cl.registerShortNameOfClass(SerializedObjectSize.class);
    cl.registerShortNameOfClass(EncodeRate.class);
    cl.registerShortNameOfClass(DecodeRate.class);
    return cl;
  }

  @NamedParameter(doc = "input path of the configuration file", short_name = "conf")
  private final class ConfigurationFilePath implements Name<String> {
  }

  @NamedParameter(doc = "initial number of data units given to an evaluator", default_value = "0")
  final class InitialWorkload implements Name<Integer> {
  }

  @NamedParameter(doc = "the computation rate of an evaluator, milliseconds per data unit", default_value = "1")
  final class ComputationRate implements Name<Long> {
  }

  @NamedParameter(doc = "number of bytes of a single object in its serialized form",
                  short_name = "serializedObject",
                  default_value = "0")
  final class SerializedObjectSize implements Name<Integer> {
  }

  @NamedParameter(doc = "the encode rate of an evaluator, milliseconds per data unit",
                  short_name = "encodeRate",
                  default_value = "0")
  final class EncodeRate implements Name<Long> {
  }

  @NamedParameter(doc = "the decode rate of an evaluator, milliseconds per data unit",
                  short_name = "decodeRate",
                  default_value = "0")
  final class DecodeRate implements Name<Long> {
  }
}