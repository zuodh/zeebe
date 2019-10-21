/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.processor.workflow.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.zeebe.engine.util.EngineRule;
import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.model.bpmn.BpmnModelInstance;
import io.zeebe.model.bpmn.builder.CallActivityBuilder;
import io.zeebe.protocol.record.Assertions;
import io.zeebe.protocol.record.Record;
import io.zeebe.protocol.record.intent.JobIntent;
import io.zeebe.protocol.record.intent.WorkflowInstanceIntent;
import io.zeebe.protocol.record.value.BpmnElementType;
import io.zeebe.protocol.record.value.WorkflowInstanceRecordValue;
import io.zeebe.test.util.BrokerClassRuleHelper;
import io.zeebe.test.util.record.RecordingExporter;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

public class CallActivityTest {

  @ClassRule public static final EngineRule ENGINE = EngineRule.singlePartition();

  private static final String PROCESS_ID_PARENT = "wf-parent";
  private static final String PROCESS_ID_CHILD = "wf-child";

  @Rule public final BrokerClassRuleHelper helper = new BrokerClassRuleHelper();

  private String jobType;

  private static BpmnModelInstance parentWorkflow(Consumer<CallActivityBuilder> consumer) {
    final var builder =
        Bpmn.createExecutableProcess(PROCESS_ID_PARENT)
            .startEvent()
            .callActivity("call", c -> c.zeebeProcessId(PROCESS_ID_CHILD));

    consumer.accept(builder);

    return builder.endEvent().done();
  }

  @Before
  public void init() {
    jobType = helper.getJobType();

    final var parentWorkflow = parentWorkflow(CallActivityBuilder::done);

    final var childWorkflow =
        Bpmn.createExecutableProcess(PROCESS_ID_CHILD)
            .startEvent()
            .serviceTask("child-task", t -> t.zeebeTaskType(jobType))
            .endEvent()
            .done();

    ENGINE
        .deployment()
        .withXmlResource("wf-parent.bpmn", parentWorkflow)
        .withXmlResource("wf-child.bpmn", childWorkflow)
        .deploy();
  }

  @Test
  public void shouldActivateCallActivity() {
    // when
    final var workflowInstanceKey =
        ENGINE.workflowInstance().ofBpmnProcessId(PROCESS_ID_PARENT).create();

    // then
    assertThat(
            RecordingExporter.workflowInstanceRecords()
                .withWorkflowInstanceKey(workflowInstanceKey)
                .withElementId("call")
                .limit(2))
        .extracting(r -> tuple(r.getValue().getBpmnElementType(), r.getIntent()))
        .containsExactly(
            tuple(BpmnElementType.CALL_ACTIVITY, WorkflowInstanceIntent.ELEMENT_ACTIVATING),
            tuple(BpmnElementType.CALL_ACTIVITY, WorkflowInstanceIntent.ELEMENT_ACTIVATED));
  }

  @Test
  public void shouldCreateInstanceOfCalledElement() {
    // when
    final var workflowInstanceKey =
        ENGINE.workflowInstance().ofBpmnProcessId(PROCESS_ID_PARENT).create();

    // then
    assertThat(
            RecordingExporter.workflowInstanceRecords()
                .withParentWorkflowInstanceKey(workflowInstanceKey)
                .limit(4))
        .extracting(r -> tuple(r.getValue().getBpmnElementType(), r.getIntent()))
        .containsExactly(
            tuple(BpmnElementType.PROCESS, WorkflowInstanceIntent.ELEMENT_ACTIVATING),
            tuple(BpmnElementType.PROCESS, WorkflowInstanceIntent.ELEMENT_ACTIVATED),
            tuple(BpmnElementType.START_EVENT, WorkflowInstanceIntent.ELEMENT_ACTIVATING),
            tuple(BpmnElementType.START_EVENT, WorkflowInstanceIntent.ELEMENT_ACTIVATED));
  }

  @Test
  public void shouldCreateInstanceOfLatestVersionOfCalledElement() {
    // given
    final var workflowChildV2 =
        Bpmn.createExecutableProcess(PROCESS_ID_CHILD).startEvent("v2").endEvent().done();

    final var secondDeployment =
        ENGINE.deployment().withXmlResource("wf-child.bpmn", workflowChildV2).deploy();
    final var secondVersion = secondDeployment.getValue().getDeployedWorkflows().get(0);

    // when
    final var workflowInstanceKey =
        ENGINE.workflowInstance().ofBpmnProcessId(PROCESS_ID_PARENT).create();

    // then
    Assertions.assertThat(getChildInstanceOf(workflowInstanceKey))
        .hasVersion(secondVersion.getVersion())
        .hasWorkflowKey(secondVersion.getWorkflowKey());
  }

  @Test
  public void shouldHaveReferenceToParentInstance() {
    // when
    final var workflowInstanceKey =
        ENGINE.workflowInstance().ofBpmnProcessId(PROCESS_ID_PARENT).create();

    completeJobWith(Map.of());

    // then
    final var callActivityInstanceKey = getCallActivityInstanceKey(workflowInstanceKey);

    assertThat(
            RecordingExporter.records()
                .limitToWorkflowInstance(workflowInstanceKey)
                .workflowInstanceRecords()
                .withParentWorkflowInstanceKey(workflowInstanceKey))
        .extracting(Record::getValue)
        .extracting(v -> tuple(v.getParentWorkflowInstanceKey(), v.getParentElementInstanceKey()))
        .containsOnly(tuple(workflowInstanceKey, callActivityInstanceKey));
  }

