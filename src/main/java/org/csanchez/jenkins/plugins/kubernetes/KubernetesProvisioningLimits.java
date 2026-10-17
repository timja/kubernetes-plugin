package org.csanchez.jenkins.plugins.kubernetes;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Node;
import hudson.model.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.metrics.api.Metrics;
import jenkins.model.Jenkins;
import jenkins.model.NodeListener;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Implements provisioning limits for clouds and pod templates
 */
@Extension
public final class KubernetesProvisioningLimits {
    private static final Logger LOGGER = Logger.getLogger(KubernetesProvisioningLimits.class.getName());

    private final AtomicBoolean init = new AtomicBoolean();

    /**
     * Tracks current number of kubernetes agents per pod template
     */
    private final ConcurrentMap<String, Integer> podTemplateCounts = new ConcurrentHashMap<>();

    /**
     * Tracks current number of kubernetes agents per kubernetes cloud
     */
    private final ConcurrentMap<String, Integer> cloudCounts = new ConcurrentHashMap<>();

    /**
     * Initialize limits counter
     * @return whether the instance was already initialized before this call.
     */
    private boolean initInstance() {
        if (init.compareAndSet(false, true)) {
            Queue.withLock(() -> {
                synchronized (this) {
                    Jenkins.get().getNodes().stream()
                            .filter(KubernetesSlave.class::isInstance)
                            .map(KubernetesSlave.class::cast)
                            .forEach(node -> {
                                cloudCounts.put(
                                        node.getCloudName(),
                                        getGlobalCount(node.getCloudName()) + node.getNumExecutors());
                                podTemplateCounts.put(
                                        node.getTemplateId(),
                                        getPodTemplateCount(node.getTemplateId()) + node.getNumExecutors());
                            });
                }
            });

            return false;
        } else {
            return true;
        }
    }

