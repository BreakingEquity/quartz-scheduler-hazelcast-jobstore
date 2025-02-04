package com.breakingequity.quarz.store.hazelcast;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobPersistenceException;
import org.quartz.spi.JobStore;
import org.quartz.spi.OperableTrigger;

import java.util.Date;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.quartz.Trigger.MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY;

/**
 * Tests running {@link HazelcastJobStore} on two different nodes. If one node crashes during
 * {@link JobStore#acquireNextTriggers(long, int, long)}, the second node should still be able
 * to get the triggers.
 * <p>
 * This test is unstable in a way that it might succeed when it should in fact fail. However, it
 * will never fail when it should succeed.
 * </p>
 * <p>
 * The reason it is unstable is because we need to time our termination of Hazelcast so it is done
 * right in the middle of {@link JobStore#acquireNextTriggers(long, int, long)}, and that doesn't
 * always happen.
 * </p>
 *
 * @author Anton Johansson
 */
public class HazelcastJobStoreUngracefulShutdownTest extends AbstractTest {

    @AfterAll
    public static void tearDown() {
        Hazelcast.shutdownAll();
    }

    @Test
    public void testOneOfTwoInstancesCrashing()
            throws Exception {

        // Build node 1
        HazelcastInstance hazelcast1 = createHazelcastInstance("testOneOfTwoInstancesCrashing");
        HazelcastJobStore jobstore1 = createJobStore(hazelcast1, "jobstore1");
        jobstore1.initialize(null, new SampleSignaler());
        jobstore1.schedulerStarted();

        // Build node 2
        HazelcastInstance hazelcast2 = createHazelcastInstance("testOneOfTwoInstancesCrashing");
        HazelcastJobStore jobstore2 = createJobStore(hazelcast2, "jobstore1");
        jobstore2.initialize(null, new SampleSignaler());
        jobstore2.schedulerStarted();


        // awaiting for partition migration
        while (!hazelcast1.getPartitionService().isClusterSafe()
                || !hazelcast2.getPartitionService().isClusterSafe()) {
            Thread.sleep(10);
        }

        // Add a job and its trigger to the scheduler
        JobDetail job = JobBuilder.newJob(TestSlowJob.class).withIdentity("job1", "jobGroup1").build();
        OperableTrigger trigger = buildAndComputeTrigger("trigger1", "triggerGroup1", job, new Date().getTime());
        trigger.setMisfireInstruction(MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY);

        jobstore1.storeJobAndTrigger(job, trigger);

        long firstFireTime = new Date(trigger.getNextFireTime().getTime()).getTime();

        // Create a thread for acquiring next triggers on node 1
        Thread acquireThread = new Thread(() -> {
            try {
                List<OperableTrigger> triggers1 = jobstore1.acquireNextTriggers(firstFireTime + 150, 1, 0L);
                triggers1.forEach(jobstore1::releaseAcquiredTrigger);
            } catch (JobPersistenceException e) {
                throw new RuntimeException(e);
            }
        }, "acquireThread");

        // Create a thread for terminating Hazelcast on node 1
        Thread terminateThread = new Thread(hazelcast1.getLifecycleService()::terminate, "terminateThread");

        // Start acquiring next triggers and right after start terminating Hazelcast
        acquireThread.start();
        long waitTime = ThreadLocalRandom.current().nextInt(1, 51);
        Thread.sleep(waitTime);
        terminateThread.start();

        // Wait a bit
        Thread.sleep(5000);

        // Acquire next triggers on node 2, we should get our trigger here!
        List<OperableTrigger> triggers2 = jobstore2.acquireNextTriggers(firstFireTime + 150 + 6000, 10, 0L);
        System.err.println("-------------------------> VAL " + triggers2.size());
        assertEquals(1, triggers2.size(), "Should find 1 trigger on node 2 after node 1 crashed when failing after " + waitTime + "ms");
    }
}
