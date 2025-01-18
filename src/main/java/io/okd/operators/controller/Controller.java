package io.okd.operators.controller;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.openshift.client.OpenShiftClient;
import io.okd.operators.controller.crds.dev.tekton.v1.*;
import io.okd.operators.controller.model.ApplicationRecipe;
import io.okd.operators.controller.model.ComponentRecipe;
import io.okd.operators.controller.util.DirectedAcyclicGraph;
import io.okd.operators.controller.util.GraphException;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.errors.*;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.util.FS;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Singleton
public class Controller {

    private static final Path RECIPE_REPO = Paths.get("/tmp", "okd-build-controller", "repo");
    private static final JsonMapper MAPPER = new JsonMapper();

    @Inject
    OpenShiftClient client;

    @Inject
    @ConfigProperty(name = "controller.build-repo", defaultValue = "https://github.com/okd-project/okd-operator-pipeline.git")
    String buildRepo;

    @Inject
    @ConfigProperty(name = "controller.build-branch", defaultValue = "master")
    String buildBranch;

    @Inject
    @ConfigProperty(name = "controller.recipes.directory", defaultValue = "recipes")
    String recipesDirectory;

    @Inject
    @ConfigProperty(name = "controller.recipes.patches-directory", defaultValue = "patches")
    String patchDirectory;

    @ConfigProperty(name = "controller.name", defaultValue = "okd-build-controller")
    String controllerName;

    @ConfigProperty(name = "controller.data-directory", defaultValue = "/opt/okd/recipes")
    String dataDirectory;

    @PostConstruct
    void cloneRepo() throws IOException, GitAPIException {
        Files.createDirectories(RECIPE_REPO.getParent());
        Git.cloneRepository()
                .setURI(this.buildRepo)
                .setDirectory(RECIPE_REPO.toFile())
                .setBranch(this.buildBranch)
                .call()
                .close();
    }

    @Scheduled(delayed = "10s", every = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() throws IOException, GitAPIException {

        // Get all PVCs owned by this controller
        PersistentVolumeClaimList pvcs = this.client.persistentVolumeClaims().inNamespace(client.getNamespace())
                .withLabel(Labels.OWNED_BY, controllerName).list();

        Set<String> activeRecipes = new HashSet<>();
        for (PersistentVolumeClaim item : pvcs.getItems()) {
            String recipe = item.getMetadata().getLabels().get(Labels.RECIPE);
            if (recipe != null) {
                activeRecipes.add(recipe);
            }
        }

        // Pull in repo with configuration
        try (Git git = Git.open(RECIPE_REPO.toFile())) {
            git.pull().call();

            Path path = git.getRepository().getWorkTree().toPath();
            Path recipesDirectory = path.resolve(this.recipesDirectory);

            // Read all YAMLs from the recipes directory
            Map<String, ApplicationRecipe> recipes = new HashMap<>();

            try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(recipesDirectory, "*.yaml")) {
                directoryStream.forEach(file -> {
                    log.info("Found recipe: {}", file.getFileName());
                    ApplicationRecipe recipe = loadRecipe(file);
                    if (recipe != null) {
                        recipes.put(getRecipeName(file), recipe);
                    }
                });
            } catch (IOException e) {
                log.info("Failed to read recipes directory", e);
            }

            activeRecipes.removeAll(recipes.keySet());
            for (String toRemove : activeRecipes) {
                onDelete(toRemove);
            }

            recipes.forEach((id, applicationRecipe) -> processRecipe(git, id, applicationRecipe));
        }
    }

    private void onDelete(String name) {
        // Look for Kubernetes resources owned by this recipe
        log.info("Recipe {} was deleted", name);
        // Delete the resources
        client.configMaps().inNamespace(client.getNamespace())
                .withLabel(Labels.OWNED_BY, controllerName)
                .withLabel(Labels.RECIPE, name)
                .delete();

        client.persistentVolumeClaims().inNamespace(client.getNamespace())
                .withLabel(Labels.OWNED_BY, controllerName)
                .withLabel(Labels.RECIPE, name)
                .delete();
    }

