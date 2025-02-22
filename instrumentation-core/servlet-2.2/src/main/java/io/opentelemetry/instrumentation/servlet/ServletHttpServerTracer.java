/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.servlet;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.attributes.SemanticAttributes;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator.Getter;
import io.opentelemetry.instrumentation.api.servlet.AppServerBridge;
import io.opentelemetry.instrumentation.api.servlet.ServletContextPath;
import io.opentelemetry.instrumentation.api.tracer.HttpServerTracer;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.Principal;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ServletHttpServerTracer<RESPONSE>
    extends HttpServerTracer<HttpServletRequest, RESPONSE, HttpServletRequest, HttpServletRequest> {

  private static final Logger log = LoggerFactory.getLogger(ServletHttpServerTracer.class);

  public Context startSpan(HttpServletRequest request) {
    Context context = startSpan(request, request, request, getSpanName(request));
    String contextPath = request.getContextPath();
    if (contextPath != null && !contextPath.isEmpty() && !contextPath.equals("/")) {
      context = context.with(ServletContextPath.CONTEXT_KEY, contextPath);
    }
    return context;
  }

  @Override
  public void endExceptionally(
      Context context, Throwable throwable, RESPONSE response, long timestamp) {
    if (isResponseCommitted(response)) {
      super.endExceptionally(context, throwable, response, timestamp);
    } else {
      // passing null response to super, in order to capture as 500 / INTERNAL, due to servlet spec
      // https://javaee.github.io/servlet-spec/downloads/servlet-4.0/servlet-4_0_FINAL.pdf:
      // "If a servlet generates an error that is not handled by the error page mechanism as
      // described above, the container must ensure to send a response with status 500."
      super.endExceptionally(context, throwable, null, timestamp);
    }
  }

  protected abstract boolean isResponseCommitted(RESPONSE response);

  @Override
  protected String url(HttpServletRequest httpServletRequest) {
    try {
      return new URI(
              httpServletRequest.getScheme(),
              null,
              httpServletRequest.getServerName(),
              httpServletRequest.getServerPort(),
              httpServletRequest.getRequestURI(),
              httpServletRequest.getQueryString(),
              null)
          .toString();
    } catch (URISyntaxException e) {
      log.debug("Failed to construct request URI", e);
      return null;
    }
  }

  @Override
  public Context getServerContext(HttpServletRequest request) {
    Object context = request.getAttribute(CONTEXT_ATTRIBUTE);
    return context instanceof Context ? (Context) context : null;
  }

  @Override
  protected void attachServerContext(Context context, HttpServletRequest request) {
    request.setAttribute(CONTEXT_ATTRIBUTE, context);
  }

  @Override
  protected Integer peerPort(HttpServletRequest connection) {
    // HttpServletResponse doesn't have accessor for remote port prior to Servlet spec 3.0
    return null;
  }

  @Override
  protected String peerHostIP(HttpServletRequest connection) {
    return connection.getRemoteAddr();
  }

  @Override
  protected String method(HttpServletRequest request) {
    return request.getMethod();
  }

  @Override
  public void onRequest(Span span, HttpServletRequest request) {
    // we do this e.g. so that servlet containers can use these values in their access logs
    request.setAttribute("traceId", span.getSpanContext().getTraceIdAsHexString());
    request.setAttribute("spanId", span.getSpanContext().getSpanIdAsHexString());

    super.onRequest(span, request);
  }

  @Override
  protected Getter<HttpServletRequest> getGetter() {
    return HttpServletRequestGetter.GETTER;
  }

  public void addUnwrappedThrowable(Span span, Throwable throwable) {
    addThrowable(span, unwrapThrowable(throwable));
  }

  @Override
  protected Throwable unwrapThrowable(Throwable throwable) {
    Throwable result = throwable;
    if (throwable instanceof ServletException && throwable.getCause() != null) {
      result = throwable.getCause();
    }
    return super.unwrapThrowable(result);
  }

  public void setPrincipal(Context context, HttpServletRequest request) {
    Principal principal = request.getUserPrincipal();
    if (principal != null) {
      Span.fromContext(context).setAttribute(SemanticAttributes.ENDUSER_ID, principal.getName());
    }
  }

  @Override
  protected String flavor(HttpServletRequest connection, HttpServletRequest request) {
    return connection.getProtocol();
  }

  @Override
  protected String requestHeader(HttpServletRequest httpServletRequest, String name) {
    return httpServletRequest.getHeader(name);
  }

  public static String getSpanName(HttpServletRequest request) {
    String servletPath = request.getServletPath();
    if (servletPath.isEmpty()) {
      return "HTTP " + request.getMethod();
    }
    String contextPath = request.getContextPath();
    if (contextPath == null || contextPath.isEmpty() || contextPath.equals("/")) {
      return servletPath;
    }
    return contextPath + servletPath;
  }

  /**
   * When server spans are managed by app server instrumentation, servlet must update server span
   * name only once and only during the first pass through the servlet stack. There are potential
   * forward and other scenarios, where servlet path may change, but we don't want this to be
   * reflected in the span name.
   */
  public void updateServerSpanNameOnce(Context attachedContext, HttpServletRequest request) {
    if (AppServerBridge.isPresent(attachedContext)
        && !AppServerBridge.isServerSpanNameUpdatedFromServlet(attachedContext)) {
      updateSpanName(Span.fromContext(attachedContext), request);
      AppServerBridge.setServletUpdatedServerSpanName(attachedContext, true);
    }
  }

  public void updateSpanName(HttpServletRequest request) {
    updateSpanName(getServerSpan(request), request);
  }

  private static void updateSpanName(Span span, HttpServletRequest request) {
    span.updateName(getSpanName(request));
  }
}
