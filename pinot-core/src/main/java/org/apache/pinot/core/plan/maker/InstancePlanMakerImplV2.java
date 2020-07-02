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
package org.apache.pinot.core.plan.maker;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.apache.pinot.core.indexsegment.IndexSegment;
import org.apache.pinot.core.plan.AggregationGroupByOrderByPlanNode;
import org.apache.pinot.core.plan.AggregationGroupByPlanNode;
import org.apache.pinot.core.plan.AggregationPlanNode;
import org.apache.pinot.core.plan.CombinePlanNode;
import org.apache.pinot.core.plan.DictionaryBasedAggregationPlanNode;
import org.apache.pinot.core.plan.GlobalPlanImplV0;
import org.apache.pinot.core.plan.InstanceResponsePlanNode;
import org.apache.pinot.core.plan.MetadataBasedAggregationPlanNode;
import org.apache.pinot.core.plan.Plan;
import org.apache.pinot.core.plan.PlanNode;
import org.apache.pinot.core.plan.SelectionPlanNode;
import org.apache.pinot.core.query.config.QueryExecutorConfig;
import org.apache.pinot.core.query.request.context.ExpressionContext;
import org.apache.pinot.core.query.request.context.FunctionContext;
import org.apache.pinot.core.query.request.context.QueryContext;
import org.apache.pinot.core.query.request.context.utils.QueryContextUtils;
import org.apache.pinot.core.segment.index.readers.Dictionary;
import org.apache.pinot.core.util.QueryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The <code>InstancePlanMakerImplV2</code> class is the default implementation of {@link PlanMaker}.
 */
public class InstancePlanMakerImplV2 implements PlanMaker {
  private static final Logger LOGGER = LoggerFactory.getLogger(InstancePlanMakerImplV2.class);

  public static final String MAX_INITIAL_RESULT_HOLDER_CAPACITY_KEY = "max.init.group.holder.capacity";
  public static final int DEFAULT_MAX_INITIAL_RESULT_HOLDER_CAPACITY = 10_000;
  public static final String NUM_GROUPS_LIMIT = "num.groups.limit";
  public static final int DEFAULT_NUM_GROUPS_LIMIT = 100_000;

  private final int _maxInitialResultHolderCapacity;
  // Limit on number of groups stored for each segment, beyond which no new group will be created
  private final int _numGroupsLimit;

  @VisibleForTesting
  public InstancePlanMakerImplV2() {
    _maxInitialResultHolderCapacity = DEFAULT_MAX_INITIAL_RESULT_HOLDER_CAPACITY;
    _numGroupsLimit = DEFAULT_NUM_GROUPS_LIMIT;
  }

  @VisibleForTesting
  public InstancePlanMakerImplV2(int maxInitialResultHolderCapacity, int numGroupsLimit) {
    _maxInitialResultHolderCapacity = maxInitialResultHolderCapacity;
    _numGroupsLimit = numGroupsLimit;
  }

  /**
   * Constructor for usage when client requires to pass {@link QueryExecutorConfig} to this class.
   * <ul>
   *   <li>Set limit on the initial result holder capacity</li>
   *   <li>Set limit on number of groups returned from each segment and combined result</li>
   * </ul>
   *
   * @param queryExecutorConfig Query executor configuration
   */
  public InstancePlanMakerImplV2(QueryExecutorConfig queryExecutorConfig) {
    _maxInitialResultHolderCapacity = queryExecutorConfig.getConfig()
        .getInt(MAX_INITIAL_RESULT_HOLDER_CAPACITY_KEY, DEFAULT_MAX_INITIAL_RESULT_HOLDER_CAPACITY);
    _numGroupsLimit = queryExecutorConfig.getConfig().getInt(NUM_GROUPS_LIMIT, DEFAULT_NUM_GROUPS_LIMIT);
    Preconditions.checkState(_maxInitialResultHolderCapacity <= _numGroupsLimit,
        "Invalid configuration: maxInitialResultHolderCapacity: %d must be smaller or equal to numGroupsLimit: %d",
        _maxInitialResultHolderCapacity, _numGroupsLimit);
    LOGGER.info("Initializing plan maker with maxInitialResultHolderCapacity: {}, numGroupsLimit: {}",
        _maxInitialResultHolderCapacity, _numGroupsLimit);
  }

