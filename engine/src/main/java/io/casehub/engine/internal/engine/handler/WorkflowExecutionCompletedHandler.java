/*
 * Copyright 2026-Present The Case Hub Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.casehub.engine.internal.engine.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.model.Worker;
import io.casehub.api.spi.ContextDiffStrategy;
import io.casehub.engine.internal.event.CaseContextChangedEvent;
import io.casehub.engine.internal.event.EventBusAddresses;
import io.casehub.engine.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.internal.history.CaseHubEventType;
import io.casehub.engine.internal.history.EventLog;
import io.casehub.engine.internal.history.EventStreamType;
import io.casehub.engine.internal.model.CaseInstance;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WorkflowExecutionCompletedHandler {

  @Inject EventBus eventBus;
  @Inject ContextDiffStrategy contextDiffStrategy;

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Logger LOG = Logger.getLogger(WorkflowExecutionCompletedHandler.class);

  @ConsumeEvent(value = EventBusAddresses.WORKER_EXECUTION_FINISHED)
  public Uni<Void> onWorkflowExecutionCompletedHandler(WorkflowExecutionCompleted event) {
    final CaseInstance caseInstance = event.caseInstance();
    final Worker worker = event.worker();
    final Map<String, Object> rawOutput = event.output() == null ? Map.of() : event.output();
    final Instant now = Instant.now();

    return Panache.withTransaction(
            () -> {
              // Snapshot BEFORE applying output — deep copy so setAll cannot corrupt it
              JsonNode contextBefore = caseInstance.getCaseContext().snapshot().asJsonNode();
              caseInstance.getCaseContext().setAll(rawOutput);
              JsonNode contextAfter = caseInstance.getCaseContext().asJsonNode();

              JsonNode diff = contextDiffStrategy.compute(contextBefore, contextAfter);
              EventLog eventLog =
                  buildEventLog(caseInstance, worker, rawOutput, event.idempotency(), now, diff);

              return eventLog.persist().replaceWith(contextAfter);
            })
        .invoke(
            contextSnapshot ->
                eventBus.publish(
                    EventBusAddresses.CONTEXT_CHANGED,
                    new CaseContextChangedEvent(caseInstance, contextSnapshot)))
        .replaceWithVoid()
        .onFailure()
        .invoke(
            t ->
                LOG.error(
                    "Failed to handle WorkflowExecutionCompleted event for caseId: "
                        + caseInstance.getUuid(),
                    t));
  }

  private EventLog buildEventLog(
      CaseInstance caseInstance,
      Worker worker,
      Map<String, Object> output,
      String idempotency,
      Instant timestamp,
      JsonNode contextDiff) {
    final EventLog eventLog = new EventLog();
    eventLog.setCaseId(caseInstance.getUuid());
    eventLog.setWorkerId(worker.getName());
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(timestamp);
    eventLog.setEventType(CaseHubEventType.WORKER_EXECUTION_COMPLETED);
    eventLog.setPayload(OBJECT_MAPPER.valueToTree(output == null ? Map.of() : output));
    eventLog.setMetadata(buildMetadata(idempotency, contextDiff));
    return eventLog;
  }

  private JsonNode buildMetadata(String idempotency, JsonNode contextDiff) {
    ObjectNode metadata = OBJECT_MAPPER.createObjectNode();
    metadata.put("inputDataHash", idempotency);
    if (contextDiff != null) {
      metadata.set("contextChanges", contextDiff);
    }
    return metadata;
  }
}
