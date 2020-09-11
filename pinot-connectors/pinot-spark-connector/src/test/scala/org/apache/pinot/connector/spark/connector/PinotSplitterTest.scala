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
package org.apache.pinot.connector.spark.connector

import org.apache.pinot.connector.spark.BaseTest
import org.apache.pinot.connector.spark.connector.query.GeneratedSQLs
import org.apache.pinot.connector.spark.exceptions.PinotException
import org.apache.pinot.spi.config.table.TableType

/**
 * Test num of Spark partitions by routing table and input configs.
 */
class PinotSplitterTest extends BaseTest {
  private val generatedPql = GeneratedSQLs("tbl", None, "", "")

  private val routingTable = Map(
    TableType.OFFLINE -> Map(
      "Server_192.168.1.100_7000" -> List("segment1", "segment2", "segment3"),
      "Server_192.168.2.100_9000" -> List("segment4"),
      "Server_192.168.3.100_7000" -> List("segment5", "segment6")
    ),
    TableType.REALTIME -> Map(
      "Server_192.168.33.100_5000" -> List("segment10", "segment11", "segment12"),
      "Server_192.168.44.100_7000" -> List("segment13")
    )
  )

  test("Total 5 partition splits should be created for maxNumSegmentPerServerRequest = 3") {
    val maxNumSegmentPerServerRequest = 3
    val splitResults =
      PinotSplitter.generatePinotSplits(generatedPql, routingTable, maxNumSegmentPerServerRequest)

    splitResults.size shouldEqual 5
  }

  test("Total 5 partition splits should be created for maxNumSegmentPerServerRequest = 90") {
    val maxNumSegmentPerServerRequest = 90
    val splitResults =
      PinotSplitter.generatePinotSplits(generatedPql, routingTable, maxNumSegmentPerServerRequest)

    splitResults.size shouldEqual 5
  }

  test("Total 10 partition splits should be created for maxNumSegmentPerServerRequest = 1") {
    val maxNumSegmentPerServerRequest = 1
    val splitResults =
      PinotSplitter.generatePinotSplits(generatedPql, routingTable, maxNumSegmentPerServerRequest)

    splitResults.size shouldEqual 10
  }

  test("Input pinot server string should be parsed successfully") {
    val inputRoutingTable = Map(
      TableType.REALTIME -> Map("Server_192.168.1.100_9000" -> List("segment1"))
    )

    val splitResults = PinotSplitter.generatePinotSplits(generatedPql, inputRoutingTable, 5)
    val expectedOutput = List(
      PinotSplit(
        generatedPql,
        PinotServerAndSegments("192.168.1.100", "9000", List("segment1"), TableType.REALTIME)
      )
    )

    expectedOutput should contain theSameElementsAs splitResults
  }

  test("GeneratePinotSplits method should throw exception due to wrong input Server_HOST_PORT") {
    val inputRoutingTable = Map(
      TableType.REALTIME -> Map(
        "Server_192.168.1.100_9000" -> List("segment1"),
        "Server_192.168.2.100" -> List("segment5")
      )
    )

    val exception = intercept[PinotException] {
      PinotSplitter.generatePinotSplits(generatedPql, inputRoutingTable, 5)
    }

    exception.getMessage shouldEqual "'Server_192.168.2.100' did not match!?"
  }

}
