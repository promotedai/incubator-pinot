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
package org.apache.pinot.core.query.reduce;

import com.google.common.base.Preconditions;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.apache.pinot.common.metrics.BrokerMeter;
import org.apache.pinot.common.metrics.BrokerMetrics;
import org.apache.pinot.common.response.broker.AggregationResult;
import org.apache.pinot.common.response.broker.BrokerResponseNative;
import org.apache.pinot.common.response.broker.GroupByResult;
import org.apache.pinot.common.response.broker.ResultTable;
import org.apache.pinot.common.utils.DataSchema;
import org.apache.pinot.common.utils.DataTable;
import org.apache.pinot.core.data.table.ConcurrentIndexedTable;
import org.apache.pinot.core.data.table.IndexedTable;
import org.apache.pinot.core.data.table.Record;
import org.apache.pinot.core.query.aggregation.function.AggregationFunction;
import org.apache.pinot.core.query.aggregation.function.AggregationFunctionUtils;
import org.apache.pinot.core.query.aggregation.groupby.AggregationGroupByTrimmingService;
import org.apache.pinot.core.query.request.context.ExpressionContext;
import org.apache.pinot.core.query.request.context.FunctionContext;
import org.apache.pinot.core.query.request.context.QueryContext;
import org.apache.pinot.core.transport.ServerRoutingInstance;
import org.apache.pinot.core.util.GroupByUtils;
import org.apache.pinot.core.util.QueryOptions;


