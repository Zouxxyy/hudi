/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.client.transaction;

import org.apache.hudi.client.utils.TransactionUtils;
import org.apache.hudi.common.model.HoodieCommitMetadata;
import org.apache.hudi.common.model.WriteOperationType;
import org.apache.hudi.common.table.timeline.HoodieActiveTimeline;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.table.timeline.HoodieInstant.State;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.common.testutils.HoodieCommonTestHarness;
import org.apache.hudi.common.testutils.HoodieTestTable;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.config.HoodieLockConfig;
import org.apache.hudi.exception.HoodieWriteConflictException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.hudi.client.transaction.TestConflictResolutionStrategyUtil.createCommit;
import static org.apache.hudi.client.transaction.TestConflictResolutionStrategyUtil.createCommitMetadata;
import static org.apache.hudi.client.transaction.TestConflictResolutionStrategyUtil.createCompaction;
import static org.apache.hudi.client.transaction.TestConflictResolutionStrategyUtil.createCompactionRequested;
import static org.apache.hudi.client.transaction.TestConflictResolutionStrategyUtil.createCompleteCommit;
import static org.apache.hudi.client.transaction.TestConflictResolutionStrategyUtil.createCompleteCompaction;
import static org.apache.hudi.client.transaction.TestConflictResolutionStrategyUtil.createCompleteReplace;
import static org.apache.hudi.client.transaction.TestConflictResolutionStrategyUtil.createInflightCommit;
import static org.apache.hudi.client.transaction.TestConflictResolutionStrategyUtil.createPendingCompaction;
import static org.apache.hudi.client.transaction.TestConflictResolutionStrategyUtil.createPendingReplace;
import static org.apache.hudi.client.transaction.TestConflictResolutionStrategyUtil.createReplace;
import static org.apache.hudi.client.transaction.TestConflictResolutionStrategyUtil.createReplaceInflight;
import static org.apache.hudi.client.transaction.TestConflictResolutionStrategyUtil.createReplaceRequested;
import static org.apache.hudi.client.transaction.TestConflictResolutionStrategyUtil.createRequestedCommit;

public class TestSimpleConcurrentFileWritesConflictResolutionStrategy extends HoodieCommonTestHarness {

  @BeforeEach
  public void init() throws IOException {
    initMetaClient();
  }

  @AfterEach
  public void tearDown() throws IOException {
    cleanMetaClient();
  }

  private static Stream<Arguments> additionalProps() {
    return Stream.of(
        Arguments.of(createProperties(SimpleConcurrentFileWritesConflictResolutionStrategy.class.getName())),
        Arguments.of(createProperties(StateTransitionTimeBasedConflictResolutionStrategy.class.getName()))
    );
  }

  public static Properties createProperties(String conflictResolutionStrategyClassName) {
    Properties properties = new Properties();
    properties.setProperty(HoodieLockConfig.WRITE_CONFLICT_RESOLUTION_STRATEGY_CLASS_NAME.key(), conflictResolutionStrategyClassName);
    return properties;
  }

  @ParameterizedTest
  @MethodSource("additionalProps")
  public void testNoConcurrentWrites(Properties props) throws Exception {
    String newInstantTime = HoodieTestTable.makeNewCommitTime();
    createCommit(newInstantTime, metaClient);
    newInstantTime = HoodieTestTable.makeNewCommitTime();
    Option<HoodieInstant> currentInstant = Option.of(new HoodieInstant(State.INFLIGHT, HoodieTimeline.COMMIT_ACTION, newInstantTime));

    ConflictResolutionStrategy strategy = TestConflictResolutionStrategyUtil.getConflictResolutionStrategy(metaClient, props);
    metaClient.reloadActiveTimeline();
    Stream<HoodieInstant> candidateInstants = strategy.getCandidateInstants(metaClient, currentInstant.get(), Option.empty());
    Assertions.assertEquals(0, candidateInstants.count());
  }