    private void processRecipe(Git git, String id, ApplicationRecipe recipe) {
        // Get resources owned by this recipe

        ConfigMapList configMaps = client.configMaps().inNamespace(client.getNamespace())
                .withLabel(Labels.OWNED_BY, controllerName)
                .withLabel(Labels.RECIPE, id)
                .list();


        PersistentVolumeClaimList pvcs = client.persistentVolumeClaims().inNamespace(client.getNamespace())
                .withLabel(Labels.OWNED_BY, controllerName)
                .withLabel(Labels.RECIPE, id)
                .list();

        // Find all components we need to create.
        Map<String, ComponentRecipe> components = new HashMap<>();
        for (ComponentRecipe component : recipe.getComponents()) {
            components.put(component.getName(), component);
        }
        if (components.size() != recipe.getComponents().size()) {
            log.error("Recipe {} has duplicate components. Bailing out", id);
            return;
        }

        deleteOrphaned(recipe.getVersions(), components.keySet(), configMaps.getItems());
        deleteOrphaned(recipe.getVersions(), components.keySet(), pvcs.getItems());

        String versionDate = getFormattedDateNow();
        for (String version : recipe.getVersions()) {
            boolean needsToBuild = false;
            try {
                for (ComponentRecipe component : recipe.getComponents()) {
                    needsToBuild |= processComponent(git, id, version, component);
                }
            } catch (Exception e) {
                log.error("Failed to build recipe {} version {}", id, version, e);
            }

            // Build out dependency graph
            DirectedAcyclicGraph<ComponentRecipe> dependencyGraph = new DirectedAcyclicGraph<>();

            for (ComponentRecipe component : recipe.getComponents()) {
                dependencyGraph.add(component);
                for (String dependency : component.getDependencies()) {
                    ComponentRecipe dep = components.get(dependency);
                    if (dep == null) {
                        log.error("Component {} in recipe {} has a dependency on a non-existent component {}", component.getName(), id, dependency);
                        return;
                    }
                    dependencyGraph.addEdges(component, dep);
                }
            }

            Collection<ComponentRecipe> sorted;
            try {
                sorted = dependencyGraph.sort();
            } catch (GraphException e) {
                throw new IllegalStateException("Circular dependency found", e);
            }

            if (needsToBuild) {
                Map<String, String> environment = new HashMap<>();
                String buildVersion;
                if (recipe.isDateBasedVersioning()) {
                    buildVersion = version + "-" + versionDate;
                } else {
                    buildVersion = version;
                }
                for (ComponentRecipe component : sorted) {
                    buildComponent(id, version, component, buildVersion, environment);
                }
            }
        }
    }

