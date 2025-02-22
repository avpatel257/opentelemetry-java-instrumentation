/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.mongo;

import static java.util.Arrays.asList;

import com.mongodb.ServerAddress;
import com.mongodb.connection.ConnectionDescription;
import com.mongodb.event.CommandStartedEvent;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.attributes.SemanticAttributes;
import io.opentelemetry.api.trace.attributes.SemanticAttributes.DbSystemValues;
import io.opentelemetry.instrumentation.api.tracer.DatabaseClientTracer;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.json.JsonWriter;
import org.bson.json.JsonWriterSettings;

public class MongoClientTracer extends DatabaseClientTracer<CommandStartedEvent, BsonDocument> {
  private static final MongoClientTracer TRACER = new MongoClientTracer();

  private final int maxNormalizedQueryLength;
  private final JsonWriterSettings jsonWriterSettings;

  public MongoClientTracer() {
    this(32 * 1024);
  }

  public MongoClientTracer(int maxNormalizedQueryLength) {
    this.maxNormalizedQueryLength = maxNormalizedQueryLength;
    this.jsonWriterSettings = createJsonWriterSettings(maxNormalizedQueryLength);
  }

  public static MongoClientTracer tracer() {
    return TRACER;
  }

  @Override
  protected String getInstrumentationName() {
    return "io.opentelemetry.javaagent.mongo";
  }

  @Override
  protected String dbSystem(CommandStartedEvent event) {
    return DbSystemValues.MONGODB;
  }

  @Override
  protected Span onConnection(Span span, CommandStartedEvent event) {
    span.setAttribute(SemanticAttributes.DB_OPERATION, event.getCommandName());
    String collection = collectionName(event);
    if (collection != null) {
      span.setAttribute(SemanticAttributes.DB_MONGODB_COLLECTION, collection);
    }
    return super.onConnection(span, event);
  }

  @Override
  protected String dbName(CommandStartedEvent event) {
    return event.getDatabaseName();
  }

  @Override
  protected InetSocketAddress peerAddress(CommandStartedEvent event) {
    if (event.getConnectionDescription() != null
        && event.getConnectionDescription().getServerAddress() != null) {
      return event.getConnectionDescription().getServerAddress().getSocketAddress();
    } else {
      return null;
    }
  }

  @Override
  protected String dbConnectionString(CommandStartedEvent event) {
    ConnectionDescription connectionDescription = event.getConnectionDescription();
    if (connectionDescription != null) {
      ServerAddress sa = connectionDescription.getServerAddress();
      if (sa != null) {
        // https://docs.mongodb.com/manual/reference/connection-string/
        String host = sa.getHost();
        int port = sa.getPort();
        if (host != null && port != 0) {
          return "mongodb://" + host + ":" + port;
        }
      }
    }
    return null;
  }

  private static final Method IS_TRUNCATED_METHOD;

  static {
    IS_TRUNCATED_METHOD =
        Arrays.stream(JsonWriter.class.getMethods())
            .filter(method -> method.getName().equals("isTruncated"))
            .findFirst()
            .orElse(null);
  }

  /**
   * The values of these mongo fields will not be scrubbed out. This allows the non-sensitive
   * collection names to be captured.
   */
  private static final List<String> UNSCRUBBED_FIELDS =
      asList("ordered", "insert", "count", "find", "create");