  @ParameterizedTest
  @MethodSource("additionalProps")
  public void testConcurrentWrites(Properties props) throws Exception {
    String newInstantTime = HoodieTestTable.makeNewCommitTime();
    createCommit(newInstantTime, metaClient);
    // writer 1
    createInflightCommit(HoodieTestTable.makeNewCommitTime(), metaClient);
    // writer 2
    createInflightCommit(HoodieTestTable.makeNewCommitTime(), metaClient);
    newInstantTime = HoodieTestTable.makeNewCommitTime();
    Option<HoodieInstant> currentInstant = Option.of(new HoodieInstant(State.INFLIGHT, HoodieTimeline.COMMIT_ACTION, newInstantTime));
    ConflictResolutionStrategy strategy = TestConflictResolutionStrategyUtil.getConflictResolutionStrategy(metaClient, props);
    metaClient.reloadActiveTimeline();
    Stream<HoodieInstant> candidateInstants = strategy.getCandidateInstants(metaClient, currentInstant.get(), Option.empty());
    Assertions.assertEquals(0, candidateInstants.count());
  }

  @ParameterizedTest
  @MethodSource("additionalProps")
  public void testConcurrentWritesWithInterleavingSuccessfulCommit(Properties props) throws Exception {
    createCommit(HoodieActiveTimeline.createNewInstantTime(), metaClient);
    // writer 1 starts
    String currentWriterInstant = HoodieActiveTimeline.createNewInstantTime();
    createInflightCommit(currentWriterInstant, metaClient);
    // writer 2 starts and finishes
    String newInstantTime = HoodieActiveTimeline.createNewInstantTime();
    createCommit(newInstantTime, metaClient);

    Option<HoodieInstant> currentInstant = Option.of(new HoodieInstant(State.INFLIGHT, HoodieTimeline.COMMIT_ACTION, currentWriterInstant));
    ConflictResolutionStrategy strategy = TestConflictResolutionStrategyUtil.getConflictResolutionStrategy(metaClient, props);
    HoodieCommitMetadata currentMetadata = createCommitMetadata(currentWriterInstant);
    metaClient.reloadActiveTimeline();
    List<HoodieInstant> candidateInstants = strategy.getCandidateInstants(metaClient, currentInstant.get(), Option.empty()).collect(
        Collectors.toList());
    // writer 1 conflicts with writer 2
    Assertions.assertEquals(1, candidateInstants.size());
    ConcurrentOperation thatCommitOperation = new ConcurrentOperation(candidateInstants.get(0), metaClient);
    ConcurrentOperation thisCommitOperation = new ConcurrentOperation(currentInstant.get(), currentMetadata);
    Assertions.assertTrue(strategy.hasConflict(thisCommitOperation, thatCommitOperation));
    try {
      strategy.resolveConflict(thisCommitOperation, thatCommitOperation);
      Assertions.fail("Cannot reach here, writer 1 and writer 2 should have thrown a conflict");
    } catch (HoodieWriteConflictException e) {
      // expected
    }
  }

  @ParameterizedTest
  @MethodSource("additionalProps")
  public void testConcurrentWritesWithReplaceInflightCommit(Properties props) throws Exception {
    createReplaceInflight(HoodieActiveTimeline.createNewInstantTime(), metaClient);
    // writer 1 starts
    String currentWriterInstant = HoodieActiveTimeline.createNewInstantTime();
    createInflightCommit(currentWriterInstant, metaClient);
    Option<HoodieInstant> currentInstant = Option.of(new HoodieInstant(State.INFLIGHT, HoodieTimeline.COMMIT_ACTION, currentWriterInstant));

    // writer 2 starts and finishes
    String newInstantTime = HoodieActiveTimeline.createNewInstantTime();
    createReplaceInflight(newInstantTime, metaClient);

    ConflictResolutionStrategy strategy = TestConflictResolutionStrategyUtil.getConflictResolutionStrategy(metaClient, props);
    HoodieCommitMetadata currentMetadata = createCommitMetadata(currentWriterInstant);
    metaClient.reloadActiveTimeline();
    List<HoodieInstant> candidateInstants = strategy.getCandidateInstants(metaClient, currentInstant.get(), Option.empty()).collect(
        Collectors.toList());

    // writer 1 conflicts with writer 2
    Assertions.assertEquals(1, candidateInstants.size());
    ConcurrentOperation thatCommitOperation = new ConcurrentOperation(candidateInstants.get(0), metaClient);
    ConcurrentOperation thisCommitOperation = new ConcurrentOperation(currentInstant.get(), currentMetadata);
    Assertions.assertTrue(strategy.hasConflict(thisCommitOperation, thatCommitOperation));
    try {
      strategy.resolveConflict(thisCommitOperation, thatCommitOperation);
      Assertions.fail("Cannot reach here, writer 1 and writer 2 should have thrown a conflict");
    } catch (HoodieWriteConflictException e) {
      // expected
    }
  }