    private boolean processComponent(Git git, String recipe, String version, ComponentRecipe component) throws Exception {
        // Create PVC
        String pvcName = String.format("%s-%s-%s", recipe, version, component.getName());
        Optional<PersistentVolumeClaim> resource = Optional.ofNullable(this.client.persistentVolumeClaims().withName(pvcName).get());
        boolean equal = resource.map(persistentVolumeClaim ->
                        persistentVolumeClaim.getSpec().equals(component.getPersistentVolumeClaim()))
                .orElse(false);

        if (!equal) {
            PersistentVolumeClaim pvc = new PersistentVolumeClaimBuilder()
                    .withNewMetadata()
                    .withName(String.format("%s-%s-%s", recipe, version, component.getName()))
                    .withNamespace(client.getNamespace())
                    .addToLabels(Labels.OWNED_BY, controllerName)
                    .addToLabels(Labels.RECIPE, recipe)
                    .addToLabels(Labels.VERSION, version)
                    .addToLabels(Labels.COMPONENT, component.getName())
                    .endMetadata()
                    .withSpec(component.getPersistentVolumeClaim())
                    .build();

            client.resource(pvc).forceConflicts().serverSideApply();
        }

        // Accumulate patch files to put into the config map
        Path path = git.getRepository().getWorkTree().toPath();
        Path patchesPath = path.resolve(this.patchDirectory).resolve(recipe).resolve(component.getName()).resolve(version);
        if (!Files.isDirectory(patchesPath)) {
            throw new IllegalStateException("Patches directory does not exist");
        }

        Map<String, String> patches = new HashMap<>();
        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(patchesPath, "*.patch")) {
            for (Path file : directoryStream) {
                patches.put(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read patches directory", e);
        }

        // Create Patch config map
        String configMapName = String.format("%s-%s-%s", recipe, version, component.getName());
        Optional<ConfigMap> configMap = Optional.ofNullable(this.client.configMaps().withName(configMapName).get());
        equal = configMap.map(cm -> Objects.equals(cm.getData(), patches)).orElse(false);

        if (!equal) {
            ConfigMap cm = new ConfigMapBuilder()
                    .withNewMetadata()
                    .withName(configMapName)
                    .withNamespace(client.getNamespace())
                    .addToLabels(Labels.OWNED_BY, controllerName)
                    .addToLabels(Labels.RECIPE, recipe)
                    .addToLabels(Labels.VERSION, version)
                    .addToLabels(Labels.COMPONENT, component.getName())
                    .endMetadata()
                    .withData(patches)
                    .build();

            client.resource(cm).forceConflicts().serverSideApply();
        }

        // Checkout the repo and branch for the component
        String branch = component.getBranchFormat().formatted(version);

        Path dataDirectory = Paths.get(this.dataDirectory, recipe, version, component.getName());

        if (Files.notExists(dataDirectory)) {
            try {
                Files.createDirectories(dataDirectory);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create data directory", e);
            }
        }

        Path gitDirectory = dataDirectory.resolve("git");

        Git componentGit = null;
        try {
            if (!RepositoryCache.FileKey.isGitRepository(gitDirectory.toFile(), FS.DETECTED)) {
                if (Files.exists(gitDirectory)) {
                    try {
                        Files.delete(gitDirectory);
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to delete git directory", e);
                    }
                }
                try {
                    componentGit = Git.cloneRepository()
                            .setURI(component.getGitUrl())
                            .setDirectory(gitDirectory.toFile())
                            .setBranch(branch)
                            .call();
                } catch (GitAPIException e) {
                    throw new IllegalStateException("Failed to clone repository", e);
                }
            } else {
                try {
                    componentGit = Git.open(gitDirectory.toFile());

                    componentGit.pull().call();
                } catch (IOException | GitAPIException e) {
                    throw new IllegalStateException("Failed to open git directory", e);
                }
            }

            // Check if we need to create a new PipelineRun by polling the git repo
            String hash = null;
            boolean runPipeline = false;
            try {
                List<Ref> call = componentGit.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call();
                log.info("Branches: {}", call.stream().map(Ref::getName).toList());
                for (Ref ref : call) {
                    if (ref.getName().equals("refs/remotes/origin/" + branch)) {
                        hash = ref.getObjectId().getName();
                        break;
                    }
                }

                if (hash == null) {
                    log.error("Branch {} not found", branch);
                    throw new IllegalStateException("Branch not found");
                }

                Path hashFile = dataDirectory.resolve("hash");

                if (Files.notExists(hashFile)) {
                    Files.writeString(hashFile, hash);
                    runPipeline = true;
                } else {
                    String existingHash = Files.readString(hashFile);
                    if (!existingHash.equals(hash)) {
                        Files.writeString(hashFile, hash);
                        runPipeline = true;
                    }
                }
            } catch (GitAPIException | IOException e) {
                throw new IllegalStateException("Failed to get branch list", e);
            }

            return runPipeline;
        } finally {
            if (componentGit != null) {
                componentGit.close();
            }
        }
    }

    private void buildComponent(String recipe, String version, ComponentRecipe component, String buildVersion,
                                Map<String, String> environment) {
        String pvcName = String.format("%s-%s-%s", recipe, version, component.getName());
        String configMapName = String.format("%s-%s-%s", recipe, version, component.getName());
        log.info("Running pipeline for component {}", component.getName());

        // Create PipelineRun
        PipelineRun pipelineRun = new PipelineRun();
        pipelineRun.setMetadata(new ObjectMetaBuilder()
                .withName(String.format("%s-%s-%s", recipe, component.getName(), buildVersion))
                .withNamespace(client.getNamespace())
                .withLabels(Map.of(
                        Labels.OWNED_BY, controllerName,
                        Labels.RECIPE, recipe,
                        Labels.VERSION, version,
                        Labels.COMPONENT, component.getName()
                ))
                .build());

        PipelineRunSpec spec = new PipelineRunSpec();
        TimeoutsSpec timeouts = new TimeoutsSpec();
        timeouts.setPipeline("1h0m0s");
        timeouts.setTasks("1h0m0s");
        timeouts.setFinally("1h0m0s");
        spec.setTimeouts(timeouts);

        // --- Workspaces ---
        List<WorkspaceSpec> workspaces = new ArrayList<>();
        WorkspaceSpec patchConfigMap = new WorkspaceSpec();
        patchConfigMap.setName("patches");
        patchConfigMap.setConfigMap(new ConfigMapVolumeSourceBuilder()
                .withName(configMapName)
                .build());
        workspaces.add(patchConfigMap);

        WorkspaceSpec cacheWorkspace = new WorkspaceSpec();
        cacheWorkspace.setName("workspace");
        cacheWorkspace.setPersistentVolumeClaim(new PersistentVolumeClaimVolumeSourceBuilder()
                .withClaimName(pvcName)
                .build());
        workspaces.add(cacheWorkspace);
        spec.setWorkspaces(workspaces);

        spec.setPipelineRef(component.getPipelineRef());

        // --- Params ---
        String branch = component.getBranchFormat().formatted(version);

        List<PipelineRunParameter> params = new ArrayList<>();
        params.add(new PipelineRunParameter("base-image-registry", "quay.io/okderators"));
        params.add(new PipelineRunParameter("repo-url", component.getGitUrl()));
        params.add(new PipelineRunParameter("version", buildVersion));
        params.add(new PipelineRunParameter("make-image", "quay.io/okderators/bundle-tools:vdev"));
        params.add(new PipelineRunParameter("repo-ref", branch));
        params.add(new PipelineRunParameter("image-name", component.getImageName()));
        params.add(new PipelineRunParameter("env-map", environment.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(" "))));

        spec.setParams(params);

        environment.put("IMAGE_" + component.getName().toUpperCase().replace("-", "_"), component.getImageName() + ":" + buildVersion);

        client.resource(pipelineRun).create();
    }

    private static String getFormattedDateNow() {
        return new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
    }

    private void deleteOrphaned(Set<String> versions, Set<String> components, List<? extends HasMetadata> metadatable) {
        Iterator<? extends HasMetadata> iterator = metadatable.iterator();
        while (iterator.hasNext()) {
            HasMetadata metadata = iterator.next();
            String version = metadata.getMetadata().getLabels().get(Labels.VERSION);
            if (!versions.contains(version)) {
                client.resource(metadata).delete();
                iterator.remove();
            }
            String component = metadata.getMetadata().getLabels().get(Labels.COMPONENT);
            if (!components.contains(component)) {
                client.resource(metadata).delete();
                iterator.remove();
            }
        }
    }

    private static ApplicationRecipe loadRecipe(Path file) {
        try (InputStream stream = Files.newInputStream(file)) {
            // Parse the YAML
            return MAPPER.readValue(stream, ApplicationRecipe.class);
        } catch (IOException e) {
            log.info("Failed to read recipe file {}", file.getFileName(), e);
            return null;
        }
    }

    private static String getRecipeName(Path file) {
        return file.getFileName().toString().substring(0, file.getFileName().toString().lastIndexOf('.'));
    }
}