  private JsonWriterSettings createJsonWriterSettings(int maxNormalizedQueryLength) {
    JsonWriterSettings settings = new JsonWriterSettings(false);
    try {
      // The static JsonWriterSettings.builder() method was introduced in the 3.5 release
      Optional<Method> buildMethod =
          Arrays.stream(JsonWriterSettings.class.getMethods())
              .filter(method -> method.getName().equals("builder"))
              .findFirst();
      if (buildMethod.isPresent()) {
        Class<?> builderClass = buildMethod.get().getReturnType();
        Object builder = buildMethod.get().invoke(null, (Object[]) null);

        // The JsonWriterSettings.Builder.indent method was introduced in the 3.5 release,
        // but checking anyway
        Optional<Method> indentMethod =
            Arrays.stream(builderClass.getMethods())
                .filter(method -> method.getName().equals("indent"))
                .findFirst();
        if (indentMethod.isPresent()) {
          indentMethod.get().invoke(builder, false);
        }

        // The JsonWriterSettings.Builder.maxLength method was introduced in the 3.7 release
        Optional<Method> maxLengthMethod =
            Arrays.stream(builderClass.getMethods())
                .filter(method -> method.getName().equals("maxLength"))
                .findFirst();
        if (maxLengthMethod.isPresent()) {
          maxLengthMethod.get().invoke(builder, maxNormalizedQueryLength);
        }
        settings =
            (JsonWriterSettings)
                builderClass.getMethod("build", (Class<?>[]) null).invoke(builder, (Object[]) null);
      }
    } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException ignored) {
    }
    return settings;
  }

  @Override
  public String normalizeQuery(BsonDocument command) {
    StringWriter stringWriter = new StringWriter(128);
    writeScrubbed(command, new JsonWriter(stringWriter, jsonWriterSettings), true);
    // If using MongoDB driver >= 3.7, the substring invocation will be a no-op due to use of
    // JsonWriterSettings.Builder.maxLength in the static initializer for JSON_WRITER_SETTINGS
    return stringWriter
        .getBuffer()
        .substring(0, Math.min(maxNormalizedQueryLength, stringWriter.getBuffer().length()));
  }

  private static final String HIDDEN_CHAR = "?";

  private static boolean writeScrubbed(BsonDocument origin, JsonWriter writer, boolean isRoot) {
    writer.writeStartDocument();
    boolean firstField = true;
    for (Map.Entry<String, BsonValue> entry : origin.entrySet()) {
      writer.writeName(entry.getKey());
      // the first field of the root document is the command name, so we preserve its value
      // (which for most CRUD commands is the collection name)
      if (isRoot && firstField && entry.getValue().isString()) {
        writer.writeString(entry.getValue().asString().getValue());
      } else {
        if (writeScrubbed(entry.getValue(), writer)) {
          return true;
        }
      }
      firstField = false;
    }
    writer.writeEndDocument();
    return false;
  }

  private static boolean writeScrubbed(BsonArray origin, JsonWriter writer) {
    writer.writeStartArray();
    for (BsonValue value : origin) {
      if (writeScrubbed(value, writer)) {
        return true;
      }
    }
    writer.writeEndArray();
    return false;
  }

  private static boolean writeScrubbed(BsonValue origin, JsonWriter writer) {
    if (origin.isDocument()) {
      return writeScrubbed(origin.asDocument(), writer, false);
    } else if (origin.isArray()) {
      return writeScrubbed(origin.asArray(), writer);
    } else {
      writer.writeString(HIDDEN_CHAR);
      return isTruncated(writer);
    }
  }

  private static boolean isTruncated(JsonWriter writer) {
    if (IS_TRUNCATED_METHOD == null) {
      return false;
    } else {
      try {
        return (boolean) IS_TRUNCATED_METHOD.invoke(writer, (Object[]) null);
      } catch (IllegalAccessException | InvocationTargetException ignored) {
        return false;
      }
    }
  }

  private static final Set<String> COMMANDS_WITH_COLLECTION_NAME_AS_VALUE =
      new HashSet<>(
          asList(
              "aggregate",
              "count",
              "distinct",
              "mapReduce",
              "geoSearch",
              "delete",
              "find",
              "killCursors",
              "findAndModify",
              "insert",
              "update",
              "create",
              "drop",
              "createIndexes",
              "listIndexes"));

  private static String collectionName(CommandStartedEvent event) {
    if (event.getCommandName().equals("getMore")) {
      if (event.getCommand().containsKey("collection")) {
        BsonValue collectionValue = event.getCommand().get("collection");
        if (collectionValue.isString()) {
          return event.getCommand().getString("collection").getValue();
        }
      }
    } else if (COMMANDS_WITH_COLLECTION_NAME_AS_VALUE.contains(event.getCommandName())) {
      BsonValue commandValue = event.getCommand().get(event.getCommandName());
      if (commandValue != null && commandValue.isString()) {
        return commandValue.asString().getValue();
      }
    }
    return null;
  }
}