  @ParameterizedTest
  @MethodSource("additionalProps")
  public void testConcurrentWritesWithInterleavingScheduledCompaction(Properties props) throws Exception {
    createCommit(HoodieActiveTimeline.createNewInstantTime(), metaClient);
    // writer 1 starts
    String currentWriterInstant = HoodieActiveTimeline.createNewInstantTime();
    createInflightCommit(currentWriterInstant, metaClient);
    // compaction 1 gets scheduled
    String newInstantTime = HoodieActiveTimeline.createNewInstantTime();
    createCompactionRequested(newInstantTime, metaClient);

    Option<HoodieInstant> currentInstant = Option.of(new HoodieInstant(State.INFLIGHT, HoodieTimeline.COMMIT_ACTION, currentWriterInstant));
    ConflictResolutionStrategy strategy = TestConflictResolutionStrategyUtil.getConflictResolutionStrategy(metaClient, props);
    HoodieCommitMetadata currentMetadata = createCommitMetadata(currentWriterInstant);
    metaClient.reloadActiveTimeline();
    List<HoodieInstant> candidateInstants = strategy.getCandidateInstants(metaClient, currentInstant.get(), Option.empty()).collect(
        Collectors.toList());
    // writer 1 conflicts with scheduled compaction plan 1
    Assertions.assertEquals(1, candidateInstants.size());
    ConcurrentOperation thatCommitOperation = new ConcurrentOperation(candidateInstants.get(0), metaClient);
    ConcurrentOperation thisCommitOperation = new ConcurrentOperation(currentInstant.get(), currentMetadata);
    Assertions.assertTrue(strategy.hasConflict(thisCommitOperation, thatCommitOperation));
    try {
      strategy.resolveConflict(thisCommitOperation, thatCommitOperation);
      Assertions.fail("Cannot reach here, should have thrown a conflict");
    } catch (HoodieWriteConflictException e) {
      // expected
    }
  }

  @ParameterizedTest
  @MethodSource("additionalProps")
  public void testConcurrentWritesWithInterleavingSuccessfulCompaction(Properties props) throws Exception {
    createCommit(HoodieActiveTimeline.createNewInstantTime(), metaClient);
    // writer 1 starts
    String currentWriterInstant = HoodieActiveTimeline.createNewInstantTime();
    createInflightCommit(currentWriterInstant, metaClient);
    // compaction 1 gets scheduled and finishes
    String newInstantTime = HoodieActiveTimeline.createNewInstantTime();
    createCompaction(newInstantTime, metaClient);

    Option<HoodieInstant> currentInstant = Option.of(new HoodieInstant(State.INFLIGHT, HoodieTimeline.COMMIT_ACTION, currentWriterInstant));
    ConflictResolutionStrategy strategy = TestConflictResolutionStrategyUtil.getConflictResolutionStrategy(metaClient, props);
    HoodieCommitMetadata currentMetadata = createCommitMetadata(currentWriterInstant);
    metaClient.reloadActiveTimeline();
    List<HoodieInstant> candidateInstants = strategy.getCandidateInstants(metaClient, currentInstant.get(), Option.empty()).collect(
        Collectors.toList());
    // writer 1 conflicts with compaction 1
    Assertions.assertEquals(1, candidateInstants.size());
    ConcurrentOperation thatCommitOperation = new ConcurrentOperation(candidateInstants.get(0), metaClient);
    ConcurrentOperation thisCommitOperation = new ConcurrentOperation(currentInstant.get(), currentMetadata);
    Assertions.assertTrue(strategy.hasConflict(thisCommitOperation, thatCommitOperation));
    try {
      strategy.resolveConflict(thisCommitOperation, thatCommitOperation);
      Assertions.fail("Cannot reach here, should have thrown a conflict");
    } catch (HoodieWriteConflictException e) {
      // expected
    }
  }

