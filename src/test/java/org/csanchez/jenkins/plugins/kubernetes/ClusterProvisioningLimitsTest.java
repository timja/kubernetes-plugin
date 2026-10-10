package org.csanchez.jenkins.plugins.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ClusterProvisioningLimitsTest {

    /** As if another controller already ran 2 of the cloud's 3 agents. */
    @TestExtension
    public static final class OtherControllerHoldsTwo implements ClusterProvisioningLimits {
        static final AtomicInteger counted = new AtomicInteger(2);

        @Override
        public boolean register(String cloudName, int containerCap, String templateId, int instanceCap, int numExecutors) {
            synchronized (counted) {
                if (counted.get() + numExecutors > containerCap) {
                    return false;
                }
                counted.addAndGet(numExecutors);
                return true;
            }
        }

        @Override
        public void unregister(String cloudName, String templateId, int numExecutors) {
            counted.addAndGet(-numExecutors);
        }
    }

    @Test
    void theCapHoldsForTheCluster(JenkinsRule j) {
        KubernetesCloud cloud = new KubernetesCloud("k");
        cloud.setContainerCap(3);
        PodTemplate pt = new PodTemplate();
        pt.setName("t");
        cloud.addTemplate(pt);
        j.jenkins.clouds.add(cloud);
        KubernetesProvisioningLimits limits = KubernetesProvisioningLimits.get();

        assertTrue(limits.register(cloud, pt, 1), "the third agent fits");
        assertFalse(limits.register(cloud, pt, 1), "a fourth exceeds the cluster-wide cap, though this controller runs only one");
        assertEquals(1, limits.getGlobalCount("k"), "the refused agent is not counted here either");
        limits.unregister(cloud, pt, 1);
        assertEquals(2, OtherControllerHoldsTwo.counted.get());
        assertTrue(limits.register(cloud, pt, 1), "fits again once released");
    }
}
