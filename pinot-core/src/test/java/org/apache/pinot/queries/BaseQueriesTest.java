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
package org.apache.pinot.queries;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.Nullable;
import org.apache.pinot.common.request.BrokerRequest;
import org.apache.pinot.common.response.broker.BrokerResponseNative;
import org.apache.pinot.common.utils.CommonConstants.Broker.Request;
import org.apache.pinot.common.utils.CommonConstants.Server;
import org.apache.pinot.common.utils.DataTable;
import org.apache.pinot.core.common.Operator;
import org.apache.pinot.core.indexsegment.IndexSegment;
import org.apache.pinot.core.plan.Plan;
import org.apache.pinot.core.plan.maker.InstancePlanMakerImplV2;
import org.apache.pinot.core.plan.maker.PlanMaker;
import org.apache.pinot.core.query.reduce.BrokerReduceService;
import org.apache.pinot.core.query.request.context.QueryContext;
import org.apache.pinot.core.query.request.context.utils.BrokerRequestToQueryContextConverter;
import org.apache.pinot.core.query.request.context.utils.QueryContextConverterUtils;
import org.apache.pinot.core.transport.ServerRoutingInstance;
import org.apache.pinot.pql.parsers.Pql2Compiler;
import org.apache.pinot.spi.config.table.TableType;
import org.apache.pinot.sql.parsers.CalciteSqlCompiler;


/**
 * Base class for queries tests.
 */
public abstract class BaseQueriesTest {
  protected static final Pql2Compiler PQL_COMPILER = new Pql2Compiler();
  protected static final CalciteSqlCompiler SQL_COMPILER = new CalciteSqlCompiler();
  protected static final PlanMaker PLAN_MAKER = new InstancePlanMakerImplV2();
  protected static final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(2);

  protected abstract String getFilter();

  protected abstract IndexSegment getIndexSegment();

  protected abstract List<IndexSegment> getIndexSegments();

  /**
   * Run PQL query on single index segment.
   * <p>Use this to test a single operator.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  protected <T extends Operator> T getOperatorForQuery(String pqlQuery) {
    QueryContext queryContext = QueryContextConverterUtils.getQueryContextFromPQL(pqlQuery);
    return (T) PLAN_MAKER.makeSegmentPlanNode(getIndexSegment(), queryContext).run();
  }

  /**
   * Run PQL query with hard-coded filter on single index segment.
   * <p>Use this to test a single operator.
   */
  @SuppressWarnings("rawtypes")
  protected <T extends Operator> T getOperatorForQueryWithFilter(String pqlQuery) {
    return getOperatorForQuery(pqlQuery + getFilter());
  }

  /**
   * Run PQL query on multiple index segments.
   * <p>Use this to test the whole flow from server to broker.
   * <p>The result should be equivalent to querying 4 identical index segments.
   */
  protected BrokerResponseNative getBrokerResponseForPqlQuery(String pqlQuery) {
    return getBrokerResponseForPqlQuery(pqlQuery, PLAN_MAKER);
  }

  /**
   * Run PQL query with hard-coded filter on multiple index segments.
   * <p>Use this to test the whole flow from server to broker.
   * <p>The result should be equivalent to querying 4 identical index segments.
   */
  protected BrokerResponseNative getBrokerResponseForPqlQueryWithFilter(String pqlQuery) {
    return getBrokerResponseForPqlQuery(pqlQuery + getFilter());
  }

  /**
   * Run PQL query on multiple index segments with custom plan maker.
   * <p>Use this to test the whole flow from server to broker.
   * <p>The result should be equivalent to querying 4 identical index segments.
   */
  protected BrokerResponseNative getBrokerResponseForPqlQuery(String pqlQuery, PlanMaker planMaker) {
    return getBrokerResponseForPqlQuery(pqlQuery, planMaker, null);
  }

  /**
   * Run PQL query on multiple index segments.
   * <p>Use this to test the whole flow from server to broker.
   * <p>The result should be equivalent to querying 4 identical index segments.
   */
  protected BrokerResponseNative getBrokerResponseForPqlQuery(String pqlQuery,
      @Nullable Map<String, String> extraQueryOptions) {
    return getBrokerResponseForPqlQuery(pqlQuery, PLAN_MAKER, extraQueryOptions);
  }

