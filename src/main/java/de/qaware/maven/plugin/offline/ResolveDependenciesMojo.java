package de.qaware.maven.plugin.offline;

import java.io.File;
import java.util.Arrays;
import org.apache.maven.RepositoryUtils;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;

/**
 * Mojo used to download all dependencies of a project or reactor to the local repository.
 * <p>
 * This includes:
 * <ul>
 * <li>Direct and transitive dependencies declared in POMs</li>
 * <li>All plugins used for the build and their transitive dependencies</li>
 * <li>Dependencies of plugins declared in POMs</li>
 * <li>DynamicDependencies configured in the go-offline-maven-plugin configuration</li>
 * </ul>
 *
 * @author Andreas Janning andreas.janning@qaware.de
 */
@Mojo(name = "resolve-dependencies", threadSafe = true, requiresOnline = true, aggregator = true)
public class ResolveDependenciesMojo extends AbstractGoOfflineMojo {

    @Component
    private DependencyDownloader dependencyDownloader;

    @Component
    private ProjectBuilder projectBuilder;

    @Parameter
    private List<DynamicDependency> dynamicDependencies;

    @Parameter(defaultValue = "false", property = "downloadSources")
    private boolean downloadSources;

    @Parameter(defaultValue = "false", property = "downloadJavadoc")
    private boolean downloadJavadoc;

    @Parameter(defaultValue = "false", property = "failOnErrors")
    private boolean failOnErrors;

    @Parameter(property = "artifactTypes")
    private List<ArtifactType> artifactTypes;

    @Parameter(property = "targetRepository")
    private File targetRepository;

    @Parameter(property = "copyPoms")
    private boolean copyPoms = false;

    public void execute() throws MojoExecutionException {
        validateConfiguration();
        dependencyDownloader.init(getBuildingRequest(), getReactorProjects(), getLog());
        if (downloadSources) {
            dependencyDownloader.enableDownloadSources();
        }
        if (downloadJavadoc) {
            dependencyDownloader.enableDownloadJavadoc();
        }

        Set<ArtifactWithRepoType> artifactsToDownload = new HashSet<>();

        if (artifactTypes == null) {
            artifactTypes = new ArrayList<>(3);
        }
        if (artifactTypes.isEmpty()) {
            artifactTypes.addAll(Arrays.asList(ArtifactType.values()));
        }
        if (artifactTypes.contains(ArtifactType.Plugin)) {
            List<Plugin> allPlugins = new ArrayList<>();
            for (MavenProject mavenProject : getReactorProjects()) {
                List<Plugin> buildPlugins = mavenProject.getBuildPlugins();
                allPlugins.addAll(buildPlugins);
            }
            for (Plugin plugin : allPlugins) {
                artifactsToDownload.addAll(dependencyDownloader.resolvePlugin(plugin));
            }
        }
        if (artifactTypes.contains(ArtifactType.Dependency)) {
            for (MavenProject project : getReactorProjects()) {
                artifactsToDownload.addAll(dependencyDownloader.resolveDependencies(project, copyPoms));
            }
            Set<ArtifactWithRepoType> parents = new HashSet<>();
            getBuildingRequest().setProcessPlugins(false);
            artifactsToDownload.forEach(a -> {
                try {
                    MavenProject project = projectBuilder
                        .build(RepositoryUtils.toArtifact(a.getArtifact()), true, getBuildingRequest())
                        .getProject();
                    while (project.hasParent()) {
                        parents.add(new ArtifactWithRepoType(RepositoryUtils.toArtifact(project.getParent().getArtifact()), RepositoryType.MAIN));
                        project = project.getParent();
                    }
                } catch (ProjectBuildingException e) {
                    getLog().warn("Could not build project from dependency " + a, e);
                }
            });
            getBuildingRequest().setProcessPlugins(true);
            artifactsToDownload.addAll(parents);
        }
        if (dynamicDependencies != null && artifactTypes.contains(ArtifactType.DynamicDependency)) {
            for (DynamicDependency dep : dynamicDependencies) {
                artifactsToDownload.addAll(dependencyDownloader.resolveDynamicDependency(dep));
            }
        }

        dependencyDownloader.downloadArtifacts(artifactsToDownload, targetRepository, copyPoms);


        List<Exception> errors = dependencyDownloader.getErrors();
        for (Exception error : errors) {
            getLog().warn(error.getMessage());
        }

        if (failOnErrors && !errors.isEmpty()) {
            throw new MojoExecutionException("Unable to download dependencies, consult the errors and warnings printed above.");
        }
    }

    private void validateConfiguration() throws MojoExecutionException {
        if (dynamicDependencies != null) {
            for (DynamicDependency dynamicDependency : dynamicDependencies) {
                dynamicDependency.validate();
            }
        }
    }

}
