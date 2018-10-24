/*
 * Zeebe Broker Core
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.zeebe.broker.workflow;

import static io.zeebe.broker.workflow.JobAssert.assertJobHeaders;
import static io.zeebe.broker.workflow.WorkflowAssert.assertWorkflowInstanceRecord;
import static io.zeebe.broker.workflow.gateway.ParallelGatewayStreamProcessorTest.PROCESS_ID;
import static io.zeebe.exporter.record.Assertions.assertThat;
import static io.zeebe.protocol.intent.WorkflowInstanceIntent.CANCEL;
import static io.zeebe.test.util.MsgPackUtil.asMsgPack;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.zeebe.broker.test.EmbeddedBrokerRule;
import io.zeebe.exporter.record.Record;
import io.zeebe.exporter.record.value.JobRecordValue;
import io.zeebe.exporter.record.value.WorkflowInstanceRecordValue;
import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.model.bpmn.BpmnModelInstance;
import io.zeebe.model.bpmn.builder.AbstractFlowNodeBuilder;
import io.zeebe.protocol.clientapi.RecordType;
import io.zeebe.protocol.clientapi.RejectionType;
import io.zeebe.protocol.clientapi.ValueType;
import io.zeebe.protocol.intent.JobIntent;
import io.zeebe.protocol.intent.WorkflowInstanceIntent;
import io.zeebe.test.broker.protocol.clientapi.ClientApiRule;
import io.zeebe.test.broker.protocol.clientapi.ExecuteCommandResponse;
import io.zeebe.test.broker.protocol.clientapi.PartitionTestClient;
import io.zeebe.test.util.MsgPackUtil;
import io.zeebe.test.util.record.RecordingExporter;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

public class CancelWorkflowInstanceTest {

  private static final BpmnModelInstance WORKFLOW =
      Bpmn.createExecutableProcess(PROCESS_ID)
          .startEvent()
          .serviceTask("task", t -> t.zeebeTaskType("test").zeebeTaskRetries(5))
          .endEvent()
          .done();

  private static final BpmnModelInstance SUB_PROCESS_WORKFLOW =
      Bpmn.createExecutableProcess(PROCESS_ID)
          .startEvent()
          .subProcess("subProcess")
          .embeddedSubProcess()
          .startEvent()
          .serviceTask("task", t -> t.zeebeTaskType("test").zeebeTaskRetries(5))
          .endEvent()
          .subProcessDone()
          .endEvent()
          .done();

  private static final BpmnModelInstance FORK_PROCESS;

  static {
    final AbstractFlowNodeBuilder<?, ?> builder =
        Bpmn.createExecutableProcess(PROCESS_ID)
            .startEvent("start")
            .parallelGateway("fork")
            .serviceTask("task1", b -> b.zeebeTaskType("type1"))
            .endEvent("end1")
            .moveToNode("fork");

    FORK_PROCESS =
        builder.serviceTask("task2", b -> b.zeebeTaskType("type2")).endEvent("end2").done();
  }

  public EmbeddedBrokerRule brokerRule = new EmbeddedBrokerRule();
  public ClientApiRule apiRule = new ClientApiRule(brokerRule::getClientAddress);

  @Rule public RuleChain ruleChain = RuleChain.outerRule(brokerRule).around(apiRule);

  private PartitionTestClient testClient;

  @Before
  public void init() {
    testClient = apiRule.partitionClient();
  }

  @Test
  public void shouldCancelWorkflowInstance() {
    // given
    testClient.deploy(WORKFLOW);
    final long workflowInstanceKey = testClient.createWorkflowInstance(PROCESS_ID);
    testClient.receiveElementInState("task", WorkflowInstanceIntent.ELEMENT_ACTIVATED);

    // when
    final ExecuteCommandResponse response = cancelWorkflowInstance(workflowInstanceKey);

    // then
    final Record<WorkflowInstanceRecordValue> cancelWorkflow =
        testClient.receiveFirstWorkflowInstanceCommand(WorkflowInstanceIntent.CANCEL);

    assertThat(response.getSourceRecordPosition()).isEqualTo(cancelWorkflow.getPosition());
    assertThat(response.getIntent()).isEqualTo(WorkflowInstanceIntent.CANCELING);

    final Record<WorkflowInstanceRecordValue> workflowInstanceCanceledEvent =
        testClient.receiveElementInState(PROCESS_ID, WorkflowInstanceIntent.ELEMENT_TERMINATED);

    assertThat(workflowInstanceCanceledEvent.getKey()).isEqualTo(workflowInstanceKey);
    assertWorkflowInstanceRecord(workflowInstanceKey, workflowInstanceCanceledEvent);

    final List<Record<WorkflowInstanceRecordValue>> workflowEvents =
        testClient
            .receiveWorkflowInstances()
            .skipUntil(r -> r.getMetadata().getIntent() == WorkflowInstanceIntent.CANCEL)
            .limit(6)
            .collect(Collectors.toList());

    assertThat(workflowEvents)
        .extracting(e -> e.getValue().getActivityId(), e -> e.getMetadata().getIntent())
        .containsSequence(
            tuple("", WorkflowInstanceIntent.CANCEL),
            tuple(PROCESS_ID, WorkflowInstanceIntent.CANCELING),
            tuple(PROCESS_ID, WorkflowInstanceIntent.ELEMENT_TERMINATING),
            tuple("task", WorkflowInstanceIntent.ELEMENT_TERMINATING),
            tuple("task", WorkflowInstanceIntent.ELEMENT_TERMINATED),
            tuple(PROCESS_ID, WorkflowInstanceIntent.ELEMENT_TERMINATED));
  }

  @Test
  public void shouldCancelWorkflowInstanceWithEmbeddedSubProcess() {
    // given
    testClient.deploy(SUB_PROCESS_WORKFLOW);
    final long workflowInstanceKey = testClient.createWorkflowInstance(PROCESS_ID);
    testClient.receiveElementInState("task", WorkflowInstanceIntent.ELEMENT_ACTIVATED);

    // when
    cancelWorkflowInstance(workflowInstanceKey);

    // then
    final List<Record<WorkflowInstanceRecordValue>> workflowEvents =
        testClient
            .receiveWorkflowInstances()
            .skipUntil(r -> r.getMetadata().getIntent() == WorkflowInstanceIntent.CANCEL)
            .limit(8)
            .collect(Collectors.toList());

    assertThat(workflowEvents)
        .extracting(e -> e.getValue().getActivityId(), e -> e.getMetadata().getIntent())
        .containsSequence(
            tuple("", WorkflowInstanceIntent.CANCEL),
            tuple(PROCESS_ID, WorkflowInstanceIntent.CANCELING),
            tuple(PROCESS_ID, WorkflowInstanceIntent.ELEMENT_TERMINATING),
            tuple("subProcess", WorkflowInstanceIntent.ELEMENT_TERMINATING),
            tuple("task", WorkflowInstanceIntent.ELEMENT_TERMINATING),
            tuple("task", WorkflowInstanceIntent.ELEMENT_TERMINATED),
            tuple("subProcess", WorkflowInstanceIntent.ELEMENT_TERMINATED),
            tuple(PROCESS_ID, WorkflowInstanceIntent.ELEMENT_TERMINATED));
  }

  @Test
  public void shouldCancelActivityInstance() {
    // given
    testClient.deploy(WORKFLOW);

    final long workflowInstanceKey = testClient.createWorkflowInstance(PROCESS_ID);

    final Record<WorkflowInstanceRecordValue> activityActivatedEvent =
        testClient.receiveElementInState("task", WorkflowInstanceIntent.ELEMENT_ACTIVATED);

    final ExecuteCommandResponse response = cancelWorkflowInstance(workflowInstanceKey);

    // then
    assertThat(response.getIntent()).isEqualTo(WorkflowInstanceIntent.CANCELING);

    final Record<WorkflowInstanceRecordValue> activityTerminatedEvent =
        testClient.receiveElementInState("task", WorkflowInstanceIntent.ELEMENT_TERMINATED);

    assertThat(activityTerminatedEvent.getKey()).isEqualTo(activityActivatedEvent.getKey());
    assertWorkflowInstanceRecord(workflowInstanceKey, "task", activityTerminatedEvent);
  }

  @Test
  public void shouldCancelWorkflowInstanceWithParallelExecution() {
    // given
    testClient.deploy(FORK_PROCESS);
    final long workflowInstanceKey = testClient.createWorkflowInstance(PROCESS_ID);
    testClient.receiveElementInState("task1", WorkflowInstanceIntent.ELEMENT_ACTIVATED);
    testClient.receiveElementInState("task2", WorkflowInstanceIntent.ELEMENT_ACTIVATED);

    // when
    cancelWorkflowInstance(workflowInstanceKey);

    // then
    final List<Record<WorkflowInstanceRecordValue>> workflowEvents =
        testClient
            .receiveWorkflowInstances()
            .skipUntil(r -> r.getMetadata().getIntent() == WorkflowInstanceIntent.CANCEL)
            .limit(
                r ->
                    r.getKey() == workflowInstanceKey
                        && r.getMetadata().getIntent() == WorkflowInstanceIntent.ELEMENT_TERMINATED)
            .collect(Collectors.toList());

    final List<Record<WorkflowInstanceRecordValue>> terminatedElements =
        workflowEvents
            .stream()
            .filter(r -> r.getMetadata().getIntent() == WorkflowInstanceIntent.ELEMENT_TERMINATED)
            .collect(Collectors.toList());

    assertThat(terminatedElements).hasSize(3);
    assertThat(terminatedElements.subList(0, 2))
        .extracting(r -> r.getValue().getActivityId())
        .contains("task1", "task2");

    final Record<WorkflowInstanceRecordValue> processTerminatedEvent = terminatedElements.get(2);
    assertThat(processTerminatedEvent.getValue().getActivityId()).isEqualTo(PROCESS_ID);
  }

  @Test
  public void shouldCancelIntermediateCatchEvent() {
    // given
    testClient.deploy(
        Bpmn.createExecutableProcess(PROCESS_ID)
            .startEvent()
            .intermediateCatchEvent("catch-event")
            .message(b -> b.name("msg").zeebeCorrelationKey("$.id"))
            .done());

    final long workflowInstanceKey =
        testClient.createWorkflowInstance(PROCESS_ID, asMsgPack("id", "123"));

    final Record<WorkflowInstanceRecordValue> catchEventEntered =
        testClient.receiveElementInState("catch-event", WorkflowInstanceIntent.ELEMENT_ACTIVATED);

    final ExecuteCommandResponse response = cancelWorkflowInstance(workflowInstanceKey);

    // then
    assertThat(response.getIntent()).isEqualTo(WorkflowInstanceIntent.CANCELING);

    final Record<WorkflowInstanceRecordValue> activityTerminatingEvent =
        testClient.receiveElementInState("catch-event", WorkflowInstanceIntent.ELEMENT_TERMINATING);
    final Record<WorkflowInstanceRecordValue> activityTerminatedEvent =
        testClient.receiveElementInState("catch-event", WorkflowInstanceIntent.ELEMENT_TERMINATED);

    assertThat(activityTerminatedEvent.getKey()).isEqualTo(catchEventEntered.getKey());
    assertThat(activityTerminatedEvent.getSourceRecordPosition())
        .isEqualTo(activityTerminatingEvent.getPosition());
    assertWorkflowInstanceRecord(workflowInstanceKey, "catch-event", activityTerminatedEvent);
  }

  @Test
  public void shouldCancelJobForActivity() {
    // given
    testClient.deploy(WORKFLOW);

    final long workflowInstanceKey = testClient.createWorkflowInstance(PROCESS_ID);

    final Record<JobRecordValue> jobCreatedEvent =
        testClient.receiveJobs().withIntent(JobIntent.CREATED).getFirst();

    final ExecuteCommandResponse response = cancelWorkflowInstance(workflowInstanceKey);

    // then
    assertThat(response.getIntent()).isEqualTo(WorkflowInstanceIntent.CANCELING);

    final Record<WorkflowInstanceRecordValue> terminateActivity =
        testClient.receiveElementInState("task", WorkflowInstanceIntent.ELEMENT_TERMINATING);
    final Record<JobRecordValue> jobCancelCmd = testClient.receiveFirstJobCommand(JobIntent.CANCEL);
    final Record<JobRecordValue> jobCanceledEvent =
        testClient.receiveFirstJobEvent(JobIntent.CANCELED);

    assertThat(jobCanceledEvent.getKey()).isEqualTo(jobCreatedEvent.getKey());
    assertThat(jobCancelCmd.getSourceRecordPosition()).isEqualTo(terminateActivity.getPosition());
    assertThat(jobCanceledEvent.getSourceRecordPosition()).isEqualTo(jobCancelCmd.getPosition());
    assertJobHeaders(workflowInstanceKey, jobCanceledEvent);
  }

  @Test
  public void shouldRejectCancelNonExistingWorkflowInstance() {
    // when
    final ExecuteCommandResponse response = cancelWorkflowInstance(-1L);

    // then
    assertThat(response.getRecordType()).isEqualTo(RecordType.COMMAND_REJECTION);
    assertThat(response.getRejectionType()).isEqualTo(RejectionType.NOT_APPLICABLE);
    assertThat(response.getRejectionReason()).isEqualTo("Workflow instance is not running");

    final Record<WorkflowInstanceRecordValue> cancelCommand =
        testClient.receiveFirstWorkflowInstanceCommand(CANCEL);
    final Record<WorkflowInstanceRecordValue> cancelRejection =
        testClient
            .receiveWorkflowInstances()
            .onlyCommandRejections()
            .withIntent(WorkflowInstanceIntent.CANCEL)
            .getFirst();

    assertThat(cancelRejection).isNotNull();
    assertThat(cancelRejection.getSourceRecordPosition()).isEqualTo(cancelCommand.getPosition());
  }

  @Test
  public void shouldRejectCancelCompletedWorkflowInstance() {
    // given
    testClient.deploy(Bpmn.createExecutableProcess(PROCESS_ID).startEvent().endEvent().done());

    final long workflowInstanceKey = testClient.createWorkflowInstance(PROCESS_ID);

    testClient.receiveElementInState(PROCESS_ID, WorkflowInstanceIntent.ELEMENT_COMPLETED);

    // when
    final ExecuteCommandResponse response = cancelWorkflowInstance(workflowInstanceKey);

    // then
    assertThat(response.getRecordType()).isEqualTo(RecordType.COMMAND_REJECTION);
    assertThat(response.getRejectionType()).isEqualTo(RejectionType.NOT_APPLICABLE);
    assertThat(response.getRejectionReason()).isEqualTo("Workflow instance is not running");

    final Record<WorkflowInstanceRecordValue> cancelCommand =
        testClient.receiveFirstWorkflowInstanceCommand(CANCEL);
    final Record<WorkflowInstanceRecordValue> cancelRejection =
        testClient
            .receiveWorkflowInstances()
            .onlyCommandRejections()
            .withIntent(WorkflowInstanceIntent.CANCEL)
            .getFirst();

    assertThat(cancelRejection).isNotNull();
    assertThat(cancelRejection.getSourceRecordPosition()).isEqualTo(cancelCommand.getPosition());
  }

  @Test
  public void shouldRejectCancelAlreadyCanceledWorkflowInstance() {
    // given
    testClient.deploy(WORKFLOW);

    final long workflowInstanceKey = testClient.createWorkflowInstance(PROCESS_ID);
    cancelWorkflowInstance(workflowInstanceKey);

    // when
    final ExecuteCommandResponse response = cancelWorkflowInstance(workflowInstanceKey);

    // then
    assertThat(response.getRecordType()).isEqualTo(RecordType.COMMAND_REJECTION);
    assertThat(response.getRejectionType()).isEqualTo(RejectionType.NOT_APPLICABLE);
    assertThat(response.getRejectionReason()).isEqualTo("Workflow instance is not running");
  }

  @Test
  public void shouldWriteEntireEventOnCancel() {
    // given
    testClient.deploy(WORKFLOW);
    final long workflowInstanceKey = testClient.createWorkflowInstance(PROCESS_ID);
    final io.zeebe.exporter.record.Record<WorkflowInstanceRecordValue> activatedEvent =
        RecordingExporter.workflowInstanceRecords(WorkflowInstanceIntent.ELEMENT_ACTIVATED)
            .withActivityId(PROCESS_ID)
            .getFirst();

    // when
    final ExecuteCommandResponse response = cancelWorkflowInstance(workflowInstanceKey);

    // then
    MsgPackUtil.assertEqualityExcluding(
        response.getRawValue(), activatedEvent.getValue().toJson(), "payload");

    final io.zeebe.exporter.record.Record<WorkflowInstanceRecordValue> cancelingEvent =
        RecordingExporter.workflowInstanceRecords(WorkflowInstanceIntent.CANCELING)
            .withActivityId(PROCESS_ID)
            .getFirst();

    assertThat(cancelingEvent.getValue()).isEqualTo(activatedEvent.getValue());
  }

  private ExecuteCommandResponse cancelWorkflowInstance(final long workflowInstanceKey) {
    return apiRule
        .createCmdRequest()
        .type(ValueType.WORKFLOW_INSTANCE, WorkflowInstanceIntent.CANCEL)
        .key(workflowInstanceKey)
        .command()
        .done()
        .sendAndAwait();
  }
}