  /**
   * This method is verifying if a conflict exists for already commit compaction commit with current running ingestion commit.
   */
  @ParameterizedTest
  @MethodSource("additionalProps")
  public void testConcurrentWriteAndCompactionScheduledEarlier(Properties props) throws Exception {
    createCommit(HoodieActiveTimeline.createNewInstantTime(), metaClient);
    // compaction 1 gets scheduled
    String newInstantTime = HoodieActiveTimeline.createNewInstantTime();
    createCompaction(newInstantTime, metaClient);
    // writer 1 starts
    String currentWriterInstant = HoodieActiveTimeline.createNewInstantTime();
    createInflightCommit(currentWriterInstant, metaClient);

    Option<HoodieInstant> currentInstant = Option.of(new HoodieInstant(State.INFLIGHT, HoodieTimeline.COMMIT_ACTION, currentWriterInstant));
    ConflictResolutionStrategy strategy = TestConflictResolutionStrategyUtil.getConflictResolutionStrategy(metaClient, props);
    metaClient.reloadActiveTimeline();
    List<HoodieInstant> candidateInstants = strategy.getCandidateInstants(metaClient, currentInstant.get(), Option.empty()).collect(
        Collectors.toList());
    // writer 1 should not conflict with an earlier scheduled compaction 1 with the same file ids
    Assertions.assertEquals(0, candidateInstants.size());
  }

  @ParameterizedTest
  @MethodSource("additionalProps")
  public void testConcurrentWritesWithInterleavingScheduledCluster(Properties props) throws Exception {
    createCommit(HoodieActiveTimeline.createNewInstantTime(), metaClient);
    // writer 1 starts
    String currentWriterInstant = HoodieActiveTimeline.createNewInstantTime();
    createInflightCommit(currentWriterInstant, metaClient);
    // clustering 1 gets scheduled
    String newInstantTime = HoodieActiveTimeline.createNewInstantTime();
    createReplaceRequested(newInstantTime, metaClient);

    Option<HoodieInstant> currentInstant = Option.of(new HoodieInstant(State.INFLIGHT, HoodieTimeline.COMMIT_ACTION, currentWriterInstant));
    ConflictResolutionStrategy strategy = TestConflictResolutionStrategyUtil.getConflictResolutionStrategy(metaClient, props);
    HoodieCommitMetadata currentMetadata = createCommitMetadata(currentWriterInstant);
    metaClient.reloadActiveTimeline();
    List<HoodieInstant> candidateInstants = strategy.getCandidateInstants(metaClient, currentInstant.get(), Option.empty()).collect(
        Collectors.toList());
    // writer 1 conflicts with scheduled compaction plan 1
    Assertions.assertEquals(1, candidateInstants.size());
    ConcurrentOperation thatCommitOperation = new ConcurrentOperation(candidateInstants.get(0), metaClient);
    ConcurrentOperation thisCommitOperation = new ConcurrentOperation(currentInstant.get(), currentMetadata);
    Assertions.assertTrue(strategy.hasConflict(thisCommitOperation, thatCommitOperation));
    try {
      strategy.resolveConflict(thisCommitOperation, thatCommitOperation);
      Assertions.fail("Cannot reach here, should have thrown a conflict");
    } catch (HoodieWriteConflictException e) {
      // expected
    }
  }