    @CheckForNull
    private static ClusterProvisioningLimits cluster() {
        // Not ExtensionList.lookupFirst, which throws when there is no implementation.
        ExtensionList<ClusterProvisioningLimits> all = ExtensionList.lookup(ClusterProvisioningLimits.class);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * @return the singleton instance
     */
    public static KubernetesProvisioningLimits get() {
        return ExtensionList.lookupSingleton(KubernetesProvisioningLimits.class);
    }

    /**
     * Register executors.
     * <p>
     * The local container and instance caps are checked and the executors reserved under this object's lock. If a
     * {@link ClusterProvisioningLimits} implementation is installed, it is asked afterwards, outside the lock, so a slow
     * implementation does not hold up other registrations; when it refuses or throws, the local reservation is released
     * again. An exception from the implementation is rethrown, so provisioning fails closed.
     * @param cloud the kubernetes cloud the executors will be on
     * @param podTemplate the pod template used to schedule the agent
     * @param numExecutors the number of executors (pretty much always 1)
     */
    public boolean register(@NonNull KubernetesCloud cloud, @NonNull PodTemplate podTemplate, int numExecutors) {
        initInstance();
        if (!reserveLocally(cloud, podTemplate, numExecutors)) {
            return false;
        }
        ClusterProvisioningLimits cluster = cluster();
        if (cluster == null) {
            return true;
        }
        boolean admitted = false;
        try {
            admitted = cluster.register(
                    cloud.name,
                    cloud.getContainerCap(),
                    ClusterProvisioningLimits.templateKey(podTemplate),
                    podTemplate.getInstanceCap(),
                    numExecutors);
        } finally {
            if (!admitted) {
                releaseLocally(cloud, podTemplate, numExecutors);
            }
        }
        if (!admitted) {
            LOGGER.log(
                    Level.FINEST,
                    () -> cloud.name + "/" + podTemplate.getName() + " cluster-wide limit reached. Cannot add "
                            + numExecutors + " more!");
            // The implementation does not say which cap was reached, so this counts as the cloud's.
            Metrics.metricRegistry().counter(MetricNames.REACHED_GLOBAL_CAP).inc();
        }
        return admitted;
    }

    private synchronized boolean reserveLocally(
            @NonNull KubernetesCloud cloud, @NonNull PodTemplate podTemplate, int numExecutors) {
        int newGlobalCount = getGlobalCount(cloud.name) + numExecutors;
        if (newGlobalCount <= cloud.getContainerCap()) {
            int newPodTemplateCount = getPodTemplateCount(podTemplate.getId()) + numExecutors;
            if (newPodTemplateCount <= podTemplate.getInstanceCap()) {
                cloudCounts.put(cloud.name, newGlobalCount);
                LOGGER.log(
                        Level.FINEST,
                        () -> cloud.name + " global limit: " + newGlobalCount + "/" + cloud.getContainerCap());

                podTemplateCounts.put(podTemplate.getId(), newPodTemplateCount);
                LOGGER.log(
                        Level.FINEST,
                        () -> podTemplate.getName() + " template limit: " + newPodTemplateCount + "/"
                                + podTemplate.getInstanceCap());
                return true;
            } else {
                LOGGER.log(
                        Level.FINEST,
                        () -> podTemplate.getName() + " template limit reached: "
                                + getPodTemplateCount(podTemplate.getId()) + "/" + podTemplate.getInstanceCap()
                                + ". Cannot add " + numExecutors + " more!");
                Metrics.metricRegistry().counter(MetricNames.REACHED_POD_CAP).inc();
            }
        } else {
            LOGGER.log(
                    Level.FINEST,
                    () -> cloud.name + " global limit reached: " + getGlobalCount(cloud.name) + "/"
                            + cloud.getContainerCap() + ". Cannot add " + numExecutors + " more!");
            Metrics.metricRegistry().counter(MetricNames.REACHED_GLOBAL_CAP).inc();
        }
        return false;
    }

    /**
     * Unregisters executors, when an agent is terminated.
     * <p>
     * The local counts are always released, even if the {@link ClusterProvisioningLimits} implementation throws.
     * @param cloud the kubernetes cloud the executors were on
     * @param podTemplate the pod template used to schedule the agent
     * @param numExecutors the number of executors (pretty much always 1)
     */
    public void unregister(@NonNull KubernetesCloud cloud, @NonNull PodTemplate podTemplate, int numExecutors) {
        if (initInstance()) {
            releaseLocally(cloud, podTemplate, numExecutors);
        }
        ClusterProvisioningLimits cluster = cluster();
        if (cluster != null) {
            try {
                cluster.unregister(cloud.name, ClusterProvisioningLimits.templateKey(podTemplate), numExecutors);
            } catch (RuntimeException e) {
                LOGGER.log(
                        Level.WARNING,
                        e,
                        () -> "Failed to release " + numExecutors + " executors of " + cloud.name + "/"
                                + podTemplate.getName() + " from the cluster-wide limits");
            }
        }
    }

    private synchronized void releaseLocally(
            @NonNull KubernetesCloud cloud, @NonNull PodTemplate podTemplate, int numExecutors) {
        int newGlobalCount = getGlobalCount(cloud.name) - numExecutors;
        if (newGlobalCount < 0) {
            LOGGER.log(
                    Level.WARNING,
                    "Global count for " + cloud.name + " went below zero. There is likely a bug in kubernetes-plugin");
        }
        cloudCounts.put(cloud.name, Math.max(0, newGlobalCount));
        LOGGER.log(
                Level.FINEST,
                () -> cloud.name + " global limit: " + Math.max(0, newGlobalCount) + "/" + cloud.getContainerCap());

        int newPodTemplateCount = getPodTemplateCount(podTemplate.getId()) - numExecutors;
        if (newPodTemplateCount < 0) {
            LOGGER.log(
                    Level.WARNING,
                    "Pod template count for " + podTemplate.getName()
                            + " went below zero. There is likely a bug in kubernetes-plugin");
        }
        podTemplateCounts.put(podTemplate.getId(), Math.max(0, newPodTemplateCount));
        LOGGER.log(
                Level.FINEST,
                () -> podTemplate.getName() + " template limit: " + Math.max(0, newPodTemplateCount) + "/"
                        + podTemplate.getInstanceCap());
    }

    @NonNull
    @Restricted(NoExternalUse.class)
    int getGlobalCount(String cloudName) {
        return cloudCounts.getOrDefault(cloudName, 0);
    }

    @NonNull
    @Restricted(NoExternalUse.class)
    int getPodTemplateCount(String podTemplate) {
        return podTemplateCounts.getOrDefault(podTemplate, 0);
    }

    @Extension
    public static class NodeListenerImpl extends NodeListener {
        @Override
        protected void onDeleted(@NonNull Node node) {
            if (node instanceof KubernetesSlave) {
                KubernetesProvisioningLimits instance = KubernetesProvisioningLimits.get();
                KubernetesSlave kubernetesNode = (KubernetesSlave) node;
                PodTemplate template = kubernetesNode.getTemplateOrNull();
                if (template != null) {
                    instance.unregister(kubernetesNode.getKubernetesCloud(), template, node.getNumExecutors());
                }
            }
        }
    }
}
