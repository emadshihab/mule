/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.runtime.streaming;

import static org.mule.runtime.core.api.util.ExceptionUtils.extractConnectionException;

import org.mule.runtime.api.connection.ConnectionException;
import org.mule.runtime.api.meta.model.operation.OperationModel;
import org.mule.runtime.api.streaming.Cursor;
import org.mule.runtime.api.util.Pair;
import org.mule.runtime.extension.api.runtime.operation.ExecutionContext;
import org.mule.runtime.extension.api.runtime.operation.Interceptor;
import org.mule.runtime.module.extension.api.runtime.privileged.ExecutionContextAdapter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This interceptor tracks all the {@link Cursor cursors} that were resolved as parameters of a given operation
 * execution and the position they were in <b>before</b> the execution.
 *
 * If the operation fails with a {@link ConnectionException}, then the SDK will attempt to reconnect. This interceptor
 * makes sure that all those cursors are set back on their original positions before the retry is attempted, since otherwise,
 * the stream would be corrupted and the repeatable promise broken.
 *
 * @since 4.1
 */
public class CursorResetInterceptor implements Interceptor<OperationModel> {

  private static final Logger LOGGER = LoggerFactory.getLogger(CursorResetInterceptor.class);
  private static final String CURSOR_POSITIONS = "CURSOR_POSITIONS";

  private final List<String> cursorParamNames;

  public CursorResetInterceptor(List<String> cursorParamNames) {
    this.cursorParamNames = cursorParamNames;
  }

  @Override
  public void before(ExecutionContext<OperationModel> ctx) throws Exception {
    List<Pair<Cursor, Long>> cursorPositions = new ArrayList<>(cursorParamNames.size());
    for (String cursorParamName : cursorParamNames) {
      Object value = ctx.getParameterOrDefault(cursorParamName, null);
      if (value instanceof Cursor) {
        final Cursor cursor = (Cursor) value;
        cursorPositions.add(new Pair<>(cursor, cursor.getPosition()));
      }
    }

    if (!cursorPositions.isEmpty()) {
      ((ExecutionContextAdapter<OperationModel>) ctx).setVariable(CURSOR_POSITIONS, cursorPositions);
    }
  }

  @Override
  public Throwable onError(ExecutionContext<OperationModel> executionContext, Throwable exception) {
    extractConnectionException(exception).ifPresent(cne -> {
      List<Pair<Cursor, Long>> cursorPositions =
          ((ExecutionContextAdapter<OperationModel>) executionContext).removeVariable(CURSOR_POSITIONS);
      if (cursorPositions != null) {
        cursorPositions.forEach(pair -> {
          try {
            pair.getFirst().seek(pair.getSecond());
          } catch (IOException e) {
            if (LOGGER.isWarnEnabled()) {
              LOGGER.warn("Could not reset cursor back to position " + pair.getSecond() + ". Inconsistencies might occur if "
                  + "reconnection attempted", e);
            }
          }
        });
      }
    });

    return exception;
  }
}
