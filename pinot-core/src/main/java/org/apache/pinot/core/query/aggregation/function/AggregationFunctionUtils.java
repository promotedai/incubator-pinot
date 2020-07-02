/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.core.query.aggregation.function;

import com.google.common.math.DoubleMath;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.pinot.common.function.AggregationFunctionType;
import org.apache.pinot.common.request.AggregationInfo;
import org.apache.pinot.core.common.BlockValSet;
import org.apache.pinot.core.operator.blocks.TransformBlock;
import org.apache.pinot.core.query.request.context.ExpressionContext;
import org.apache.pinot.core.query.request.context.FunctionContext;
import org.apache.pinot.core.query.request.context.OrderByExpressionContext;
import org.apache.pinot.core.query.request.context.QueryContext;
import org.apache.pinot.core.startree.v2.AggregationFunctionColumnPair;
import org.apache.pinot.parsers.CompilerConstants;


/**
 * The <code>AggregationFunctionUtils</code> class provides utility methods for aggregation function.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class AggregationFunctionUtils {
  private AggregationFunctionUtils() {
  }

  /**
   * Extracts the aggregation function arguments (could be column name, transform function or constant) from the
   * {@link AggregationInfo} as an array of Strings.
   * <p>NOTE: For backward-compatibility, uses the new Thrift field `expressions` if found, or falls back to the old
   * aggregationParams based approach.
   */
  public static List<String> getArguments(AggregationInfo aggregationInfo) {
    List<String> expressions = aggregationInfo.getExpressions();
    if (expressions != null) {
      return expressions;
    } else {
      // NOTE: When the server is upgraded before the broker, the expressions won't be set. Falls back to the old
      //       aggregationParams based approach.
      String column = aggregationInfo.getAggregationParams().get(CompilerConstants.COLUMN_KEY_IN_AGGREGATION_INFO);
      return Arrays.asList(column.split(CompilerConstants.AGGREGATION_FUNCTION_ARG_SEPARATOR));
    }
  }

  /**
   * Creates an array of {@link AggregationFunction}s based on the given {@link QueryContext}.
   */
  public static AggregationFunction[] getAggregationFunctions(QueryContext queryContext) {
    List<ExpressionContext> selectExpressions = queryContext.getSelectExpressions();
    Set<FunctionContext> functions = new HashSet<>();
    List<AggregationFunction> aggregationFunctions = new ArrayList<>();
    for (ExpressionContext selectExpression : selectExpressions) {
      FunctionContext function = selectExpression.getFunction();
      if (function != null && function.getType() == FunctionContext.Type.AGGREGATION) {
        // TODO: Deduplicate aggregation functions after deprecating the BrokerRequest. PQL relies on them to return the
        //       correct columns.
        functions.add(function);
        aggregationFunctions.add(AggregationFunctionFactory.getAggregationFunction(function, queryContext));
      }
    }
    // Add aggregation functions in the ORDER-BY clause but not in the SELECT clause
    List<OrderByExpressionContext> orderByExpressions = queryContext.getOrderByExpressions();
    if (orderByExpressions != null) {
      for (OrderByExpressionContext orderByExpression : orderByExpressions) {
        FunctionContext function = orderByExpression.getExpression().getFunction();
        if (function != null && function.getType() == FunctionContext.Type.AGGREGATION && functions.add(function)) {
          aggregationFunctions.add(AggregationFunctionFactory.getAggregationFunction(function, queryContext));
        }
      }
    }
    return aggregationFunctions.toArray(new AggregationFunction[0]);
  }

  /**
   * (For Star-Tree) Creates an {@link AggregationFunctionColumnPair} from the {@link AggregationFunction}. Returns
   * {@code null} if the {@link AggregationFunction} cannot be represented as an {@link AggregationFunctionColumnPair}
   * (e.g. has multiple arguments, argument is not column etc.).
   */
  @Nullable
  public static AggregationFunctionColumnPair getAggregationFunctionColumnPair(
      AggregationFunction aggregationFunction) {
    AggregationFunctionType aggregationFunctionType = aggregationFunction.getType();
    if (aggregationFunctionType == AggregationFunctionType.COUNT) {
      return AggregationFunctionColumnPair.COUNT_STAR;
    }
    List<ExpressionContext> inputExpressions = aggregationFunction.getInputExpressions();
    if (inputExpressions.size() == 1) {
      ExpressionContext inputExpression = inputExpressions.get(0);
      if (inputExpression.getType() == ExpressionContext.Type.IDENTIFIER) {
        return new AggregationFunctionColumnPair(aggregationFunctionType, inputExpression.getIdentifier());
      }
    }
    return null;
  }

  public static String formatValue(Object value) {
    if (value instanceof Double) {
      double doubleValue = (double) value;

      // NOTE: String.format() is very expensive, so avoid it for whole numbers that can fit in Long.
      //       We simply append ".00000" to long, in order to keep the existing behavior.
      if (doubleValue <= Long.MAX_VALUE && doubleValue >= Long.MIN_VALUE && DoubleMath
          .isMathematicalInteger(doubleValue)) {
        return (long) doubleValue + ".00000";
      } else {
        return String.format(Locale.US, "%1.5f", doubleValue);
      }
    } else {
      return value.toString();
    }
  }

  public static Serializable getSerializableValue(Object value) {
    if (value instanceof Number) {
      return (Number) value;
    } else {
      return value.toString();
    }
  }

  /**
   * Helper function to concatenate arguments using separator.
   *
   * @param arguments Arguments to concatenate
   * @return Concatenated String of arguments
   */
  public static String concatArgs(String[] arguments) {
    return arguments.length > 1 ? String.join(CompilerConstants.AGGREGATION_FUNCTION_ARG_SEPARATOR, arguments)
        : arguments[0];
  }

  /**
   * Collects all transform expressions required for aggregation/group-by queries.
   * <p>NOTE: We don't need to consider order-by columns here as the ordering is only allowed for aggregation functions
   *          or group-by expressions.
   */
  public static Set<ExpressionContext> collectExpressionsToTransform(AggregationFunction[] aggregationFunctions,
      @Nullable ExpressionContext[] groupByExpressions) {
    Set<ExpressionContext> expressions = new HashSet<>();
    for (AggregationFunction aggregationFunction : aggregationFunctions) {
      expressions.addAll(aggregationFunction.getInputExpressions());
    }
    if (groupByExpressions != null) {
      expressions.addAll(Arrays.asList(groupByExpressions));
    }
    return expressions;
  }

  /**
   * Creates a map from expression required by the {@link AggregationFunction} to {@link BlockValSet} fetched from the
   * {@link TransformBlock}.
   */
  public static Map<ExpressionContext, BlockValSet> getBlockValSetMap(AggregationFunction aggregationFunction,
      TransformBlock transformBlock) {
    //noinspection unchecked
    List<ExpressionContext> expressions = aggregationFunction.getInputExpressions();
    int numExpressions = expressions.size();
    if (numExpressions == 0) {
      return Collections.emptyMap();
    }
    if (numExpressions == 1) {
      ExpressionContext expression = expressions.get(0);
      return Collections.singletonMap(expression, transformBlock.getBlockValueSet(expression));
    }
    Map<ExpressionContext, BlockValSet> blockValSetMap = new HashMap<>();
    for (ExpressionContext expression : expressions) {
      blockValSetMap.put(expression, transformBlock.getBlockValueSet(expression));
    }
    return blockValSetMap;
  }

  /**
   * (For Star-Tree) Creates a map from expression required by the {@link AggregationFunctionColumnPair} to
   * {@link BlockValSet} fetched from the {@link TransformBlock}.
   * <p>NOTE: We construct the map with original column name as the key but fetch BlockValSet with the aggregation
   *          function pair so that the aggregation result column name is consistent with or without star-tree.
   */
  public static Map<ExpressionContext, BlockValSet> getBlockValSetMap(
      AggregationFunctionColumnPair aggregationFunctionColumnPair, TransformBlock transformBlock) {
    ExpressionContext expression = ExpressionContext.forIdentifier(aggregationFunctionColumnPair.getColumn());
    BlockValSet blockValSet = transformBlock.getBlockValueSet(aggregationFunctionColumnPair.toColumnName());
    return Collections.singletonMap(expression, blockValSet);
  }
}
