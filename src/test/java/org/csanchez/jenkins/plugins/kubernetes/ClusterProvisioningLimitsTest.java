package org.csanchez.jenkins.plugins.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ClusterProvisioningLimitsTest {

    private KubernetesCloud cloud;
    private KubernetesProvisioningLimits limits;

    /** Shared counts, as if kept in storage several controllers use. Other controllers' agents are seeded by tests. */
    @TestExtension
    public static final class SharedCounts implements ClusterProvisioningLimits {
        static final Map<String, Integer> counts = new HashMap<>();
        static RuntimeException failure;

        @Override
        public synchronized boolean register(
                String cloudName, int containerCap, String templateKey, int instanceCap, int numExecutors) {
            if (failure != null) {
                throw failure;
            }
            String t = cloudName + "/" + templateKey;
            if (counts.getOrDefault(cloudName, 0) + numExecutors > containerCap
                    || counts.getOrDefault(t, 0) + numExecutors > instanceCap) {
                return false;
            }
            counts.merge(cloudName, numExecutors, Integer::sum);
            counts.merge(t, numExecutors, Integer::sum);
            return true;
        }

        @Override
        public synchronized void unregister(String cloudName, String templateKey, int numExecutors) {
            if (failure != null) {
                throw failure;
            }
            counts.merge(cloudName, -numExecutors, (a, b) -> Math.max(0, a + b));
            counts.merge(cloudName + "/" + templateKey, -numExecutors, (a, b) -> Math.max(0, a + b));
        }
    }

    @BeforeEach
    void setUp(JenkinsRule j) {
        SharedCounts.counts.clear();
        SharedCounts.failure = null;
        cloud = new KubernetesCloud("k");
        cloud.setContainerCap(3);
        j.jenkins.clouds.add(cloud);
        limits = KubernetesProvisioningLimits.get();
    }

    private PodTemplate template(String name, int instanceCap) {
        PodTemplate pt = new PodTemplate();
        pt.setName(name);
        pt.setInstanceCap(instanceCap);
        cloud.addTemplate(pt);
        return pt;
    }

    @Test
    void containerCapHoldsForTheCluster() {
        PodTemplate pt = template("t", Integer.MAX_VALUE);
        SharedCounts.counts.put("k", 2);

        assertTrue(limits.register(cloud, pt, 1), "the third agent fits");
        assertFalse(limits.register(cloud, pt, 1), "a fourth exceeds the cluster-wide cap");
        assertEquals(1, limits.getGlobalCount("k"), "the refused agent is not counted here either");
        assertEquals(1, limits.getPodTemplateCount(pt.getId()));
        limits.unregister(cloud, pt, 1);
        assertEquals(2, SharedCounts.counts.get("k"));
        assertTrue(limits.register(cloud, pt, 1), "fits again once released");
    }

    @Test
    void instanceCapIsKeyedByTemplateName() {
        PodTemplate pt = template("t", 2);
        // Another controller loaded the same template, which got a different random id there.
        SharedCounts.counts.put("k", 1);
        SharedCounts.counts.put("k/t", 1);

        assertTrue(limits.register(cloud, pt, 1), "the second instance fits");
        assertFalse(limits.register(cloud, pt, 1), "a third exceeds the cluster-wide instance cap");
        assertEquals(1, limits.getPodTemplateCount(pt.getId()), "the refused agent is not counted here");
        assertEquals(1, limits.getGlobalCount("k"));

        PodTemplate other = template("u", 2);
        assertTrue(limits.register(cloud, other, 1), "another template still fits the container cap");
        assertEquals(2, limits.getGlobalCount("k"));
        assertEquals(1, limits.getPodTemplateCount(other.getId()));
        assertEquals(1, limits.getPodTemplateCount(pt.getId()), "the refusal for t did not touch its count");
    }

    @Test
    void anImplementationThatThrowsOnRegisterFailsClosed() {
        PodTemplate pt = template("t", 2);
        SharedCounts.failure = new IllegalStateException("store unreachable");

        assertThrows(IllegalStateException.class, () -> limits.register(cloud, pt, 1));
        assertEquals(0, limits.getGlobalCount("k"), "the local reservation is released");
        assertEquals(0, limits.getPodTemplateCount(pt.getId()));
    }

    @Test
    void anImplementationThatThrowsOnUnregisterStillReleasesLocally() {
        PodTemplate pt = template("t", 2);
        assertTrue(limits.register(cloud, pt, 1));
        assertEquals(1, limits.getGlobalCount("k"));

        SharedCounts.failure = new IllegalStateException("store unreachable");
        limits.unregister(cloud, pt, 1);
        assertEquals(0, limits.getGlobalCount("k"));
        assertEquals(0, limits.getPodTemplateCount(pt.getId()));
    }
}
