/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.opensearch.rest.v3_0;

import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.javaagent.instrumentation.opensearch.rest.v3_0.OpenSearchRestSingletons.convertResponse;
import static io.opentelemetry.javaagent.instrumentation.opensearch.rest.v3_0.OpenSearchRestSingletons.instrumenter;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.javaagent.instrumentation.opensearch.rest.OpenSearchRestRequest;
import io.opentelemetry.javaagent.instrumentation.opensearch.rest.RestResponseListener;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.ResponseListener;

public class RestClientInstrumentation implements TypeInstrumentation {
  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("org.opensearch.client.RestClient");
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        isMethod()
            .and(named("performRequest"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("org.opensearch.client.Request"))),
        this.getClass().getName() + "$PerformRequestAdvice");
    transformer.applyAdviceToMethod(
        isMethod()
            .and(named("performRequestAsync"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("org.opensearch.client.Request")))
            .and(takesArgument(1, named("org.opensearch.client.ResponseListener"))),
        this.getClass().getName() + "$PerformRequestAsyncAdvice");
  }

  @SuppressWarnings("unused")
  public static class PerformRequestAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(0) Request request,
        @Advice.Local("otelRequest") OpenSearchRestRequest otelRequest,
        @Advice.Local("otelContext") Context context,
        @Advice.Local("otelScope") Scope scope) {

      Context parentContext = currentContext();
      otelRequest = OpenSearchRestRequest.create(request.getMethod(), request.getEndpoint());
      if (!instrumenter().shouldStart(parentContext, otelRequest)) {
        return;
      }

      context = instrumenter().start(parentContext, otelRequest);
      scope = context.makeCurrent();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Return Response response,
        @Advice.Thrown Throwable throwable,
        @Advice.Local("otelRequest") OpenSearchRestRequest otelRequest,
        @Advice.Local("otelContext") Context context,
        @Advice.Local("otelScope") Scope scope) {

      if (scope == null) {
        return;
      }
      scope.close();

      instrumenter().end(context, otelRequest, convertResponse(response), throwable);
    }
  }

  @SuppressWarnings("unused")
  public static class PerformRequestAsyncAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(0) Request request,
        @Advice.Argument(value = 1, readOnly = false) ResponseListener responseListener,
        @Advice.Local("otelRequest") OpenSearchRestRequest otelRequest,
        @Advice.Local("otelContext") Context context,
        @Advice.Local("otelScope") Scope scope) {

      Context parentContext = currentContext();
      otelRequest = OpenSearchRestRequest.create(request.getMethod(), request.getEndpoint());
      if (!instrumenter().shouldStart(parentContext, otelRequest)) {
        return;
      }

      context = instrumenter().start(parentContext, otelRequest);
      scope = context.makeCurrent();

      responseListener =
          new RestResponseListener(
              responseListener,
              parentContext,
              instrumenter(),
              context,
              otelRequest,
              OpenSearchRestSingletons::convertResponse);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Thrown Throwable throwable,
        @Advice.Local("otelRequest") OpenSearchRestRequest otelRequest,
        @Advice.Local("otelContext") Context context,
        @Advice.Local("otelScope") Scope scope) {

      if (scope == null) {
        return;
      }
      scope.close();

      if (throwable != null) {
        instrumenter().end(context, otelRequest, null, throwable);
      }
      // span ended in RestResponseListener
    }
  }
}