  @ParameterizedTest
  @MethodSource("additionalProps")
  public void testConcurrentWritesWithInterleavingSuccessfulCluster(Properties props) throws Exception {
    createCommit(HoodieActiveTimeline.createNewInstantTime(), metaClient);
    // writer 1 starts
    String currentWriterInstant = HoodieActiveTimeline.createNewInstantTime();
    createInflightCommit(currentWriterInstant, metaClient);
    // cluster 1 gets scheduled and finishes
    String newInstantTime = HoodieActiveTimeline.createNewInstantTime();
    createReplace(newInstantTime, WriteOperationType.CLUSTER, metaClient);

    Option<HoodieInstant> currentInstant = Option.of(new HoodieInstant(State.INFLIGHT, HoodieTimeline.COMMIT_ACTION, currentWriterInstant));
    ConflictResolutionStrategy strategy = TestConflictResolutionStrategyUtil.getConflictResolutionStrategy(metaClient, props);
    HoodieCommitMetadata currentMetadata = createCommitMetadata(currentWriterInstant);
    metaClient.reloadActiveTimeline();
    List<HoodieInstant> candidateInstants = strategy.getCandidateInstants(metaClient, currentInstant.get(), Option.empty()).collect(
        Collectors.toList());
    // writer 1 conflicts with cluster 1
    Assertions.assertEquals(1, candidateInstants.size());
    ConcurrentOperation thatCommitOperation = new ConcurrentOperation(candidateInstants.get(0), metaClient);
    ConcurrentOperation thisCommitOperation = new ConcurrentOperation(currentInstant.get(), currentMetadata);
    Assertions.assertTrue(strategy.hasConflict(thisCommitOperation, thatCommitOperation));
    try {
      strategy.resolveConflict(thisCommitOperation, thatCommitOperation);
      Assertions.fail("Cannot reach here, should have thrown a conflict");
    } catch (HoodieWriteConflictException e) {
      // expected
    }
  }

  @ParameterizedTest
  @MethodSource("additionalProps")
  public void testConcurrentWritesWithInterleavingSuccessfulReplace(Properties props) throws Exception {
    createCommit(HoodieActiveTimeline.createNewInstantTime(), metaClient);
    // writer 1 starts
    String currentWriterInstant = HoodieActiveTimeline.createNewInstantTime();
    createInflightCommit(currentWriterInstant, metaClient);
    // replace 1 gets scheduled and finished
    String newInstantTime = HoodieActiveTimeline.createNewInstantTime();
    createReplace(newInstantTime, WriteOperationType.INSERT_OVERWRITE, metaClient);

    Option<HoodieInstant> currentInstant = Option.of(new HoodieInstant(State.INFLIGHT, HoodieTimeline.COMMIT_ACTION, currentWriterInstant));
    ConflictResolutionStrategy strategy = TestConflictResolutionStrategyUtil.getConflictResolutionStrategy(metaClient, props);
    HoodieCommitMetadata currentMetadata = createCommitMetadata(currentWriterInstant);
    metaClient.reloadActiveTimeline();
    List<HoodieInstant> candidateInstants = strategy.getCandidateInstants(metaClient, currentInstant.get(), Option.empty()).collect(
        Collectors.toList());
    // writer 1 conflicts with replace 1
    Assertions.assertEquals(1, candidateInstants.size());
    ConcurrentOperation thatCommitOperation = new ConcurrentOperation(candidateInstants.get(0), metaClient);
    ConcurrentOperation thisCommitOperation = new ConcurrentOperation(currentInstant.get(), currentMetadata);
    Assertions.assertTrue(strategy.hasConflict(thisCommitOperation, thatCommitOperation));
    try {
      strategy.resolveConflict(thisCommitOperation, thatCommitOperation);
      Assertions.fail("Cannot reach here, should have thrown a conflict");
    } catch (HoodieWriteConflictException e) {
      // expected
    }
  }

