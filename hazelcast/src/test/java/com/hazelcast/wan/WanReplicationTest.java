package com.hazelcast.wan;

import com.hazelcast.config.Config;
import com.hazelcast.config.WanReplicationConfig;
import com.hazelcast.config.WanReplicationRef;
import com.hazelcast.config.WanTargetClusterConfig;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.instance.HazelcastInstanceFactory;
import com.hazelcast.map.DeleteMergePolicy;
import com.hazelcast.map.EntryBackupProcessor;
import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.listener.EntryMergedListener;
import com.hazelcast.map.merge.HigherHitsMapMergePolicy;
import com.hazelcast.map.merge.PassThroughMergePolicy;
import com.hazelcast.map.merge.PutIfAbsentMapMergePolicy;
import com.hazelcast.monitor.LocalMapStats;
import com.hazelcast.test.AssertTask;
import com.hazelcast.test.HazelcastSerialClassRunner;
import com.hazelcast.test.HazelcastTestSupport;
import com.hazelcast.test.annotation.SlowTest;
import com.hazelcast.wan.impl.WanNoDelayReplication;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;


@RunWith(HazelcastSerialClassRunner.class)
@Category(SlowTest.class)
public class WanReplicationTest extends HazelcastTestSupport {

    private int ASSERT_TRUE_EVENTUALLY_TIMEOUT_VALUE = 3 * 60;

    private HazelcastInstance[] clusterA = new HazelcastInstance[2];
    private HazelcastInstance[] clusterB = new HazelcastInstance[2];
    private HazelcastInstance[] clusterC = new HazelcastInstance[2];

    public Config configA;
    public Config configB;
    public Config configC;

    private Random random = new Random();

    @Before
    public void setup() throws Exception {
        configA = new Config();
        configA.getGroupConfig().setName("A");
        configA.getNetworkConfig().setPort(5701);

        configB = new Config();
        configB.getGroupConfig().setName("B");
        configB.getNetworkConfig().setPort(5801);

        configC = new Config();
        configC.getGroupConfig().setName("C");
        configC.getNetworkConfig().setPort(5901);
    }

    @After
    public void cleanup() {
        HazelcastInstanceFactory.shutdownAll();
    }

    // V topo config 1 passive replicar, 2 producers
    @Test
    public void VTopo_1passiveReplicar_2producers_Test_PassThroughMergePolicy() {
        setupReplicateFrom(configA, configC, clusterC.length, "atoc", PassThroughMergePolicy.class.getName());
        setupReplicateFrom(configB, configC, clusterC.length, "btoc", PassThroughMergePolicy.class.getName());
        initAllClusters();

        createDataIn(clusterA, "map", 0, 1000);
        createDataIn(clusterB, "map", 1000, 2000);

        assertDataInFrom(clusterC, "map", 0, 1000, clusterA);
        assertDataInFrom(clusterC, "map", 1000, 2000, clusterB);

        createDataIn(clusterB, "map", 0, 1);
        assertDataInFrom(clusterC, "map", 0, 1, clusterB);

        removeDataIn(clusterA, "map", 0, 500);
        removeDataIn(clusterB, "map", 1500, 2000);

        assertKeysNotIn(clusterC, "map", 0, 500);
        assertKeysNotIn(clusterC, "map", 1500, 2000);

        assertKeysIn(clusterC, "map", 500, 1500);

        removeDataIn(clusterA, "map", 500, 1000);
        removeDataIn(clusterB, "map", 1000, 1500);

        assertKeysNotIn(clusterC, "map", 0, 2000);
        assertDataSizeEventually(clusterC, "map", 0);
    }