  @Test
  public void shouldCompleteCallActivity() {
    // when
    final var workflowInstanceKey =
        ENGINE.workflowInstance().ofBpmnProcessId(PROCESS_ID_PARENT).create();

    completeJobWith(Map.of());

    // then
    assertThat(
            RecordingExporter.records()
                .limitToWorkflowInstance(workflowInstanceKey)
                .workflowInstanceRecords())
        .extracting(r -> tuple(r.getValue().getBpmnElementType(), r.getIntent()))
        .containsSubsequence(
            tuple(BpmnElementType.END_EVENT, WorkflowInstanceIntent.ELEMENT_COMPLETED),
            tuple(BpmnElementType.PROCESS, WorkflowInstanceIntent.ELEMENT_COMPLETED),
            tuple(BpmnElementType.CALL_ACTIVITY, WorkflowInstanceIntent.ELEMENT_COMPLETING),
            tuple(BpmnElementType.CALL_ACTIVITY, WorkflowInstanceIntent.ELEMENT_COMPLETED),
            tuple(BpmnElementType.SEQUENCE_FLOW, WorkflowInstanceIntent.SEQUENCE_FLOW_TAKEN),
            tuple(BpmnElementType.PROCESS, WorkflowInstanceIntent.ELEMENT_COMPLETED));
  }

  @Test
  public void shouldCopyVariablesToChild() {
    // when
    final var workflowInstanceKey =
        ENGINE
            .workflowInstance()
            .ofBpmnProcessId(PROCESS_ID_PARENT)
            .withVariables(
                Map.of(
                    "x", 1,
                    "y", 2))
            .create();

    // then
    final var childInstance = getChildInstanceOf(workflowInstanceKey);

    assertThat(
            RecordingExporter.variableRecords()
                .withWorkflowInstanceKey(childInstance.getWorkflowInstanceKey())
                .limit(2))
        .extracting(Record::getValue)
        .allMatch(v -> v.getWorkflowKey() == childInstance.getWorkflowKey())
        .allMatch(v -> v.getWorkflowInstanceKey() == childInstance.getWorkflowInstanceKey())
        .allMatch(v -> v.getScopeKey() == childInstance.getWorkflowInstanceKey())
        .extracting(v -> tuple(v.getName(), v.getValue()))
        .contains(tuple("x", "1"), tuple("y", "2"));
  }

  @Test
  public void shouldPropagateVariablesToParent() {
    // given
    final var workflowInstanceKey =
        ENGINE.workflowInstance().ofBpmnProcessId(PROCESS_ID_PARENT).create();

    // when
    completeJobWith(Map.of("y", 2));

    // then
    assertThat(
            RecordingExporter.records()
                .limitToWorkflowInstance(workflowInstanceKey)
                .variableRecords()
                .withWorkflowInstanceKey(workflowInstanceKey))
        .extracting(Record::getValue)
        .extracting(v -> tuple(v.getScopeKey(), v.getName(), v.getValue()))
        .containsExactly(tuple(workflowInstanceKey, "y", "2"));
  }

  @Test
  public void shouldApplyInputMappings() {
    // given
    ENGINE
        .deployment()
        .withXmlResource("wf-parent.bpmn", parentWorkflow(c -> c.zeebeInput("x", "y")))
        .deploy();

    // when
    final var workflowInstanceKey =
        ENGINE.workflowInstance().ofBpmnProcessId(PROCESS_ID_PARENT).withVariable("x", 1).create();

    // then
    final var childInstance = getChildInstanceOf(workflowInstanceKey);

    assertThat(
            RecordingExporter.variableRecords()
                .withWorkflowInstanceKey(childInstance.getWorkflowInstanceKey())
                .limit(2))
        .extracting(Record::getValue)
        .extracting(v -> tuple(v.getName(), v.getValue()))
        .contains(tuple("x", "1"), tuple("y", "1"));
  }

  @Test
  public void shouldApplyOutputMappings() {
    // given
    ENGINE
        .deployment()
        .withXmlResource("wf-parent.bpmn", parentWorkflow(c -> c.zeebeOutput("x", "y")))
        .deploy();

    final var workflowInstanceKey =
        ENGINE.workflowInstance().ofBpmnProcessId(PROCESS_ID_PARENT).create();

    // when
    completeJobWith(Map.of("x", 2));

    // then
    final long callActivityInstanceKey = getCallActivityInstanceKey(workflowInstanceKey);

    assertThat(
            RecordingExporter.records()
                .limitToWorkflowInstance(workflowInstanceKey)
                .variableRecords()
                .withWorkflowInstanceKey(workflowInstanceKey))
        .extracting(Record::getValue)
        .extracting(v -> tuple(v.getScopeKey(), v.getName(), v.getValue()))
        .hasSize(2)
        .contains(tuple(callActivityInstanceKey, "x", "2"), tuple(workflowInstanceKey, "y", "2"));
  }

  private void completeJobWith(Map<String, Object> variables) {

    RecordingExporter.jobRecords(JobIntent.CREATED).withType(jobType).getFirst().getValue();

    ENGINE
        .jobs()
        .withType(jobType)
        .activate()
        .getValue()
        .getJobKeys()
        .forEach(jobKey -> ENGINE.job().withKey(jobKey).withVariables(variables).complete());
  }

  private WorkflowInstanceRecordValue getChildInstanceOf(long workflowInstanceKey) {

    return RecordingExporter.workflowInstanceRecords()
        .withBpmnProcessId(PROCESS_ID_CHILD)
        .withParentWorkflowInstanceKey(workflowInstanceKey)
        .getFirst()
        .getValue();
  }

  private long getCallActivityInstanceKey(long workflowInstanceKey) {

    return RecordingExporter.workflowInstanceRecords(WorkflowInstanceIntent.ELEMENT_ACTIVATED)
        .withWorkflowInstanceKey(workflowInstanceKey)
        .withElementType(BpmnElementType.CALL_ACTIVITY)
        .getFirst()
        .getKey();
  }
}
