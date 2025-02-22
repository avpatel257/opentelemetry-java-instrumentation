/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.smoketest

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import okhttp3.Request

class SpringBootWithSamplingSmokeTest extends SmokeTest {

  static final double SAMPLER_PROBABILITY = 0.2
  static final int NUM_TRIES = 1000
  static final int ALLOWED_DEVIATION = 0.1 * NUM_TRIES

  protected String getTargetImage(String jdk, String serverVersion) {
    "ghcr.io/open-telemetry/java-test-containers:smoke-springboot-jdk$jdk-20201217.427450994"
  }

  @Override
  protected Map<String, String> getExtraEnv() {
    return ["OTEL_CONFIG_SAMPLER_PROBABILITY": String.valueOf(SAMPLER_PROBABILITY)]
  }

  def "spring boot with probability sampling enabled on JDK #jdk"(int jdk) {
    setup:
    startTarget(jdk)
    String url = "http://localhost:${target.getMappedPort(8080)}/greeting"
    def request = new Request.Builder().url(url).get().build()

    when:
    for (int i = 1; i <= NUM_TRIES; i++) {
      CLIENT.newCall(request).execute().close()
    }
    Collection<ExportTraceServiceRequest> traces = waitForTraces()

    then:
    // since sampling is enabled, not really expecting to receive NUM_TRIES spans
    Math.abs(countSpansByName(traces, 'WebController.greeting') - (SAMPLER_PROBABILITY * NUM_TRIES)) <= ALLOWED_DEVIATION
    Math.abs(countSpansByName(traces, '/greeting') - (SAMPLER_PROBABILITY * NUM_TRIES)) <= ALLOWED_DEVIATION

    cleanup:
    stopTarget()

    where:
    jdk << [8, 11, 15]
  }
}