    @Test
    public void Vtopo_TTL_Replication_Issue254() {
        setupReplicateFrom(configA, configC, clusterC.length, "atoc", PassThroughMergePolicy.class.getName());
        setupReplicateFrom(configB, configC, clusterC.length, "btoc", PassThroughMergePolicy.class.getName());

        configA.getMapConfig("default").setTimeToLiveSeconds(10);
        configB.getMapConfig("default").setTimeToLiveSeconds(10);
        configC.getMapConfig("default").setTimeToLiveSeconds(10);


        initAllClusters();

        createDataIn(clusterA, "map", 0, 10);
        assertDataInFrom(clusterC, "map", 0, 10, clusterA);

        createDataIn(clusterB, "map", 10, 20);
        assertDataInFrom(clusterC, "map", 10, 20, clusterB);

        sleepSeconds(10);
        assertKeysNotIn(clusterA, "map", 0, 10);
        assertKeysNotIn(clusterB, "map", 10, 20);
        assertKeysNotIn(clusterC, "map", 0, 20);
    }

    @Test
    public void Entry_TTL_Replication_Issue() {
        setupReplicateFrom(configA, configB, clusterB.length, "atob", PassThroughMergePolicy.class.getName());
        setupReplicateFrom(configB, configA, clusterA.length, "btoa", PassThroughMergePolicy.class.getName());

        initClusterA();
        initClusterB();

        // Create some non-TTL data to be overwritten (full invoking the merge policy),
        // half the data range from each cluster.
        createDataIn(clusterA, "map", 0, 5);
        assertDataInFrom(clusterB, "map", 0, 5, clusterA);
        createDataIn(clusterB, "map", 10, 15);
        assertDataInFrom(clusterA, "map", 10, 15, clusterB);

        createDataIn(clusterA, "map", 0, 10, 10, TimeUnit.SECONDS);
        assertDataInFrom(clusterB, "map", 0, 10, clusterA);
        createDataIn(clusterB, "map", 10, 20, 10, TimeUnit.SECONDS);
        assertDataInFrom(clusterA, "map", 10, 20, clusterB);

        // Create some TTL entries that overwrite to out-last the test.
        createDataIn(clusterA, "map", 8, 10, 1, TimeUnit.HOURS);
        assertDataInFrom(clusterB, "map", 8, 10, clusterA);
        createDataIn(clusterB, "map", 18, 20, 1, TimeUnit.HOURS);
        assertDataInFrom(clusterA, "map", 18, 20, clusterB);

        sleepSeconds(10);
        assertKeysNotIn(clusterA, "map", 0, 8);
        assertKeysIn(clusterA, "map", 8, 10);
        assertKeysNotIn(clusterA, "map", 10, 18);
        assertKeysIn(clusterA, "map", 18, 20);
        assertKeysNotIn(clusterB, "map", 0, 8);
        assertKeysIn(clusterB, "map", 8, 10);
        assertKeysNotIn(clusterB, "map", 10, 18);
        assertKeysIn(clusterB, "map", 18, 20);
    }

    @Test
    public void EntryWithDefault_TTL_Replication_Issue() {
      setupReplicateFrom(configA, configB, clusterB.length, "atob", PassThroughMergePolicy.class.getName());
      setupReplicateFrom(configB, configA, clusterA.length, "btoa", PassThroughMergePolicy.class.getName());

      configA.getMapConfig("default").setTimeToLiveSeconds(60);
      configB.getMapConfig("default").setTimeToLiveSeconds(60);

      initClusterA();
      initClusterB();

      // Make sure clusters ready
      createDataIn(clusterA, "map", 50, 60);
      assertDataInFrom(clusterB, "map", 50, 60, clusterA);

      // Create some expiring data that will live less than the default TTL
      createDataIn(clusterA, "map", 0, 10, 10, TimeUnit.SECONDS);
      assertDataInFrom(clusterB, "map", 0, 10, clusterA);
      createDataIn(clusterB, "map", 10, 20, 10, TimeUnit.SECONDS);
      assertDataInFrom(clusterA, "map", 10, 20, clusterB);

      sleepSeconds(20);
      checkKeysNotIn(clusterA, "map", 0, 20);
      checkKeysNotIn(clusterB, "map", 0, 20);
    }

