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
package edu.snu.cay.dolphin.async.jobserver;

import org.apache.reef.tang.Configuration;
import org.apache.reef.tang.InjectionFuture;
import org.apache.reef.tang.exceptions.InjectionException;
import org.apache.reef.webserver.HttpHandler;
import org.apache.reef.webserver.ParsedHttpRequest;

import javax.inject.Inject;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Receive HttpRequest so that it can handle the command list.
 */
public final class JobServerHttpHandler implements HttpHandler {

  private String uriSpecification = "dolphin";
  private final InjectionFuture<JobServerDriver> jobServerDriverFuture;

  @Inject
  private JobServerHttpHandler(final InjectionFuture<JobServerDriver> jobServerDriverFuture) {
    this.jobServerDriverFuture = jobServerDriverFuture;
  }

  @Override
  public String getUriSpecification() {
    return uriSpecification;
  }

  @Override
  public void setUriSpecification(final String newSpecification) {
    uriSpecification = newSpecification;
  }

  /**
   * HttpRequest handler.
   * The request url is http://127.0.1.1:{port}/dolphin/v1/{command}
   *
   * APIs
   *    /submit?conf={"jobConf" : confString}     to submit a new job.
   *    /finish                                   to finish the job server.
   */
  @Override
  public void onHttpRequest(final ParsedHttpRequest request, final HttpServletResponse httpServletResponse)
      throws IOException, ServletException {

    final String target = request.getTargetEntity().toLowerCase();
    final HttpResponse result;
    switch (target) {
    case "submit":
      result = onSubmit();
      break;
    case "finish":
      result = onFinish();
      break;
    default:
      return;
    }

    final int status = result.getStatus();
    final String message = result.getMessage();

    if (result.isOK()) {
      httpServletResponse.getOutputStream().println(message);
    } else {
      httpServletResponse.sendError(status, message);
    }
  }

  private HttpResponse onSubmit() {
    final Configuration jobConf = jobServerDriverFuture.get().getTestingJobConf();
    try {
      final boolean isAccepted = jobServerDriverFuture.get().executeJob(jobConf);
      if (isAccepted) {
        return HttpResponse.ok("Job is successfully submitted");
      } else {
        return HttpResponse.ok("JobServer has been closed");
      }
    } catch (InjectionException | IOException e) {
      return HttpResponse.badRequest("Incomplete job configuration");
    }
  }

  private HttpResponse onFinish() {
    jobServerDriverFuture.get().shutdown();
    return HttpResponse.ok("Job server is successfully finished");
  }
}
