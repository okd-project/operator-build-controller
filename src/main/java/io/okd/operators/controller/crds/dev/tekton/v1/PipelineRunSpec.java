package io.okd.operators.controller.crds.dev.tekton.v1;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import lombok.Data;

import java.util.List;

@Data
public class PipelineRunSpec implements KubernetesResource {

    @JsonProperty("params")
    private List<PipelineRunParameter> params;

    @JsonProperty("pipelineRef")
    private PipelineRef pipelineRef;

    @JsonProperty("workspaces")
    private List<WorkspaceSpec> workspaces;

    @JsonProperty("status")
    private String status;

    @JsonProperty("timeouts")
    private TimeoutsSpec timeouts;
}
