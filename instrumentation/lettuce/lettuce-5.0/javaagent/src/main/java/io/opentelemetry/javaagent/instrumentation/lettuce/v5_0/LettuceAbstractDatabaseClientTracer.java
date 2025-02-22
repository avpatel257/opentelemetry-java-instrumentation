/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.lettuce.v5_0;

import io.lettuce.core.RedisURI;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.attributes.SemanticAttributes;
import io.opentelemetry.api.trace.attributes.SemanticAttributes.DbSystemValues;
import io.opentelemetry.instrumentation.api.tracer.DatabaseClientTracer;
import java.net.InetSocketAddress;

public abstract class LettuceAbstractDatabaseClientTracer<QUERY>
    extends DatabaseClientTracer<RedisURI, QUERY> {
  @Override
  protected String getInstrumentationName() {
    return "io.opentelemetry.javaagent.lettuce";
  }

  @Override
  protected String dbSystem(RedisURI connection) {
    return DbSystemValues.REDIS;
  }

  @Override
  protected InetSocketAddress peerAddress(RedisURI redisUri) {
    return new InetSocketAddress(redisUri.getHost(), redisUri.getPort());
  }

  @Override
  public Span onConnection(Span span, RedisURI connection) {
    if (connection != null && connection.getDatabase() != 0) {
      span.setAttribute(SemanticAttributes.DB_REDIS_DATABASE_INDEX, connection.getDatabase());
    }
    return super.onConnection(span, connection);
  }
}
