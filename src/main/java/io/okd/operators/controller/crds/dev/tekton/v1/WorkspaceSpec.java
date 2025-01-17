package io.okd.operators.controller.crds.dev.tekton.v1;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimSpec;
import io.fabric8.kubernetes.api.model.Volume;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class WorkspaceSpec extends Volume {

    @JsonProperty("name")
    private String name;

    @JsonProperty("subPath")
    private String subPath;
}
