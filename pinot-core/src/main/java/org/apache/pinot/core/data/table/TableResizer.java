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
package org.apache.pinot.core.data.table;

import com.google.common.annotations.VisibleForTesting;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Function;
import org.apache.pinot.common.utils.DataSchema;
import org.apache.pinot.core.query.aggregation.function.AggregationFunction;
import org.apache.pinot.core.query.request.context.OrderByExpressionContext;


/**
 * Helper class for trimming and sorting records in the IndexedTable, based on the order by information
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class TableResizer {
  private final OrderByValueExtractor[] _orderByValueExtractors;
  private final Comparator<IntermediateRecord> _intermediateRecordComparator;
  private final int _numOrderByExpressions;

  TableResizer(DataSchema dataSchema, AggregationFunction[] aggregationFunctions,
      List<OrderByExpressionContext> orderByExpressions) {

    // NOTE: the assumption here is that the key columns will appear before the aggregation columns in the data schema
    // This is handled in the only in the AggregationGroupByOrderByOperator for now

    int numColumns = dataSchema.size();
    int numAggregations = aggregationFunctions.length;
    int numKeyColumns = numColumns - numAggregations;

    Map<String, Integer> columnIndexMap = new HashMap<>();
    Map<String, AggregationFunction> aggregationColumnToFunction = new HashMap<>();
    for (int i = 0; i < numColumns; i++) {
      String columnName = dataSchema.getColumnName(i);
      columnIndexMap.put(columnName, i);
      if (i >= numKeyColumns) {
        aggregationColumnToFunction.put(columnName, aggregationFunctions[i - numKeyColumns]);
      }
    }

    _numOrderByExpressions = orderByExpressions.size();
    _orderByValueExtractors = new OrderByValueExtractor[_numOrderByExpressions];
    Comparator[] comparators = new Comparator[_numOrderByExpressions];

    for (int orderByIdx = 0; orderByIdx < _numOrderByExpressions; orderByIdx++) {
      OrderByExpressionContext orderByExpression = orderByExpressions.get(orderByIdx);
      String column = orderByExpression.getExpression().toString();

      if (columnIndexMap.containsKey(column)) {
        int index = columnIndexMap.get(column);
        if (index < numKeyColumns) {
          _orderByValueExtractors[orderByIdx] = new KeyColumnExtractor(index);
        } else {
          AggregationFunction aggregationFunction = aggregationColumnToFunction.get(column);
          _orderByValueExtractors[orderByIdx] = new AggregationColumnExtractor(index, aggregationFunction);
        }
      } else {
        throw new IllegalStateException("Could not find column " + column + " in data schema");
      }

      comparators[orderByIdx] = orderByExpression.isAsc() ? Comparator.naturalOrder() : Comparator.reverseOrder();
    }

    _intermediateRecordComparator = (o1, o2) -> {

      for (int i = 0; i < _numOrderByExpressions; i++) {
        int result = comparators[i].compare(o1._values[i], o2._values[i]);
        if (result != 0) {
          return result;
        }
      }
      return 0;
    };
  }

  /**
   * Constructs an IntermediateRecord from Record
   * The IntermediateRecord::key is the same Record::key
   * The IntermediateRecord::values contains only the order by columns, in the query's sort sequence
   * For aggregation values in the order by, the final result is extracted if the intermediate result is non-comparable
   */
  @VisibleForTesting
  IntermediateRecord getIntermediateRecord(Key key, Record record) {
    Comparable[] intermediateRecordValues = new Comparable[_numOrderByExpressions];
    for (int i = 0; i < _numOrderByExpressions; i++) {
      intermediateRecordValues[i] = _orderByValueExtractors[i].extract(record);
    }
    return new IntermediateRecord(key, intermediateRecordValues);
  }

  /**
   * Trim recordsMap to trimToSize, based on order by information
   * Resize only if number of records is greater than trimToSize
   * The resizer smartly chooses to create PQ of records to evict or records to retain, based on the number of records and the number of records to evict
   */
  void resizeRecordsMap(Map<Key, Record> recordsMap, int trimToSize) {

    int numRecordsToEvict = recordsMap.size() - trimToSize;

    if (numRecordsToEvict > 0) {
      // TODO: compare the performance of converting to IntermediateRecord vs keeping Record, in cases where we do not need to extract final results

      if (numRecordsToEvict < trimToSize) { // num records to evict is smaller than num records to retain
        // make PQ of records to evict
        Comparator<IntermediateRecord> comparator = _intermediateRecordComparator;
        PriorityQueue<IntermediateRecord> priorityQueue =
            convertToIntermediateRecordsPQ(recordsMap, numRecordsToEvict, comparator);
        for (IntermediateRecord evictRecord : priorityQueue) {
          recordsMap.remove(evictRecord._key);
        }
      } else { // num records to retain is smaller than num records to evict
        // make PQ of records to retain
        Comparator<IntermediateRecord> comparator = _intermediateRecordComparator.reversed();
        PriorityQueue<IntermediateRecord> priorityQueue =
            convertToIntermediateRecordsPQ(recordsMap, trimToSize, comparator);
        ObjectOpenHashSet<Key> keysToRetain = new ObjectOpenHashSet<>(priorityQueue.size());
        for (IntermediateRecord retainRecord : priorityQueue) {
          keysToRetain.add(retainRecord._key);
        }
        recordsMap.keySet().retainAll(keysToRetain);
      }
    }
  }

  private PriorityQueue<IntermediateRecord> convertToIntermediateRecordsPQ(Map<Key, Record> recordsMap, int size,
      Comparator<IntermediateRecord> comparator) {
    PriorityQueue<IntermediateRecord> priorityQueue = new PriorityQueue<>(size, comparator);

    for (Map.Entry<Key, Record> entry : recordsMap.entrySet()) {

      IntermediateRecord intermediateRecord = getIntermediateRecord(entry.getKey(), entry.getValue());
      if (priorityQueue.size() < size) {
        priorityQueue.offer(intermediateRecord);
      } else {
        IntermediateRecord peek = priorityQueue.peek();
        if (comparator.compare(peek, intermediateRecord) < 0) {
          priorityQueue.poll();
          priorityQueue.offer(intermediateRecord);
        }
      }
    }
    return priorityQueue;
  }

  private List<Record> sortRecordsMap(Map<Key, Record> recordsMap) {
    int numRecords = recordsMap.size();
    List<Record> sortedRecords = new ArrayList<>(numRecords);
    List<IntermediateRecord> intermediateRecords = new ArrayList<>(numRecords);
    for (Map.Entry<Key, Record> entry : recordsMap.entrySet()) {
      intermediateRecords.add(getIntermediateRecord(entry.getKey(), entry.getValue()));
    }
    intermediateRecords.sort(_intermediateRecordComparator);
    for (IntermediateRecord intermediateRecord : intermediateRecords) {
      sortedRecords.add(recordsMap.get(intermediateRecord._key));
    }
    return sortedRecords;
  }

  /**
   * Resizes the recordsMap and returns a sorted list of records.
   * This method is to be called from IndexedTable::finish, if both resize and sort is needed
   *
   * If numRecordsToEvict > numRecordsToRetain, resize with PQ of records to evict, and then sort
   * Else, resize with PQ of record to retain, then use the PQ to create sorted list
   */
  List<Record> resizeAndSortRecordsMap(Map<Key, Record> recordsMap, int trimToSize) {

    int numRecords = recordsMap.size();
    if (numRecords == 0) {
      return Collections.emptyList();
    }

    int numRecordsToRetain = Math.min(numRecords, trimToSize);
    int numRecordsToEvict = numRecords - numRecordsToRetain;

    if (numRecordsToEvict < numRecordsToRetain) { // num records to evict is smaller than num records to retain
      if (numRecordsToEvict > 0) {
        // make PQ of records to evict
        PriorityQueue<IntermediateRecord> priorityQueue =
            convertToIntermediateRecordsPQ(recordsMap, numRecordsToEvict, _intermediateRecordComparator);
        for (IntermediateRecord evictRecord : priorityQueue) {
          recordsMap.remove(evictRecord._key);
        }
      }
      return sortRecordsMap(recordsMap);
    } else {
      // make PQ of records to retain
      PriorityQueue<IntermediateRecord> priorityQueue =
          convertToIntermediateRecordsPQ(recordsMap, numRecordsToRetain, _intermediateRecordComparator.reversed());
      // use PQ to get sorted list
      Record[] sortedArray = new Record[numRecordsToRetain];
      ObjectOpenHashSet<Key> keysToRetain = new ObjectOpenHashSet<>(numRecordsToRetain);
      while (!priorityQueue.isEmpty()) {
        IntermediateRecord intermediateRecord = priorityQueue.poll();
        keysToRetain.add(intermediateRecord._key);
        Record record = recordsMap.get(intermediateRecord._key);
        sortedArray[--numRecordsToRetain] = record;
      }
      recordsMap.keySet().retainAll(keysToRetain);
      return Arrays.asList(sortedArray);
    }
  }

  /**
   * Helper class to store a subset of Record fields
   * IntermediateRecord is derived from a Record
   * Some of the main properties of an IntermediateRecord are:
   *
   * 1. Key in IntermediateRecord is expected to be identical to the one in the Record
   * 2. For values, IntermediateRecord should only have the columns needed for order by
   * 3. Inside the values, the columns should be ordered by the order by sequence
   * 4. For order by on aggregations, final results should extracted if the intermediate result is non-comparable
   */
  @VisibleForTesting
  static class IntermediateRecord {
    final Key _key;
    final Comparable[] _values;

    IntermediateRecord(Key key, Comparable[] values) {
      _key = key;
      _values = values;
    }
  }

  /**
   * Extractor for order by value columns from Record
   */
  private static abstract class OrderByValueExtractor {
    abstract Comparable extract(Record record);
  }

  /**
   * Extractor for key column
   */
  private static class KeyColumnExtractor extends OrderByValueExtractor {
    final int _index;

    KeyColumnExtractor(int index) {
      _index = index;
    }

    @Override
    Comparable extract(Record record) {
      Object keyColumn = record.getValues()[_index];
      return (Comparable) keyColumn;
    }
  }

  /**
   * Extractor for aggregation column
   */
  private static class AggregationColumnExtractor extends OrderByValueExtractor {
    final int _index;
    final Function<Object, Comparable> _convertorFunction;

    AggregationColumnExtractor(int index, AggregationFunction aggregationFunction) {
      _index = index;
      if (aggregationFunction.isIntermediateResultComparable()) {
        _convertorFunction = o -> (Comparable) o;
      } else {
        _convertorFunction = o -> aggregationFunction.extractFinalResult(o);
      }
    }

    @Override
    Comparable extract(Record record) {
      Object aggregationColumn = record.getValues()[_index];
      return _convertorFunction.apply(aggregationColumn);
    }
  }
}