    @Test
    public void VTopo_1passiveReplicar_2producers_Test_PutIfAbsentMapMergePolicy() {
        setupReplicateFrom(configA, configC, clusterC.length, "atoc", PutIfAbsentMapMergePolicy.class.getName());
        setupReplicateFrom(configB, configC, clusterC.length, "btoc", PutIfAbsentMapMergePolicy.class.getName());
        initAllClusters();

        createDataIn(clusterA, "map", 0, 1000);
        createDataIn(clusterB, "map", 1000, 2000);

        assertDataInFrom(clusterC, "map", 0, 1000, clusterA);
        assertDataInFrom(clusterC, "map", 1000, 2000, clusterB);

        createDataIn(clusterB, "map", 0, 1000);
        assertDataInFrom(clusterC, "map", 0, 1000, clusterA);

        assertDataSizeEventually(clusterC, "map", 2000);

        removeDataIn(clusterA, "map", 0, 1000);
        removeDataIn(clusterB, "map", 1000, 2000);

        assertKeysNotIn(clusterC, "map", 0, 2000);
        assertDataSizeEventually(clusterC, "map", 0);
    }

    //"Issue #1373  this test passes when run in isolation")//TODO
    @Test
    public void VTopo_1passiveReplicar_2producers_Test_HigherHitsMapMergePolicy() {
        setupReplicateFrom(configA, configC, clusterC.length, "atoc", HigherHitsMapMergePolicy.class.getName());
        setupReplicateFrom(configB, configC, clusterC.length, "btoc", HigherHitsMapMergePolicy.class.getName());
        initAllClusters();

        createDataIn(clusterA, "map", 0, 1000);
        assertDataInFrom(clusterC, "map", 0, 1000, clusterA);

        createDataIn(clusterB, "map", 0, 1000);

        assertDataInFrom(clusterC, "map", 0, 1000, clusterA);

        increaseHitCount(clusterB, "map", 0, 1000, 10);
        createDataIn(clusterB, "map", 0, 1000);

        assertDataInFrom(clusterC, "map", 0, 1000, clusterB);
    }

    //("Issue #1368 multi replicar topology cluster A replicates to B and C")
    @Test
    public void VTopo_2passiveReplicar_1producer_Test() {
        String replicaName = "multiReplica";
        setupReplicateFrom(configA, configB, clusterB.length, replicaName, PassThroughMergePolicy.class.getName());
        setupReplicateFrom(configA, configC, clusterC.length, replicaName, PassThroughMergePolicy.class.getName());
        initAllClusters();

        createDataIn(clusterA, "map", 0, 1000);

        assertKeysIn(clusterB, "map", 0, 1000);
        assertKeysIn(clusterC, "map", 0, 1000);

        removeDataIn(clusterA, "map", 0, 1000);

        assertKeysNotIn(clusterB, "map", 0, 1000);
        assertKeysNotIn(clusterC, "map", 0, 1000);

        assertDataSizeEventually(clusterB, "map", 0);
        assertDataSizeEventually(clusterC, "map", 0);
    }

    @Test
    public void linkTopo_ActiveActiveReplication_Test() {
        setupReplicateFrom(configA, configB, clusterB.length, "atob", PassThroughMergePolicy.class.getName());
        setupReplicateFrom(configB, configA, clusterA.length, "btoa", PassThroughMergePolicy.class.getName());
        initClusterA();
        initClusterB();

        createDataIn(clusterA, "map", 0, 1000);
        assertDataInFrom(clusterB, "map", 0, 1000, clusterA);

        createDataIn(clusterB, "map", 1000, 2000);
        assertDataInFrom(clusterA, "map", 1000, 2000, clusterB);

        removeDataIn(clusterA, "map", 1500, 2000);
        assertKeysNotIn(clusterB, "map", 1500, 2000);

        removeDataIn(clusterB, "map", 0, 500);
        assertKeysNotIn(clusterA, "map", 0, 500);

        assertKeysIn(clusterA, "map", 500, 1500);
        assertKeysIn(clusterB, "map", 500, 1500);

        assertDataSizeEventually(clusterA, "map", 1000);
        assertDataSizeEventually(clusterB, "map", 1000);
    }

