package org.codejive.jcp;

import eu.maveniverse.maven.mima.context.Context;
import eu.maveniverse.maven.mima.context.ContextOverrides;
import eu.maveniverse.maven.mima.context.Runtime;
import eu.maveniverse.maven.mima.context.Runtimes;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.util.artifact.JavaScopes;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class Main {

    public static void main(String... args) {
        if (args.length == 0) {
            throw new IllegalArgumentException("grp:art[:ext[:cls]]:ver [grp:art[:ext[:cls]]:ver ...]");
        }
        List<Dependency> dependencies = Arrays.stream(args)
                .map(DefaultArtifact::new)
                .map(a -> new Dependency(a, JavaScopes.RUNTIME))
                .collect(Collectors.toList());
        ContextOverrides overrides = ContextOverrides.create().build();
        Runtime runtime = Runtimes.INSTANCE.getRuntime();
        try (Context context = runtime.create(overrides)) {
            CollectRequest collectRequest = new CollectRequest()
                    .setDependencies(dependencies)
                    .setRepositories(context.remoteRepositories());
            DependencyRequest dependencyRequest = new DependencyRequest()
                    .setCollectRequest(collectRequest);

            DependencyResult dependencyResult = context
                    .repositorySystem()
                    .resolveDependencies(context.repositorySystemSession(), dependencyRequest);
            List<ArtifactResult> artifacts = dependencyResult.getArtifactResults();
            String classpath = artifacts.stream()
                    .map(ar -> ar.getArtifact().getFile().toPath().toString())
                    .collect(Collectors.joining(File.pathSeparator));
            System.out.print(classpath);
        } catch (DependencyResolutionException e) {
            throw new RuntimeException(e);
        }
    }
}