/**
 * Helper class to reduce data tables and set group by results into the BrokerResponseNative
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class GroupByDataTableReducer implements DataTableReducer {
  private final QueryContext _queryContext;
  private final AggregationFunction[] _aggregationFunctions;
  private final int _numAggregationFunctions;
  private final List<ExpressionContext> _groupByExpressions;
  private final int _numGroupByExpressions;
  private final int _numColumns;
  private final boolean _preserveType;
  private final boolean _groupByModeSql;
  private final boolean _responseFormatSql;
  private final boolean _sqlQuery;

  GroupByDataTableReducer(QueryContext queryContext, AggregationFunction[] aggregationFunctions) {
    _queryContext = queryContext;
    _aggregationFunctions = aggregationFunctions;
    _numAggregationFunctions = aggregationFunctions.length;
    _groupByExpressions = queryContext.getGroupByExpressions();
    assert _groupByExpressions != null;
    _numGroupByExpressions = _groupByExpressions.size();
    _numColumns = _numAggregationFunctions + _numGroupByExpressions;
    QueryOptions queryOptions = new QueryOptions(queryContext.getQueryOptions());
    _preserveType = queryOptions.isPreserveType();
    _groupByModeSql = queryOptions.isGroupByModeSQL();
    _responseFormatSql = queryOptions.isResponseFormatSQL();
    _sqlQuery = queryContext.getBrokerRequest().getPinotQuery() != null;
  }

  /**
   * Reduces and sets group by results into ResultTable, if responseFormat = sql
   * By default, sets group by results into GroupByResults
   */
  @Override
  public void reduceAndSetResults(String tableName, DataSchema dataSchema,
      Map<ServerRoutingInstance, DataTable> dataTableMap, BrokerResponseNative brokerResponseNative,
      BrokerMetrics brokerMetrics) {
    assert dataSchema != null;
    int resultSize = 0;
    Collection<DataTable> dataTables = dataTableMap.values();

    // For group by, PQL behavior is different than the SQL behavior. In the PQL way,
    // a result is generated for each aggregation in the query,
    // and the group by keys are not the same across the aggregations
    // This PQL style of execution makes it impossible to support order by on group by.
    //
    // We could not simply change the group by execution behavior,
    // as that would not be backward compatible for existing users of group by.
    // As a result, we have 2 modes of group by execution - pql and sql - which can be controlled via query options
    //
    // Long term, we may completely move to sql, and keep only full sql mode alive
    // Until then, we need to support responseFormat = sql for both the modes of execution.
    // The 4 variants are as described below:

    if (_groupByModeSql) {

      if (_responseFormatSql) {
        // 1. groupByMode = sql, responseFormat = sql
        // This is the primary SQL compliant group by

        setSQLGroupByInResultTable(brokerResponseNative, dataSchema, dataTables);
        resultSize = brokerResponseNative.getResultTable().getRows().size();
      } else {
        // 2. groupByMode = sql, responseFormat = pql
        // This mode will invoke SQL style group by execution, but present results in PQL way
        // This mode is useful for users who want to avail of SQL compliant group by behavior,
        // w/o having to forcefully move to a new result type

        setSQLGroupByInAggregationResults(brokerResponseNative, dataSchema, dataTables);
        if (!brokerResponseNative.getAggregationResults().isEmpty()) {
          resultSize = brokerResponseNative.getAggregationResults().get(0).getGroupByResult().size();
        }
      }
    } else {

      // 3. groupByMode = pql, responseFormat = sql
      // This mode is for users who want response presented in SQL style, but want PQL style group by behavior
      // Multiple aggregations in PQL violates the tabular nature of results
      // As a result, in this mode, only single aggregations are supported

      // 4. groupByMode = pql, responseFormat = pql
      // This is the primary PQL compliant group by

      setGroupByResults(brokerResponseNative, dataTables);

      if (_responseFormatSql) {
        resultSize = brokerResponseNative.getResultTable().getRows().size();
      } else {
        // We emit the group by size when the result isn't empty. All the sizes among group-by results should be the same.
        // Thus, we can just emit the one from the 1st result.
        if (!brokerResponseNative.getAggregationResults().isEmpty()) {
          resultSize = brokerResponseNative.getAggregationResults().get(0).getGroupByResult().size();
        }
      }
    }

    if (brokerMetrics != null && resultSize > 0) {
      brokerMetrics.addMeteredTableValue(tableName, BrokerMeter.GROUP_BY_SIZE, resultSize);
    }
  }

  /**
   * Extract group by order by results and set into {@link ResultTable}
   * @param brokerResponseNative broker response
   * @param dataSchema data schema
   * @param dataTables Collection of data tables
   */
  private void setSQLGroupByInResultTable(BrokerResponseNative brokerResponseNative, DataSchema dataSchema,
      Collection<DataTable> dataTables) {
    DataSchema resultTableSchema = getSQLResultTableSchema(dataSchema);
    IndexedTable indexedTable = getIndexedTable(dataSchema, dataTables);
    Iterator<Record> sortedIterator = indexedTable.iterator();
    int limit = _queryContext.getLimit();
    List<Object[]> rows = new ArrayList<>(limit);

    if (_sqlQuery) {
      // SQL query with SQL group-by mode and response format
      // NOTE: For SQL query, need to reorder the columns in the data table based on the select expressions.

      int[] selectExpressionIndexMap = getSelectExpressionIndexMap();
      int numSelectExpressions = selectExpressionIndexMap.length;
      String[] columnNames = resultTableSchema.getColumnNames();
      DataSchema.ColumnDataType[] columnDataTypes = resultTableSchema.getColumnDataTypes();
      String[] reorderedColumnNames = new String[numSelectExpressions];
      DataSchema.ColumnDataType[] reorderedColumnDataTypes = new DataSchema.ColumnDataType[numSelectExpressions];
      resultTableSchema = new DataSchema(reorderedColumnNames, reorderedColumnDataTypes);
      for (int i = 0; i < numSelectExpressions; i++) {
        reorderedColumnNames[i] = columnNames[selectExpressionIndexMap[i]];
        reorderedColumnDataTypes[i] = columnDataTypes[selectExpressionIndexMap[i]];
      }
      while (rows.size() < limit && sortedIterator.hasNext()) {
        Record nextRecord = sortedIterator.next();
        Object[] values = nextRecord.getValues();
        for (int i = 0; i < _numAggregationFunctions; i++) {
          int valueIndex = i + _numGroupByExpressions;
          values[valueIndex] = AggregationFunctionUtils
              .getSerializableValue(_aggregationFunctions[i].extractFinalResult(values[valueIndex]));
        }

        Object[] reorderedValues = new Object[numSelectExpressions];
        for (int i = 0; i < numSelectExpressions; i++) {
          reorderedValues[i] = values[selectExpressionIndexMap[i]];
        }
        rows.add(reorderedValues);
      }
    } else {
      // PQL query with SQL group-by mode and response format

      while (rows.size() < limit && sortedIterator.hasNext()) {
        Record nextRecord = sortedIterator.next();
        Object[] values = nextRecord.getValues();
        for (int i = 0; i < _numAggregationFunctions; i++) {
          int valueIndex = i + _numGroupByExpressions;
          values[valueIndex] = AggregationFunctionUtils
              .getSerializableValue(_aggregationFunctions[i].extractFinalResult(values[valueIndex]));
        }
        rows.add(values);
      }
    }

    brokerResponseNative.setResultTable(new ResultTable(resultTableSchema, rows));
  }

  /**
   * Helper method to generate a map from the expression index in the select expressions to the column index in the data
   * schema. This map is used to reorder the expressions according to the select expressions.
   */
  private int[] getSelectExpressionIndexMap() {
    List<ExpressionContext> selectExpressions = _queryContext.getSelectExpressions();
    List<ExpressionContext> groupByExpressions = _queryContext.getGroupByExpressions();
    assert groupByExpressions != null;
    int numSelectExpressions = selectExpressions.size();
    int[] selectExpressionIndexMap = new int[numSelectExpressions];
    int aggregationExpressionIndex = _numGroupByExpressions;
    for (int i = 0; i < numSelectExpressions; i++) {
      ExpressionContext selectExpression = selectExpressions.get(i);
      if (selectExpression.getType() == ExpressionContext.Type.FUNCTION
          && selectExpression.getFunction().getType() == FunctionContext.Type.AGGREGATION) {
        selectExpressionIndexMap[i] = aggregationExpressionIndex++;
      } else {
        int indexInGroupByExpressions = groupByExpressions.indexOf(selectExpression);
        Preconditions.checkState(indexInGroupByExpressions >= 0,
            "Select expression: %s is not an aggregation expression and not contained in the group-by expressions");
        selectExpressionIndexMap[i] = indexInGroupByExpressions;
      }
    }
    return selectExpressionIndexMap;
  }

  /**
   * Constructs the final result table schema for sql mode execution
   * The data type for the aggregations needs to be taken from the final result's data type
   */
  private DataSchema getSQLResultTableSchema(DataSchema dataSchema) {
    String[] columns = dataSchema.getColumnNames();
    DataSchema.ColumnDataType[] finalColumnDataTypes = new DataSchema.ColumnDataType[_numColumns];
    int aggIdx = 0;
    for (int i = 0; i < _numColumns; i++) {
      if (i < _numGroupByExpressions) {
        finalColumnDataTypes[i] = dataSchema.getColumnDataType(i);
      } else {
        finalColumnDataTypes[i] = _aggregationFunctions[aggIdx].getFinalResultColumnType();
        aggIdx++;
      }
    }
    return new DataSchema(columns, finalColumnDataTypes);
  }

  private IndexedTable getIndexedTable(DataSchema dataSchema, Collection<DataTable> dataTables) {
    int indexedTableCapacity = GroupByUtils.getTableCapacity(_queryContext);
    IndexedTable indexedTable =
        new ConcurrentIndexedTable(dataSchema, _aggregationFunctions, _queryContext.getOrderByExpressions(),
            indexedTableCapacity);

    for (DataTable dataTable : dataTables) {
      BiFunction[] functions = new BiFunction[_numColumns];
      for (int i = 0; i < _numColumns; i++) {
        DataSchema.ColumnDataType columnDataType = dataSchema.getColumnDataType(i);
        BiFunction<Integer, Integer, Object> function;
        switch (columnDataType) {
          case INT:
            function = dataTable::getInt;
            break;
          case LONG:
            function = dataTable::getLong;
            break;
          case FLOAT:
            function = dataTable::getFloat;
            break;
          case DOUBLE:
            function = dataTable::getDouble;
            break;
          case STRING:
            function = dataTable::getString;
            break;
          case BYTES:
            function = dataTable::getBytes;
            break;
          case OBJECT:
            function = dataTable::getObject;
            break;
          // Add other aggregation intermediate result / group-by column type supports here
          default:
            throw new IllegalStateException();
        }
        functions[i] = function;
      }

      for (int row = 0; row < dataTable.getNumberOfRows(); row++) {
        Object[] columns = new Object[_numColumns];
        for (int col = 0; col < _numColumns; col++) {
          columns[col] = functions[col].apply(row, col);
        }
        Record record = new Record(columns);
        indexedTable.upsert(record);
      }
    }
    indexedTable.finish(true);
    return indexedTable;
  }

  /**
   * Extract the results of group by order by into a List of {@link AggregationResult}
   * There will be 1 aggregation result per aggregation. The group by keys will be the same across all aggregations
   * @param brokerResponseNative broker response
   * @param dataSchema data schema
   * @param dataTables Collection of data tables
   */
  private void setSQLGroupByInAggregationResults(BrokerResponseNative brokerResponseNative, DataSchema dataSchema,
      Collection<DataTable> dataTables) {

    List<String> groupByColumns = new ArrayList<>(_numGroupByExpressions);
    int idx = 0;
    while (idx < _numGroupByExpressions) {
      groupByColumns.add(dataSchema.getColumnName(idx));
      idx++;
    }

    List<String> aggregationColumns = new ArrayList<>(_numAggregationFunctions);
    List<List<GroupByResult>> groupByResults = new ArrayList<>(_numAggregationFunctions);
    while (idx < _numColumns) {
      aggregationColumns.add(dataSchema.getColumnName(idx));
      groupByResults.add(new ArrayList<>());
      idx++;
    }

    if (!dataTables.isEmpty()) {
      IndexedTable indexedTable = getIndexedTable(dataSchema, dataTables);

      int limit = _queryContext.getLimit();
      Iterator<Record> sortedIterator = indexedTable.iterator();
      int numRows = 0;
      while (numRows < limit && sortedIterator.hasNext()) {
        Record nextRecord = sortedIterator.next();
        Object[] values = nextRecord.getValues();

        int index = 0;
        List<String> group = new ArrayList<>(_numGroupByExpressions);
        while (index < _numGroupByExpressions) {
          group.add(values[index].toString());
          index++;
        }

        int aggNum = 0;
        while (index < _numColumns) {
          Serializable serializableValue =
              getSerializableValue(_aggregationFunctions[aggNum].extractFinalResult(values[index]));
          if (!_preserveType) {
            serializableValue = AggregationFunctionUtils.formatValue(serializableValue);
          }
          GroupByResult groupByResult = new GroupByResult();
          groupByResult.setGroup(group);
          groupByResult.setValue(serializableValue);
          groupByResults.get(aggNum).add(groupByResult);
          index++;
          aggNum++;
        }
        numRows++;
      }
    }

    List<AggregationResult> aggregationResults = new ArrayList<>(_numAggregationFunctions);
    for (int i = 0; i < _numAggregationFunctions; i++) {
      AggregationResult aggregationResult =
          new AggregationResult(groupByResults.get(i), groupByColumns, aggregationColumns.get(i));
      aggregationResults.add(aggregationResult);
    }
    brokerResponseNative.setAggregationResults(aggregationResults);
  }

  private Serializable getSerializableValue(Object value) {
    if (value instanceof Number) {
      return (Number) value;
    } else {
      return value.toString();
    }
  }

  /**
   * Constructs the final result table schema for PQL execution mode
   */
  private DataSchema getPQLResultTableSchema(AggregationFunction aggregationFunction) {
    String[] columnNames = new String[_numColumns];
    DataSchema.ColumnDataType[] columnDataTypes = new DataSchema.ColumnDataType[_numColumns];
    for (int i = 0; i < _numGroupByExpressions; i++) {
      columnNames[i] = _groupByExpressions.get(i).toString();
      columnDataTypes[i] = DataSchema.ColumnDataType.STRING;
    }
    columnNames[_numGroupByExpressions] = aggregationFunction.getResultColumnName();
    columnDataTypes[_numGroupByExpressions] = aggregationFunction.getFinalResultColumnType();
    return new DataSchema(columnNames, columnDataTypes);
  }

  /**
   * Reduce group-by results from multiple servers and set them into BrokerResponseNative passed in.
   *
   * @param brokerResponseNative broker response.
   * @param dataTables Collection of data tables
   */
  private void setGroupByResults(BrokerResponseNative brokerResponseNative, Collection<DataTable> dataTables) {

    // Merge results from all data tables.
    String[] columnNames = new String[_numAggregationFunctions];
    Map<String, Object>[] intermediateResultMaps = new Map[_numAggregationFunctions];
    for (DataTable dataTable : dataTables) {
      for (int i = 0; i < _numAggregationFunctions; i++) {
        if (columnNames[i] == null) {
          columnNames[i] = dataTable.getString(i, 0);
          intermediateResultMaps[i] = dataTable.getObject(i, 1);
        } else {
          Map<String, Object> mergedIntermediateResultMap = intermediateResultMaps[i];
          Map<String, Object> intermediateResultMapToMerge = dataTable.getObject(i, 1);
          for (Map.Entry<String, Object> entry : intermediateResultMapToMerge.entrySet()) {
            String groupKey = entry.getKey();
            Object intermediateResultToMerge = entry.getValue();
            if (mergedIntermediateResultMap.containsKey(groupKey)) {
              Object mergedIntermediateResult = mergedIntermediateResultMap.get(groupKey);
              mergedIntermediateResultMap
                  .put(groupKey, _aggregationFunctions[i].merge(mergedIntermediateResult, intermediateResultToMerge));
            } else {
              mergedIntermediateResultMap.put(groupKey, intermediateResultToMerge);
            }
          }
        }
      }
    }

    // Extract final result maps from the merged intermediate result maps.
    Map<String, Comparable>[] finalResultMaps = new Map[_numAggregationFunctions];
    for (int i = 0; i < _numAggregationFunctions; i++) {
      Map<String, Object> intermediateResultMap = intermediateResultMaps[i];
      Map<String, Comparable> finalResultMap = new HashMap<>();
      for (String groupKey : intermediateResultMap.keySet()) {
        Object intermediateResult = intermediateResultMap.get(groupKey);
        finalResultMap.put(groupKey, _aggregationFunctions[i].extractFinalResult(intermediateResult));
      }
      finalResultMaps[i] = finalResultMap;
    }

    // Trim the final result maps and set them into the broker response.
    AggregationGroupByTrimmingService aggregationGroupByTrimmingService =
        new AggregationGroupByTrimmingService(_aggregationFunctions, _queryContext.getLimit());
    List<GroupByResult>[] groupByResultLists = aggregationGroupByTrimmingService.trimFinalResults(finalResultMaps);

    if (_responseFormatSql) {
      assert _numAggregationFunctions == 1;
      List<GroupByResult> groupByResultList = groupByResultLists[0];
      List<Object[]> rows = new ArrayList<>();
      for (GroupByResult groupByResult : groupByResultList) {
        Object[] row = new Object[_numColumns];
        int i = 0;
        for (String column : groupByResult.getGroup()) {
          row[i++] = column;
        }
        row[i] = groupByResult.getValue();
        rows.add(row);
      }
      DataSchema finalDataSchema = getPQLResultTableSchema(_aggregationFunctions[0]);
      brokerResponseNative.setResultTable(new ResultTable(finalDataSchema, rows));
    } else {
      // Format the value into string if required
      if (!_preserveType) {
        for (List<GroupByResult> groupByResultList : groupByResultLists) {
          for (GroupByResult groupByResult : groupByResultList) {
            groupByResult.setValue(AggregationFunctionUtils.formatValue(groupByResult.getValue()));
          }
        }
      }
      List<String> groupByColumns = new ArrayList<>(_numGroupByExpressions);
      for (ExpressionContext groupByExpression : _groupByExpressions) {
        groupByColumns.add(groupByExpression.toString());
      }
      List<AggregationResult> aggregationResults = new ArrayList<>(_numAggregationFunctions);
      for (int i = 0; i < _numAggregationFunctions; i++) {
        aggregationResults.add(new AggregationResult(groupByResultLists[i], groupByColumns, columnNames[i]));
      }
      brokerResponseNative.setAggregationResults(aggregationResults);
    }
  }
}