    @Test
    public void linkTopo_ActiveActiveReplication_2clusters_Test_HigherHitsMapMergePolicy() {
        setupReplicateFrom(configA, configB, clusterB.length, "atob", HigherHitsMapMergePolicy.class.getName());
        setupReplicateFrom(configB, configA, clusterA.length, "btoa", HigherHitsMapMergePolicy.class.getName());
        initClusterA();
        initClusterB();

        createDataIn(clusterA, "map", 0, 1000);
        assertDataInFrom(clusterB, "map", 0, 1000, clusterA);

        increaseHitCount(clusterB, "map", 0, 500, 10);
        createDataIn(clusterB, "map", 0, 500);
        assertDataInFrom(clusterA, "map", 0, 500, clusterB);
    }

    @Test
    public void wan_events_should_be_processed_in_order() {
        setupReplicateFrom(configA, configB, clusterB.length, "atob", PassThroughMergePolicy.class.getName());
        initClusterA();
        initClusterB();

        createDataIn(clusterA, "map", 0, 1000);
        removeAndCreateDataIn(clusterA, "map", 0, 1000);

        assertKeysIn(clusterB, "map", 0, 1000);
        assertDataSizeEventually(clusterB, "map", 1000);
    }

    @Test
    public void putAllWanReplication() {
        setupReplicateFrom(configA, configB, clusterB.length, "atob", PassThroughMergePolicy.class.getName());
        initClusterA();
        initClusterB();

        createPutAllDataIn(clusterA, "map", 0, 1000);
        assertKeysIn(clusterB, "map", 0, 1000);
    }

    @Test
    public void multipleEntryOperationReplication() {
        setupReplicateFrom(configA, configB, clusterB.length, "atob", PassThroughMergePolicy.class.getName());
        initClusterA();
        initClusterB();

        createDataIn(clusterA, "map", 0, 1000);
        assertKeysIn(clusterB, "map", 0, 1000);

        Set<Integer> keySet = new HashSet<Integer>();
        for (int i = 0; i < 1000; i++) {
            keySet.add(i);
        }

        IMap map = getNode(clusterA).getMap("map");
        map.executeOnKeys(keySet, new MyEntryProcessor());

        assertGivenDataAppliedEventually(clusterB, "map", 0, 1000, "TEST");
    }

    static class MyEntryProcessor implements EntryProcessor, EntryBackupProcessor {

        @Override
        public Object process(Entry entry) {
            return entry.setValue("TEST");
        }

        @Override
        public EntryBackupProcessor getBackupProcessor() {
            return MyEntryProcessor.this;
        }

        @Override
        public void processBackup(Entry entry) {
            entry.setValue("TEST");
        }
    }

    @Test
    public void willFireNewOnMergeEventAtReceivingCluster() {
        setupReplicateFrom(configA, configB, clusterB.length, "atob", PassThroughMergePolicy.class.getName());

        initClusterA();
        initClusterB();

        IMap<String, String> map = clusterB[0].getMap("map");
        int entryCount = 1000;
        final CountDownLatch mergeEventFiredCounter = new CountDownLatch(entryCount);
        map.addEntryListener(new EntryMergedListener<String, String>() {
            @Override
            public void entryMerged(EntryEvent<String, String> event) {
                mergeEventFiredCounter.countDown();
            }
        }, true);

        createDataIn(clusterA, "map", 0, entryCount);
        assertKeysIn(clusterB, "map", 0, entryCount);
        assertDataSizeEventually(clusterB, "map", entryCount);
        assertOpenEventually(mergeEventFiredCounter);
    }

