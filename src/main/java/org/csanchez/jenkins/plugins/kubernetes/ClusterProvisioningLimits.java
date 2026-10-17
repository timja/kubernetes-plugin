package org.csanchez.jenkins.plugins.kubernetes;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionPoint;
import hudson.Util;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * Provisioning limits shared by several Jenkins controllers that provision agents into the same Kubernetes cluster.
 * <p>
 * {@link KubernetesProvisioningLimits} counts agents per controller, so several controllers configured with the same
 * cloud can together run more agents than {@link KubernetesCloud#getContainerCap()} or
 * {@link PodTemplate#getInstanceCap()} allow. When an implementation of this extension point is installed (the first one
 * found is used), a new agent is admitted only if it fits both the local counts and the counts kept by the
 * implementation, typically in storage shared by the controllers, so the caps hold across all of them.
 * <p>
 * Counts are keyed by the cloud name and by {@link #templateKey(PodTemplate)}, the template name, which is the same on
 * every controller loading the same configuration. {@link PodTemplate#getId()} is not used, as it is generated at random
 * on each controller unless set explicitly.
 * <p>
 * The plugin calls {@link #register} after the local caps were checked and a local reservation was made, outside any
 * lock. Implementations may make a remote call, but should bound its duration: it runs within a provisioning round.
 * There is no reconciliation callback; agents that already exist when an implementation is installed are not reported to
 * it.
 */
@Restricted(Beta.class)
public interface ClusterProvisioningLimits extends ExtensionPoint {

    /**
     * Atomically counts executors against a cloud's and a template's cluster-wide caps.
     * <p>
     * If this method throws, the agent is not provisioned and the local reservation is released (the plugin fails
     * closed). Implementations that cannot reach their storage should either throw or return {@code false}.
     *
     * @param cloudName the {@linkplain KubernetesCloud#name cloud name}
     * @param containerCap the cloud's container cap
     * @param templateKey the template's {@link #templateKey(PodTemplate) key}
     * @param instanceCap the template's instance cap
     * @param numExecutors the number of executors to count
     * @return {@code false}, counting nothing, if either cap would be exceeded
     */
    boolean register(
            @NonNull String cloudName,
            int containerCap,
            @NonNull String templateKey,
            int instanceCap,
            int numExecutors);

    /**
     * Releases executors counted by {@link #register}.
     * <p>
     * This may be called more than once for the same agent (for example when a failed provisioning round is rolled back
     * and the agent is then deleted), so implementations must not let a count go below zero. An exception is logged and
     * does not prevent the local counts from being released.
     *
     * @param cloudName the {@linkplain KubernetesCloud#name cloud name}
     * @param templateKey the template's {@link #templateKey(PodTemplate) key}
     * @param numExecutors the number of executors to release
     */
    void unregister(@NonNull String cloudName, @NonNull String templateKey, int numExecutors);

    /**
     * The key under which a template's instances are counted: its name, or its id if it has no name.
     */
    @NonNull
    static String templateKey(@NonNull PodTemplate podTemplate) {
        String name = Util.fixEmptyAndTrim(podTemplate.getName());
        return name != null ? name : podTemplate.getId();
    }
}