  @Override
  public Plan makeInstancePlan(List<IndexSegment> indexSegments, QueryContext queryContext,
      ExecutorService executorService, long timeOutMs) {
    List<PlanNode> planNodes = new ArrayList<>(indexSegments.size());
    for (IndexSegment indexSegment : indexSegments) {
      planNodes.add(makeSegmentPlanNode(indexSegment, queryContext));
    }
    CombinePlanNode combinePlanNode =
        new CombinePlanNode(planNodes, queryContext, executorService, timeOutMs, _numGroupsLimit);
    return new GlobalPlanImplV0(new InstanceResponsePlanNode(combinePlanNode));
  }

  @Override
  public PlanNode makeSegmentPlanNode(IndexSegment indexSegment, QueryContext queryContext) {
    if (QueryContextUtils.isAggregationQuery(queryContext)) {
      // Aggregation query
      List<ExpressionContext> groupByExpressions = queryContext.getGroupByExpressions();
      if (groupByExpressions != null) {
        // Aggregation group-by query
        QueryOptions queryOptions = new QueryOptions(queryContext.getQueryOptions());
        // new Combine operator only when GROUP_BY_MODE explicitly set to SQL
        if (queryOptions.isGroupByModeSQL()) {
          return new AggregationGroupByOrderByPlanNode(indexSegment, queryContext, _maxInitialResultHolderCapacity,
              _numGroupsLimit);
        }
        return new AggregationGroupByPlanNode(indexSegment, queryContext, _maxInitialResultHolderCapacity,
            _numGroupsLimit);
      } else {
        // Aggregation only query
        if (queryContext.getFilter() == null) {
          if (isFitForMetadataBasedPlan(queryContext)) {
            return new MetadataBasedAggregationPlanNode(indexSegment, queryContext);
          } else if (isFitForDictionaryBasedPlan(queryContext, indexSegment)) {
            return new DictionaryBasedAggregationPlanNode(indexSegment, queryContext);
          }
        }
        return new AggregationPlanNode(indexSegment, queryContext);
      }
    } else {
      // Selection query
      return new SelectionPlanNode(indexSegment, queryContext);
    }
  }

  /**
   * Returns {@code true} if the given aggregation-only without filter QueryContext can be solved with segment metadata,
   * {@code false} otherwise.
   * <p>Aggregations supported: COUNT
   */
  @VisibleForTesting
  static boolean isFitForMetadataBasedPlan(QueryContext queryContext) {
    List<ExpressionContext> selectExpressions = queryContext.getSelectExpressions();
    for (ExpressionContext expression : selectExpressions) {
      if (!expression.getFunction().getFunctionName().equals("count")) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns {@code true} if the given aggregation-only without filter QueryContext can be solved with dictionary,
   * {@code false} otherwise.
   * <p>Aggregations supported: MIN, MAX, MINMAXRANGE
   */
  @VisibleForTesting
  static boolean isFitForDictionaryBasedPlan(QueryContext queryContext, IndexSegment indexSegment) {
    List<ExpressionContext> selectExpressions = queryContext.getSelectExpressions();
    for (ExpressionContext expression : selectExpressions) {
      FunctionContext function = expression.getFunction();
      String functionName = function.getFunctionName();
      if (!functionName.equals("min") && !functionName.equals("max") && !functionName.equals("minmaxrange")) {
        return false;
      }
      ExpressionContext argument = function.getArguments().get(0);
      if (argument.getType() != ExpressionContext.Type.IDENTIFIER) {
        return false;
      }
      String column = argument.getIdentifier();
      Dictionary dictionary = indexSegment.getDataSource(column).getDictionary();
      if (dictionary == null || !dictionary.isSorted()) {
        return false;
      }
    }
    return true;
  }
}