    @Test
    public void checkErasingMapMergePolicy() {
        setupReplicateFrom(configA, configB, clusterB.length, "atob", DeleteMergePolicy.class.getName());
        initClusterA();
        initClusterB();

        createDataIn(clusterB, "map", 0, 100);
        createDataIn(clusterA, "map", 0, 100);
        assertKeysNotIn(clusterB, "map", 0, 100);
        IMap map = clusterB[0].getMap("map");
        LocalMapStats mapStats = map.getLocalMapStats();
        assertEquals(0, mapStats.getBackupEntryCount());
    }

    private void initCluster(HazelcastInstance[] cluster, Config config) {
        for (int i = 0; i < cluster.length; i++) {
            cluster[i] = HazelcastInstanceFactory.newHazelcastInstance(config);
        }
    }

    private void initClusterA() {
        initCluster(clusterA, configA);
    }

    private void initClusterB() {
        initCluster(clusterB, configB);
    }

    private void initClusterC() {
        initCluster(clusterC, configC);
    }

    private void initAllClusters() {
        initClusterA();
        initClusterB();
        initClusterC();
    }

    private HazelcastInstance getNode(HazelcastInstance[] cluster) {
        return cluster[random.nextInt(cluster.length)];
    }

    private List getClusterEndPoints(Config config, int count) {
        List ends = new ArrayList<String>();

        int port = config.getNetworkConfig().getPort();

        for (int i = 0; i < count; i++) {
            ends.add(new String("127.0.0.1:" + port++));
        }
        return ends;
    }

    private WanTargetClusterConfig targetCluster(Config config, int count) {
        WanTargetClusterConfig target = new WanTargetClusterConfig();
        target.setGroupName(config.getGroupConfig().getName());
        target.setReplicationImpl(WanNoDelayReplication.class.getName());
        target.setEndpoints(getClusterEndPoints(config, count));
        return target;
    }

    private void setupReplicateFrom(Config fromConfig, Config toConfig, int clusterSz, String setupName, String policy) {
        WanReplicationConfig wanConfig = fromConfig.getWanReplicationConfig(setupName);
        if (wanConfig == null) {
            wanConfig = new WanReplicationConfig();
            wanConfig.setName(setupName);
        }
        wanConfig.addTargetClusterConfig(targetCluster(toConfig, clusterSz));

        WanReplicationRef wanRef = new WanReplicationRef();
        wanRef.setName(setupName);
        wanRef.setMergePolicy(policy);

        fromConfig.addWanReplicationConfig(wanConfig);
        fromConfig.getMapConfig("default").setWanReplicationRef(wanRef);
    }

    private void createDataIn(HazelcastInstance[] cluster, String mapName, int start, int end) {
        HazelcastInstance node = getNode(cluster);
        IMap m = node.getMap(mapName);
        for (; start < end; start++) {
            m.put(start, node.getConfig().getGroupConfig().getName() + start);
        }
    }

    private void createDataIn(HazelcastInstance[] cluster, String mapName, int start, int end, long ttl, TimeUnit timeUnit) {
        HazelcastInstance node = getNode(cluster);
        IMap m = node.getMap(mapName);
        for (; start < end; start++) {
            m.put(start, node.getConfig().getGroupConfig().getName() + start, ttl, timeUnit);
        }
    }

    private void createPutAllDataIn(HazelcastInstance[] cluster, String mapName, int start, int end) {
        HazelcastInstance node = getNode(cluster);
        Map<Integer, String> dataMap = new HashMap<Integer, String>();
        for (; start < end; start++) {
            dataMap.put(start, node.getConfig().getGroupConfig().getName() + start);
        }
        IMap m = node.getMap(mapName);
        m.putAll(dataMap);
    }

    private void increaseHitCount(HazelcastInstance[] cluster, String mapName, int start, int end, int repeat) {
        HazelcastInstance node = getNode(cluster);
        IMap m = node.getMap(mapName);
        for (; start < end; start++) {
            for (int i = 0; i < repeat; i++) {
                m.get(start);
            }
        }
    }