  /**
   * Run PQL query on multiple index segments with custom plan maker and queryOptions.
   * <p>Use this to test the whole flow from server to broker.
   * <p>The result should be equivalent to querying 4 identical index segments.
   */
  private BrokerResponseNative getBrokerResponseForPqlQuery(String pqlQuery, PlanMaker planMaker,
      @Nullable Map<String, String> extraQueryOptions) {
    BrokerRequest brokerRequest = PQL_COMPILER.compileToBrokerRequest(pqlQuery);
    if (extraQueryOptions != null) {
      Map<String, String> queryOptions = brokerRequest.getQueryOptions();
      if (queryOptions != null) {
        queryOptions.putAll(extraQueryOptions);
      } else {
        brokerRequest.setQueryOptions(extraQueryOptions);
      }
    }
    QueryContext queryContext = BrokerRequestToQueryContextConverter.convert(brokerRequest);
    return getBrokerResponse(queryContext, planMaker);
  }

  /**
   * Run SQL query on multiple index segments.
   * <p>Use this to test the whole flow from server to broker.
   * <p>The result should be equivalent to querying 4 identical index segments.
   */
  protected BrokerResponseNative getBrokerResponseForSqlQuery(String sqlQuery) {
    return getBrokerResponseForSqlQuery(sqlQuery, PLAN_MAKER);
  }

  /**
   * Run SQL query with hard-coded filter on multiple index segments.
   * <p>Use this to test the whole flow from server to broker.
   * <p>The result should be equivalent to querying 4 identical index segments.
   */
  protected BrokerResponseNative getBrokerResponseForSqlQueryWithFilter(String sqlQuery) {
    return getBrokerResponseForSqlQuery(sqlQuery + getFilter());
  }

  /**
   * Run SQL query on multiple index segments with custom plan maker.
   * <p>Use this to test the whole flow from server to broker.
   * <p>The result should be equivalent to querying 4 identical index segments.
   */
  @SuppressWarnings("SameParameterValue")
  protected BrokerResponseNative getBrokerResponseForSqlQuery(String sqlQuery, PlanMaker planMaker) {
    BrokerRequest brokerRequest = SQL_COMPILER.compileToBrokerRequest(sqlQuery);
    Map<String, String> queryOptions = brokerRequest.getQueryOptions();
    if (queryOptions == null) {
      queryOptions = new HashMap<>();
      brokerRequest.setQueryOptions(queryOptions);
    }
    queryOptions.put(Request.QueryOptionKey.GROUP_BY_MODE, Request.SQL);
    queryOptions.put(Request.QueryOptionKey.RESPONSE_FORMAT, Request.SQL);
    QueryContext queryContext = BrokerRequestToQueryContextConverter.convert(brokerRequest);
    return getBrokerResponse(queryContext, planMaker);
  }

  /**
   * Run query on multiple index segments with custom plan maker.
   * <p>Use this to test the whole flow from server to broker.
   * <p>The result should be equivalent to querying 4 identical index segments.
   */
  private BrokerResponseNative getBrokerResponse(QueryContext queryContext, PlanMaker planMaker) {
    // Server side.
    Plan plan = planMaker
        .makeInstancePlan(getIndexSegments(), queryContext, EXECUTOR_SERVICE, Server.DEFAULT_QUERY_EXECUTOR_TIMEOUT_MS);
    DataTable instanceResponse = plan.execute();

    // Broker side.
    BrokerReduceService brokerReduceService = new BrokerReduceService();
    Map<ServerRoutingInstance, DataTable> dataTableMap = new HashMap<>();
    dataTableMap.put(new ServerRoutingInstance("localhost", 1234, TableType.OFFLINE), instanceResponse);
    dataTableMap.put(new ServerRoutingInstance("localhost", 1234, TableType.REALTIME), instanceResponse);
    return brokerReduceService.reduceOnDataTable(queryContext.getBrokerRequest(), dataTableMap, null);
  }
}
