package io.okd.operators.controller.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Set;

@Data
public class ApplicationRecipe {

    @JsonProperty
    String name;

    @JsonProperty
    List<ComponentRecipe> components;

    @JsonProperty
    Set<String> versions;

    @JsonProperty
    boolean dateBasedVersioning;
}