    private void removeDataIn(HazelcastInstance[] cluster, String mapName, int start, int end) {
        HazelcastInstance node = getNode(cluster);
        IMap m = node.getMap(mapName);
        for (; start < end; start++) {
            m.remove(start);
        }
    }

    private void removeAndCreateDataIn(HazelcastInstance[] cluster, String mapName, int start, int end) {
        HazelcastInstance node = getNode(cluster);
        IMap<Integer, String> m = node.getMap(mapName);
        for (; start < end; start++) {
            m.remove(start);
            m.put(start, node.getConfig().getGroupConfig().getName() + start);
        }
    }

    private boolean checkKeysIn(HazelcastInstance[] cluster, String mapName, int start, int end) {
        HazelcastInstance node = getNode(cluster);
        IMap m = node.getMap(mapName);
        for (; start < end; start++) {
            if (!m.containsKey(start)) {
                return false;
            }
        }
        return true;
    }

    private boolean checkDataInFrom(HazelcastInstance[] targetCluster, String mapName, int start, int end, HazelcastInstance[] sourceCluster) {
        HazelcastInstance node = getNode(targetCluster);

        String sourceGroupName = getNode(sourceCluster).getConfig().getGroupConfig().getName();

        IMap m = node.getMap(mapName);
        for (; start < end; start++) {
            Object v = m.get(start);
            if (v == null || !v.equals(sourceGroupName + start)) {
                return false;
            }
        }
        return true;
    }

    private boolean checkKeysNotIn(HazelcastInstance[] cluster, String mapName, int start, int end) {
        HazelcastInstance node = getNode(cluster);
        IMap m = node.getMap(mapName);
        for (; start < end; start++) {
            if (m.containsKey(start)) {
                return false;
            }
        }
        return true;
    }

    private boolean checkGivenDataApplied(HazelcastInstance[] targetCluster, String mapName, int start, int end, String value) {
        HazelcastInstance node = getNode(targetCluster);

        IMap m = node.getMap(mapName);
        for (; start < end; start++) {
            Object v = m.get(start);
            if (v == null || !v.equals(value)) {
                return false;
            }
        }
        return true;
    }

    private void assertDataSizeEventually(final HazelcastInstance[] cluster, final String mapName, final int size) {
        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                HazelcastInstance node = getNode(cluster);
                IMap m = node.getMap(mapName);
                assertEquals(size, m.size());
            }
        });
    }


    private void assertKeysIn(final HazelcastInstance[] cluster, final String mapName, final int start, final int end) {
        assertTrueEventually(new AssertTask() {
            public void run() {
                assertTrue(checkKeysIn(cluster, mapName, start, end));
            }
        }, ASSERT_TRUE_EVENTUALLY_TIMEOUT_VALUE);
    }

    private void assertDataInFrom(final HazelcastInstance[] cluster, final String mapName, final int start, final int end, final HazelcastInstance[] sourceCluster) {
        assertTrueEventually(new AssertTask() {
            public void run() {
                assertTrue(checkDataInFrom(cluster, mapName, start, end, sourceCluster));
            }
        }, ASSERT_TRUE_EVENTUALLY_TIMEOUT_VALUE);
    }

    private void assertKeysNotIn(final HazelcastInstance[] cluster, final String mapName, final int start, final int end) {
        assertTrueEventually(new AssertTask() {
            public void run() {
                assertTrue(checkKeysNotIn(cluster, mapName, start, end));
            }
        }, ASSERT_TRUE_EVENTUALLY_TIMEOUT_VALUE);
    }

    private void assertGivenDataAppliedEventually(final HazelcastInstance[] cluster, final String mapName, final int start, final int end, final String value) {
        assertTrueEventually(new AssertTask() {
            public void run() {
                assertTrue(checkGivenDataApplied(cluster, mapName, start, end, value));
            }
        }, ASSERT_TRUE_EVENTUALLY_TIMEOUT_VALUE);
    }
}
