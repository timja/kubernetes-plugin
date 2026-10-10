package org.csanchez.jenkins.plugins.kubernetes;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionPoint;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * Limits shared by several Jenkins controllers provisioning from the same clouds, such as controllers running active/active
 * against one store. When an implementation is installed, {@link KubernetesProvisioningLimits} admits a new agent only if
 * it also fits the cluster-wide counts, so that {@link KubernetesCloud#getContainerCap()} and
 * {@link PodTemplate#getInstanceCap()} hold for the whole cluster rather than for each controller.
 */
@Restricted(Beta.class)
public interface ClusterProvisioningLimits extends ExtensionPoint {

    /**
     * Atomically counts executors against a cloud's and a template's cluster-wide caps.
     *
     * @return false, counting nothing, if either cap would be exceeded
     */
    boolean register(
            @NonNull String cloudName, int containerCap, @NonNull String templateId, int instanceCap, int numExecutors);

    /**
     * Releases executors counted by {@link #register}.
     */
    void unregister(@NonNull String cloudName, @NonNull String templateId, int numExecutors);
}