  // try to simulate HUDI-3355
  @ParameterizedTest
  @MethodSource("additionalProps")
  public void testConcurrentWritesWithPendingInstants(Properties props) throws Exception {
    // step1: create a pending replace/compact/commit instant: C1,C11,C12
    String newInstantTimeC1 = HoodieActiveTimeline.createNewInstantTime();
    createPendingReplace(newInstantTimeC1, WriteOperationType.CLUSTER, metaClient);

    String newCompactionInstantTimeC11 = HoodieActiveTimeline.createNewInstantTime();
    createPendingCompaction(newCompactionInstantTimeC11, metaClient);

    String newCommitInstantTimeC12 = HoodieActiveTimeline.createNewInstantTime();
    createInflightCommit(newCommitInstantTimeC12, metaClient);
    // step2: create a complete commit which has no conflict with C1,C11,C12, named it as C2
    createCommit(HoodieActiveTimeline.createNewInstantTime(), metaClient);
    HoodieActiveTimeline timeline = metaClient.getActiveTimeline();
    // step3: write 1 starts, which has conflict with C1,C11,C12, named it as C3
    String currentWriterInstant = HoodieActiveTimeline.createNewInstantTime();
    createInflightCommit(currentWriterInstant, metaClient);
    // step4: create a requested commit, which has conflict with C3, named it as C4
    String commitC4 = HoodieActiveTimeline.createNewInstantTime();
    createRequestedCommit(commitC4, metaClient);
    // get PendingCommit during write 1 operation
    metaClient.reloadActiveTimeline();
    Set<String> pendingInstant = TransactionUtils.getInflightAndRequestedInstantsWithoutCurrent(metaClient, currentWriterInstant);
    // step5: finished pending cluster/compaction/commit operation
    createCompleteReplace(newInstantTimeC1, WriteOperationType.CLUSTER, metaClient);
    createCompleteCompaction(newCompactionInstantTimeC11, metaClient);
    createCompleteCommit(newCommitInstantTimeC12, metaClient);
    createCompleteCommit(commitC4, metaClient);

    // step6: do check
    Option<HoodieInstant> currentInstant = Option.of(new HoodieInstant(State.INFLIGHT, HoodieTimeline.COMMIT_ACTION, currentWriterInstant));
    ConflictResolutionStrategy strategy = TestConflictResolutionStrategyUtil.getConflictResolutionStrategy(metaClient, props);
    // make sure c3 has conflict with C1,C11,C12,C4;
    HoodieCommitMetadata currentMetadata = createCommitMetadata(currentWriterInstant, "file-2");
    metaClient.reloadActiveTimeline();
    timeline.reload();
    List<HoodieInstant> completedInstantsDuringCurrentWriteOperation = TransactionUtils
        .getCompletedInstantsDuringCurrentWriteOperation(metaClient, pendingInstant).collect(Collectors.toList());
    // C1,C11,C12,C4 should be included
    Assertions.assertEquals(4, completedInstantsDuringCurrentWriteOperation.size());

    ConcurrentOperation thisCommitOperation = new ConcurrentOperation(currentInstant.get(), currentMetadata);
    // check C3 has conflict with C1,C11,C12,C4
    for (HoodieInstant instant : completedInstantsDuringCurrentWriteOperation) {
      ConcurrentOperation thatCommitOperation = new ConcurrentOperation(instant, metaClient);
      Assertions.assertTrue(strategy.hasConflict(thisCommitOperation, thatCommitOperation));
      try {
        strategy.resolveConflict(thisCommitOperation, thatCommitOperation);
        // C11 is COMPACTION, and C3 is created after C11, so the resolve can pass
        if (!instant.getTimestamp().equals(newCompactionInstantTimeC11)) {
          Assertions.fail("Cannot reach here, should have thrown a conflict");
        }
      } catch (HoodieWriteConflictException e) {
        // expected
      }
    }
  }
}
