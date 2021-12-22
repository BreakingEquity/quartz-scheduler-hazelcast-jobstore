package com.breakingequity.quarz.store.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.*;
import org.quartz.Calendar;
import org.quartz.*;
import org.quartz.impl.calendar.BaseCalendar;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.simpl.CascadingClassLoadHelper;
import org.quartz.spi.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.quartz.Scheduler.DEFAULT_GROUP;
import static org.quartz.SimpleScheduleBuilder.simpleSchedule;
import static org.quartz.TriggerBuilder.newTrigger;

public class HazelcastJobStoreTest extends AbstractTest {

    static final Logger LOG = LoggerFactory.getLogger(HazelcastJobStoreTest.class);

    private static HazelcastInstance hazelcastInstance;
    private static HazelcastJobStore jobStore;

    @BeforeAll
    public static void setUp() throws SchedulerException {

        SampleSignaler fSignaler = new SampleSignaler();

        Config config = new Config();
        config.setProperty("hazelcast.logging.type", "slf4j");
        hazelcastInstance = Hazelcast.newHazelcastInstance(config);

        ClassLoadHelper loadHelper = new CascadingClassLoadHelper();
        loadHelper.initialize();

        jobStore = createJobStore(hazelcastInstance, "AbstractJobStoreTest");
        jobStore.initialize(loadHelper, fSignaler);
        jobStore.schedulerStarted();
    }

    @AfterAll
    public static void tearDown() {

        jobStore.shutdown();
        hazelcastInstance.shutdown();
    }

    @AfterEach
    public void cleanUpAfterEachTest()
            throws JobPersistenceException {

        jobStore.clearAllSchedulingData();
        jobStore.resumeAll();

    }

    @Test
    public void testShuttingDownWithoutShuttingDownHazelcast()
            throws SchedulerException {

        HazelcastInstance hazelcastInstance = createHazelcastInstance(UUID.randomUUID().toString());

        HazelcastJobStore jobStore = createJobStore(hazelcastInstance, "test-shutting-down-hazelcast");
        jobStore.schedulerStarted();
        jobStore.shutdown();

        assertTrue(hazelcastInstance.getLifecycleService().isRunning());
    }

    @Test
    public void testAcquireNextTrigger()
            throws Exception {

        JobDetail job = JobBuilder.newJob(MyJob.class).build();
        jobStore.storeJob(job, true);


        long baseFireTime = System.currentTimeMillis();
        assertTrue(jobStore.acquireNextTriggers(baseFireTime, 1, 0L).isEmpty());

        baseFireTime = System.currentTimeMillis();
        OperableTrigger t1 = buildAndComputeTrigger("trigger1", "testAcquireNextTrigger", job, baseFireTime + 2000);
        jobStore.storeTrigger(t1, false);
        assertEquals(jobStore.acquireNextTriggers(baseFireTime + 2000, 1, 0L).get(0), t1);

        baseFireTime = System.currentTimeMillis();
        OperableTrigger t2 = buildAndComputeTrigger("trigger2", "testAcquireNextTrigger", job, baseFireTime + 500);
        jobStore.storeTrigger(t2, false);


        List<OperableTrigger> acquiredTriggers = jobStore.acquireNextTriggers(baseFireTime + 600, 1, 0L);
        assertFalse(acquiredTriggers.isEmpty());
        assertEquals(t2, acquiredTriggers.get(0));

        acquiredTriggers = jobStore.acquireNextTriggers(baseFireTime + 600, 1, 0L);
        assertTrue(acquiredTriggers.isEmpty());

        baseFireTime = System.currentTimeMillis();
        OperableTrigger t3 = buildAndComputeTrigger("trigger3", "testAcquireNextTrigger", job, baseFireTime + 1000);
        jobStore.storeTrigger(t3, false);

        acquiredTriggers = jobStore.acquireNextTriggers(baseFireTime + 5000, 1, 0L);
        assertFalse(acquiredTriggers.isEmpty());
        assertEquals(t3, acquiredTriggers.get(0));

        // release trigger3
        acquiredTriggers.forEach(jobStore::releaseAcquiredTrigger);
        assertEquals(jobStore.acquireNextTriggers(t3.getNextFireTime().getTime() + 5000, 1, 1L).get(0), t3);

        acquiredTriggers = jobStore.acquireNextTriggers(baseFireTime + 10000, 1, 0L);
        assertTrue(acquiredTriggers.isEmpty());

        jobStore.removeTrigger(t1.getKey());
        jobStore.removeTrigger(t2.getKey());
        jobStore.removeTrigger(t3.getKey());
    }

    @Test
    public void testAcquireNextTriggerAfterMissFire()
            throws Exception {
        long oldThreshold = jobStore.getMisfireThreshold();
        long baseFireTime = System.currentTimeMillis();

        JobDetail job = JobBuilder.newJob(MyJob.class).build();
        jobStore.storeJob(job, true);
        jobStore.setMisfireThreshold(500);

        OperableTrigger t1 = buildAndComputeTrigger(
                "trigger1",
                "testAcquireNextTriggerAfterMissFire",
                job,
                baseFireTime + 500,
                null,
                SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow());
        OperableTrigger t2 = buildAndComputeTrigger(
                "trigger2",
                "testAcquireNextTriggerAfterMissFire",
                job,
                baseFireTime + 500,
                null,
                SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow());

        jobStore.storeTrigger(t1, false);
        jobStore.storeTrigger(t2, false);

        List<OperableTrigger> acquired = jobStore.acquireNextTriggers(baseFireTime + 1000, 1, 0L);
        assertEquals(acquired.size(), 1);
        jobStore.triggersFired(acquired);
        Thread.sleep(800);

        long now = System.currentTimeMillis();
        //misfired is acquired immediately
        assertEquals(jobStore.acquireNextTriggers(now + 1000, 1, 0L).size(), 1);

        jobStore.removeTrigger(t1.getKey());
        jobStore.removeTrigger(t2.getKey());

        jobStore.setMisfireThreshold(oldThreshold);

    }

    @Test
    public void testAcquireNextTriggerAfterMissFire_triggersImmediately_ifNextScheduleTimeInRange()
            throws Exception {


        long baseFireTime = System.currentTimeMillis();

        JobDetail job = JobBuilder.newJob(MyJob.class).build();
        jobStore.storeJob(job, true);

        SimpleScheduleBuilder scheduleBuilder = simpleSchedule().withIntervalInSeconds(3).repeatForever()
                .withMisfireHandlingInstructionFireNow();
        OperableTrigger t1 = buildAndComputeTrigger("trigger1", "triggerGroup1", job, baseFireTime + 500, null,
                scheduleBuilder);
        jobStore.storeTrigger(t1, false);

        assertAcquiredAndRelease(baseFireTime, 1);

        Thread.sleep(5000);
        // missed one execution, next execution is immediate
        assertAcquiredAndRelease(System.currentTimeMillis() + 500, 1);

        Thread.sleep(1000);
        // next execution is at 8 seconds tick (5 + 3) outside interval (6 sec to 7 sec tick), no triggers should be acquired
        assertAcquiredAndRelease(System.currentTimeMillis() + 1050, 0);
        // increase interval to contain 8 seconds tick
        assertAcquiredAndRelease(System.currentTimeMillis() + 2550, 1);
    }

    @Test
    public void testAcquireNextTriggerAfterMissFire_doesNotTrigger_ifNextScheduleTimeOutOfRange()
            throws Exception {

        long baseFireTime = System.currentTimeMillis();

        JobDetail job = JobBuilder.newJob(MyJob.class).build();
        jobStore.storeJob(job, true);

        ScheduleBuilder<SimpleTrigger> scheduleBuilder = simpleSchedule().withIntervalInSeconds(3).repeatForever()
                .withMisfireHandlingInstructionNextWithExistingCount();
        OperableTrigger t1 = buildAndComputeTrigger("trigger1", "triggerGroup1", job, baseFireTime + 500, null,
                scheduleBuilder);
        jobStore.storeTrigger(t1, false);

        assertAcquiredAndRelease(baseFireTime, 1);

        Thread.sleep(5000);
        // missed one execution (3 seconds tick is more than 1 seconds ago), next execution (at 6 seconds tick) is not yet picked up
        assertAcquiredAndRelease(System.currentTimeMillis() + 250, 0);

        // try acquire on larger interval (containing 6 sec tick)
        assertAcquiredAndRelease(System.currentTimeMillis() + 1550, 1);

    }

    @Test
    public void testAcquireNextTriggerBatch()
            throws Exception {

        JobDetail jobDetail = JobBuilder.newJob(MyJob.class).withIdentity("job1", "jobGroup1").build();
        jobStore.storeJob(jobDetail, false);

        long baseFireTime = System.currentTimeMillis();

        jobStore.storeJob(jobDetail, true);

        OperableTrigger trigger1 = buildTrigger("trigger1",
                "testAcquireNextTriggerBatch",
                jobDetail,
                baseFireTime + 2000,
                baseFireTime + 2005);
        OperableTrigger trigger2 = buildTrigger("trigger2",
                "testAcquireNextTriggerBatch",
                jobDetail,
                baseFireTime + 2100,
                baseFireTime + 2105);
        OperableTrigger trigger3 = buildTrigger("trigger3",
                "testAcquireNextTriggerBatch",
                jobDetail,
                baseFireTime + 2200,
                baseFireTime + 2205);
        OperableTrigger trigger4 = buildTrigger("trigger4",
                "testAcquireNextTriggerBatch",
                jobDetail,
                baseFireTime + 2300,
                baseFireTime + 2305);
        OperableTrigger trigger5 = buildTrigger("trigger5",
                "testAcquireNextTriggerBatch",
                jobDetail,
                baseFireTime + 5000,
                baseFireTime + 7000);

        trigger1.computeFirstFireTime(null);
        trigger2.computeFirstFireTime(null);
        trigger3.computeFirstFireTime(null);
        trigger4.computeFirstFireTime(null);
        trigger5.computeFirstFireTime(null);

        jobStore.storeTrigger(trigger1, false);
        jobStore.storeTrigger(trigger2, false);
        jobStore.storeTrigger(trigger3, false);
        jobStore.storeTrigger(trigger4, false);
        jobStore.storeTrigger(trigger5, false);

        List<OperableTrigger> acquiredTriggers = jobStore.acquireNextTriggers(baseFireTime + 2001, 3, 1000L);
        assertEquals(3, acquiredTriggers.size());
        acquiredTriggers.forEach(jobStore::releaseAcquiredTrigger);

        acquiredTriggers = jobStore.acquireNextTriggers(baseFireTime + 2001, 4, 1000L);
        assertEquals(4, acquiredTriggers.size());
        acquiredTriggers.forEach(jobStore::releaseAcquiredTrigger);

        acquiredTriggers = jobStore.acquireNextTriggers(baseFireTime + 2001, 5, 1000L);
        assertEquals(4, acquiredTriggers.size());
        acquiredTriggers.forEach(jobStore::releaseAcquiredTrigger);

        acquiredTriggers = jobStore.acquireNextTriggers(baseFireTime + 2001, 5, 0L);
        assertEquals(1, acquiredTriggers.size());
        acquiredTriggers.forEach(jobStore::releaseAcquiredTrigger);

        acquiredTriggers = jobStore.acquireNextTriggers(baseFireTime + 2001, 5, 150L);
        assertEquals(2, acquiredTriggers.size());
        acquiredTriggers.forEach(jobStore::releaseAcquiredTrigger);

        acquiredTriggers = jobStore.acquireNextTriggers(baseFireTime + 2001, 5, 50L);
        assertEquals(1, acquiredTriggers.size());
        acquiredTriggers.forEach(jobStore::releaseAcquiredTrigger);

        jobStore.removeTrigger(trigger1.getKey());
        jobStore.removeTrigger(trigger2.getKey());
        jobStore.removeTrigger(trigger3.getKey());
        jobStore.removeTrigger(trigger4.getKey());
        jobStore.removeTrigger(trigger5.getKey());
    }

    @Test
    public void testTriggerStates()
            throws Exception {

        JobDetail newJob = JobBuilder.newJob(MyJob.class).withIdentity("job1", "testTriggerStates").build();
        jobStore.storeJob(newJob, false);

        OperableTrigger trigger = buildTrigger("trigger1",
                "testTriggerStates",
                newJob,
                System.currentTimeMillis() + 1000,
                System.currentTimeMillis() + 2000);

        trigger.computeFirstFireTime(null);

        assertEquals(jobStore.getTriggerState(trigger.getKey()), Trigger.TriggerState.NONE);

        jobStore.storeTrigger(trigger, false);
        assertEquals(jobStore.getTriggerState(trigger.getKey()), Trigger.TriggerState.NORMAL);

        jobStore.pauseTrigger(trigger.getKey());
        assertEquals(jobStore.getTriggerState(trigger.getKey()), Trigger.TriggerState.PAUSED);

        jobStore.resumeTrigger(trigger.getKey());
        assertEquals(jobStore.getTriggerState(trigger.getKey()), Trigger.TriggerState.NORMAL);

        OperableTrigger rt1 = jobStore.acquireNextTriggers(
                new Date(trigger.getNextFireTime().getTime()).getTime() + 10000,
                1,
                1L)
                .get(0);
        assertNotNull(rt1);
        jobStore.releaseAcquiredTrigger(rt1);

        OperableTrigger rt2 = jobStore.acquireNextTriggers(
                new Date(rt1.getNextFireTime().getTime()).getTime() + 1500,
                1,
                1L)
                .get(0);

        assertNotNull(rt2);
        assertEquals(rt2.getJobKey(), rt1.getJobKey());

        assertTrue(jobStore.acquireNextTriggers(new Date(rt2.getNextFireTime().getTime()).getTime() + 1500,
                1,
                1L)
                .isEmpty());
    }

    @Test
    public void testStoreTriggerReplacesTrigger()
            throws Exception {

        JobDetail job = buildJob("replacesTrigJob99", "replacesTrigJobGroup");
        jobStore.storeJob(job, false);

        OperableTrigger tr = buildTrigger("stReplacesTrigger1", "stReplacesTriggerGroup", job, new Date().getTime());
        tr.setCalendarName(null);

        jobStore.storeTrigger(tr, false);
        assertEquals(jobStore.retrieveTrigger(tr.getKey()), tr);

        try {
            jobStore.storeTrigger(tr, false);
            fail("an attempt to store duplicate trigger succeeded");
        } catch (ObjectAlreadyExistsException ex) {
            // expected
        }

        tr.setCalendarName("QQ");
        jobStore.storeTrigger(tr, true);
        assertEquals(jobStore.retrieveTrigger(tr.getKey()), tr);
        assertEquals("QQ", jobStore.retrieveTrigger(tr.getKey()).getCalendarName(), "StoreJob doesn't replace triggers");
    }

    @Test
    public void testPauseJobGroupPausesNewJob()
            throws Exception {

        final String jobGroup = "PauseJobGroupPausesNewJobGroup";

        JobDetail job1 = buildJob("PauseJobGroupPausesNewJob", jobGroup);
        jobStore.storeJob(job1, false);
        jobStore.pauseJobs(GroupMatcher.jobGroupEquals(jobGroup));

        JobDetail job2 = buildJob("PauseJobGroupPausesNewJob2", jobGroup);
        jobStore.storeJob(job2, false);

        OperableTrigger tr = buildTrigger(
                "PauseJobGroupPausesNewJobTrigger",
                "PauseJobGroupPausesNewJobTriggerGroup",
                job2,
                new Date().getTime());

        jobStore.storeTrigger(tr, false);
        assertEquals(jobStore.getTriggerState(tr.getKey()), Trigger.TriggerState.PAUSED);
    }

    @Test
    public void testStoreAndRetrieveJobs()
            throws Exception {

        final int nJobs = 10;

        SchedulerSignaler schedSignaler = new SampleSignaler();
        ClassLoadHelper loadHelper = new CascadingClassLoadHelper();
        loadHelper.initialize();

        JobStore store = createJobStore(hazelcastInstance, "testStoreAndRetrieveJobs");
        store.initialize(loadHelper, schedSignaler);

        // Store jobs.
        for (int i = 0; i < nJobs; i++) {
            JobDetail job = JobBuilder.newJob(MyJob.class).withIdentity("job" + i).build();
            store.storeJob(job, false);
        }

        // Retrieve jobs.
        for (int i = 0; i < nJobs; i++) {
            JobKey jobKey = JobKey.jobKey("job" + i);
            JobDetail storedJob = store.retrieveJob(jobKey);
            assertEquals(storedJob.getKey(), jobKey);
        }
    }

    @Test
    public void testStoreAndRetriveTriggers()
            throws Exception {

        final int nJobs = 10;

        SchedulerSignaler schedSignaler = new SampleSignaler();
        ClassLoadHelper loadHelper = new CascadingClassLoadHelper();
        loadHelper.initialize();

        JobStore store = createJobStore(hazelcastInstance, "testStoreAndRetriveTriggers");
        store.initialize(loadHelper, schedSignaler);

        // Store jobs and triggers.
        for (int i = 0; i < nJobs; i++) {
            JobDetail job = JobBuilder.newJob(MyJob.class).withIdentity("job" + i).build();
            store.storeJob(job, true);

            OperableTrigger trigger = buildTrigger("job" + i, DEFAULT_GROUP, job);
            store.storeTrigger(trigger, true);
        }
        // Retrieve jobs and triggers.
        for (int i = 0; i < nJobs; i++) {
            JobKey jobKey = JobKey.jobKey("job" + i);
            JobDetail storedJob = store.retrieveJob(jobKey);
            assertEquals(storedJob.getKey(), jobKey);

            TriggerKey triggerKey = TriggerKey.triggerKey("job" + i);
            OperableTrigger storedTrigger = store.retrieveTrigger(triggerKey);
            assertEquals(storedTrigger.getKey(), triggerKey);
        }
    }

    @Test
    public void testAcquireTriggers()
            throws Exception {

        final int nJobs = 10;

        SchedulerSignaler schedSignaler = new SampleSignaler();
        ClassLoadHelper loadHelper = new CascadingClassLoadHelper();
        loadHelper.initialize();

        JobStore store = createJobStore(hazelcastInstance, "testAcquireTriggers");
        store.initialize(loadHelper, schedSignaler);

        // Setup: Store jobs and triggers.
        long MIN = 60 * 1000L;
        Date startTime0 = new Date(System.currentTimeMillis() + MIN); // a min from
        // now.
        for (int i = 0; i < nJobs; i++) {
            Date startTime = new Date(startTime0.getTime() + i * MIN); // a min apart
            JobDetail job = JobBuilder.newJob(MyJob.class).withIdentity("job" + i).build();
            SimpleScheduleBuilder schedule = SimpleScheduleBuilder.repeatMinutelyForever(2);
            OperableTrigger trigger = (OperableTrigger) newTrigger()
                    .withIdentity("job" + i)
                    .withSchedule(schedule).forJob(job)
                    .startAt(startTime)
                    .build();

            // Manually trigger the first fire time computation that scheduler would
            // do. Otherwise
            // the store.acquireNextTriggers() will not work properly.
            Date fireTime = trigger.computeFirstFireTime(null);
            assertNotNull(fireTime);

            store.storeJobAndTrigger(job, trigger);
        }

        // Test acquire one trigger at a time
        for (int i = 0; i < nJobs; i++) {
            long noLaterThan = (startTime0.getTime() + i * MIN);
            int maxCount = 1;
            long timeWindow = 0;
            List<OperableTrigger> triggers = store.acquireNextTriggers(noLaterThan, maxCount, timeWindow);
            assertEquals(triggers.size(), 1);
            assertEquals(triggers.get(0).getKey().getName(), "job" + i);

            // Let's remove the trigger now.
            store.removeJob(triggers.get(0).getJobKey());
        }
    }

    @Test
    public void testAcquireTriggersInBatch()
            throws Exception {

        SchedulerSignaler schedSignaler = new SampleSignaler();
        ClassLoadHelper loadHelper = new CascadingClassLoadHelper();
        loadHelper.initialize();

        JobStore store = createJobStore(hazelcastInstance, "testAcquireTriggersInBatch");
        store.initialize(loadHelper, schedSignaler);

        // Setup: Store jobs and triggers.
        long MIN = 60 * 1000L;
        Date startTime0 = new Date(System.currentTimeMillis() + MIN); // a min from
        // now.
        for (int i = 0; i < 10; i++) {
            Date startTime = new Date(startTime0.getTime() + i * MIN); // a min apart
            JobDetail job = JobBuilder.newJob(MyJob.class).withIdentity("job" + i).build();
            SimpleScheduleBuilder schedule = SimpleScheduleBuilder.repeatMinutelyForever(2);
            OperableTrigger trigger = (OperableTrigger) newTrigger()
                    .withIdentity("job" + i)
                    .withSchedule(schedule)
                    .forJob(job)
                    .startAt(startTime)
                    .build();

            // Manually trigger the first fire time computation that scheduler would
            // do. Otherwise
            // the store.acquireNextTriggers() will not work properly.
            Date fireTime = trigger.computeFirstFireTime(null);
            assertNotNull(fireTime);

            store.storeJobAndTrigger(job, trigger);
        }

        // Test acquire batch of triggers at a time
        long noLaterThan = startTime0.getTime() + 10 * MIN;
        int maxCount = 7;
        // time window needs to be big to be able to pick up multiple triggers when
        // they are a minute apart
        long timeWindow = 8 * MIN;
        List<OperableTrigger> triggers = store.acquireNextTriggers(noLaterThan, maxCount, timeWindow);
        assertEquals(triggers.size(), 7);
    }

    @Test
    public void testStoreSimpleJob()
            throws JobPersistenceException {

        String jobName = "job20";
        storeJob(jobName);
        JobDetail retrieveJob = retrieveJob(jobName);
        assertNotNull(retrieveJob);
    }

    @Test
    public void storeTwiceSameJob()
            throws JobPersistenceException {

        String jobName = "job21";
        storeJob(jobName);
        assertThrows(ObjectAlreadyExistsException.class, () -> storeJob(jobName));
    }

    @Test
    public void testRemoveJob()
            throws JobPersistenceException {

        String jobName = "job22";
        JobDetail jobDetail = buildJob(jobName);
        storeJob(jobDetail);

        JobDetail retrieveJob = retrieveJob(jobName);
        assertNotNull(retrieveJob);

        OperableTrigger trigger = buildTrigger(jobDetail);
        jobStore.storeTrigger(trigger, false);

        assertNotNull(retrieveTrigger(trigger.getKey()));

        boolean removeJob = jobStore.removeJob(jobDetail.getKey());
        assertTrue(removeJob);
        retrieveJob = retrieveJob(jobName);
        assertNull(retrieveJob);

        assertNull(retrieveTrigger(trigger.getKey()));

        removeJob = jobStore.removeJob(jobDetail.getKey());
        assertFalse(removeJob);
    }

    @Test
    public void testRemoveJobs()
            throws JobPersistenceException {

        String jobName = "job24";
        JobDetail jobDetail = buildJob(jobName);

        String jobName2 = "job25";
        JobDetail jobDetailImpl2 = buildJob(jobName2);

        jobStore.storeJob(jobDetail, false);
        jobStore.storeJob(jobDetailImpl2, false);

        JobDetail retrieveJob = retrieveJob(jobName);
        assertNotNull(retrieveJob);

        List<JobKey> jobKeyList = new ArrayList<>(Arrays.asList(jobDetail.getKey(), jobDetailImpl2.getKey()));
        boolean removeJob = jobStore.removeJobs(jobKeyList);
        assertTrue(removeJob);

        retrieveJob = retrieveJob(jobName);
        assertNull(retrieveJob);
        retrieveJob = retrieveJob(jobName2);
        assertNull(retrieveJob);

        removeJob = jobStore.removeJob(jobDetail.getKey());
        assertFalse(removeJob);
        removeJob = jobStore.removeJob(jobDetailImpl2.getKey());
        assertFalse(removeJob);
    }

    @Test
    public void testCheckExistsJob()
            throws JobPersistenceException {

        JobDetail jobDetailImpl = buildJob("job23");
        jobStore.storeJob(jobDetailImpl, false);
        boolean checkExists = jobStore.checkExists(jobDetailImpl.getKey());
        assertTrue(checkExists);
    }

    @Test
    public void testStoreTriggerWithoutJob() {
        OperableTrigger trigger1 = (OperableTrigger) newTrigger().withIdentity("tKey1", "group").build();
        assertThrows(JobPersistenceException.class, () -> jobStore.storeTrigger(trigger1, false));
    }

    @Test
    public void testStoreTrigger()
            throws JobPersistenceException {

        OperableTrigger trigger1 = buildTrigger();
        jobStore.storeTrigger(trigger1, false);
        OperableTrigger retrieveTrigger = retrieveTrigger(trigger1.getKey());
        assertNotNull(retrieveTrigger);
    }

    @Test
    public void testStoreTriggerThrowsAlreadyExists()
            throws JobPersistenceException {

        OperableTrigger trigger1 = buildTrigger();
        jobStore.storeTrigger(trigger1, false);
        OperableTrigger retrieveTrigger = retrieveTrigger(trigger1.getKey());
        assertNotNull(retrieveTrigger);
        assertThrows(ObjectAlreadyExistsException.class, () -> jobStore.storeTrigger(trigger1, false));
        retrieveTrigger = retrieveTrigger(trigger1.getKey());
        assertNotNull(retrieveTrigger);
    }

    @Test
    public void testStoreTriggerTwice() throws JobPersistenceException {

        OperableTrigger trigger1 = buildTrigger();

        jobStore.storeTrigger(trigger1, false);
        OperableTrigger retrieveTrigger = retrieveTrigger(trigger1.getKey());
        assertNotNull(retrieveTrigger);
        jobStore.storeTrigger(trigger1, true);
        retrieveTrigger = retrieveTrigger(trigger1.getKey());
        assertNotNull(retrieveTrigger);
    }

    @Test
    public void testRemoveTrigger()
            throws JobPersistenceException {

        JobDetail storeJob = storeJob(buildJob("job"));
        OperableTrigger trigger1 = buildTrigger(storeJob);
        TriggerKey triggerKey = trigger1.getKey();
        jobStore.storeTrigger(trigger1, false);
        OperableTrigger retrieveTrigger = retrieveTrigger(trigger1.getKey());
        assertNotNull(retrieveTrigger);
        boolean removeTrigger = jobStore.removeTrigger(triggerKey);
        assertTrue(removeTrigger);
        retrieveTrigger = retrieveTrigger(trigger1.getKey());
        assertNull(retrieveTrigger);
        removeTrigger = jobStore.removeTrigger(triggerKey);
        assertFalse(removeTrigger);

        Trigger.TriggerState triggerState = jobStore.getTriggerState(triggerKey);
        assertEquals(triggerState, Trigger.TriggerState.NONE);
    }

    @Test
    public void testRemoveTriggers()
            throws JobPersistenceException {

        OperableTrigger trigger1 = buildTrigger();
        OperableTrigger trigger2 = buildTrigger();

        jobStore.storeTrigger(trigger1, false);
        jobStore.storeTrigger(trigger2, false);

        List<TriggerKey> triggerKeys = new ArrayList<>(Arrays.asList(trigger1.getKey(), trigger2.getKey()));
        boolean removeTriggers = jobStore.removeTriggers(triggerKeys);
        assertTrue(removeTriggers);
    }

    @Test
    public void testTriggerCheckExists()
            throws JobPersistenceException {

        OperableTrigger trigger1 = buildTrigger();
        TriggerKey triggerKey = trigger1.getKey();

        boolean checkExists = jobStore.checkExists(triggerKey);
        assertFalse(checkExists);

        jobStore.storeTrigger(trigger1, false);

        checkExists = jobStore.checkExists(triggerKey);
        assertTrue(checkExists);
    }

    @Test
    public void testReplaceTrigger()
            throws JobPersistenceException {

        OperableTrigger trigger1 = buildTrigger();

        jobStore.storeTrigger(trigger1, false);

        OperableTrigger newTrigger = buildTrigger();
        newTrigger.setJobKey(trigger1.getJobKey());


        TriggerKey triggerKey = trigger1.getKey();
        boolean replaceTrigger = jobStore.replaceTrigger(triggerKey, newTrigger);
        assertTrue(replaceTrigger);
        OperableTrigger retrieveTrigger = jobStore.retrieveTrigger(newTrigger.getKey());
        assertEquals(newTrigger, retrieveTrigger);
    }

    @Test
    public void testStoreJobAndTrigger()
            throws JobPersistenceException {

        JobDetail jobDetailImpl = buildJob("job30");

        OperableTrigger trigger1 = buildTrigger();
        jobStore.storeJobAndTrigger(jobDetailImpl, trigger1);
        JobDetail retrieveJob = jobStore.retrieveJob(jobDetailImpl.getKey());
        assertNotNull(retrieveJob);
        OperableTrigger retrieveTrigger = retrieveTrigger(trigger1.getKey());
        assertNotNull(retrieveTrigger);
    }

    @Test
    public void testStoreJobAndTriggerThrowJobAlreadyExists()
            throws JobPersistenceException {

        JobDetail jobDetailImpl = buildJob("job31");
        OperableTrigger trigger1 = buildTrigger();
        jobStore.storeJobAndTrigger(jobDetailImpl, trigger1);
        JobDetail retrieveJob = jobStore.retrieveJob(jobDetailImpl.getKey());
        assertNotNull(retrieveJob);
        OperableTrigger retrieveTrigger = retrieveTrigger(trigger1.getKey());
        assertNotNull(retrieveTrigger);

        assertThrows(ObjectAlreadyExistsException.class, () -> jobStore.storeJobAndTrigger(jobDetailImpl, trigger1));
    }

    @Test
    public void storeCalendar()
            throws JobPersistenceException {

        String calName = "calendar";
        storeCalendar(calName);
        Calendar retrieveCalendar = jobStore.retrieveCalendar(calName);
        assertNotNull(retrieveCalendar);
    }

    @Test
    public void testRemoveCalendar()
            throws JobPersistenceException {

        String calName = "calendar1";
        storeCalendar(calName);

        Calendar retrieveCalendar = jobStore.retrieveCalendar(calName);
        assertNotNull(retrieveCalendar);
        boolean calendarExisted = jobStore.removeCalendar(calName);
        assertTrue(calendarExisted);
        retrieveCalendar = jobStore.retrieveCalendar(calName);
        assertNull(retrieveCalendar);
        calendarExisted = jobStore.removeCalendar(calName);
        assertFalse(calendarExisted);

    }

    @Test
    public void testClearAllSchedulingData()
            throws JobPersistenceException {

        assertEquals(0, jobStore.getNumberOfJobs());
        assertEquals(0, jobStore.getNumberOfTriggers());
        assertEquals(0, jobStore.getNumberOfCalendars());

        final String jobName = "job40";
        final JobDetail storeJob = storeJob(jobName);
        assertEquals(1, jobStore.getNumberOfJobs());

        jobStore.storeTrigger(buildTrigger(storeJob), false);
        assertEquals(1, jobStore.getNumberOfTriggers());

        jobStore.storeCalendar("calendar", new BaseCalendar(), false, false);
        assertEquals(1, jobStore.getNumberOfCalendars());

        jobStore.clearAllSchedulingData();

        assertEquals(0, jobStore.getNumberOfJobs());
        assertEquals(0, jobStore.getNumberOfTriggers());
        assertEquals(0, jobStore.getNumberOfCalendars());
    }

    @Test
    public void testStoreSameJobNameWithDifferentGroup()
            throws JobPersistenceException {

        storeJob(buildJob("job40", "group1"));
        storeJob(buildJob("job40", "group2"));
        // Assert there is no exception throws
    }

    @Test
    public void testGetJobGroupNames()
            throws JobPersistenceException {

        JobDetail buildJob = buildJob("job40", "group1");
        storeJob(buildJob);
        storeJob(buildJob("job41", "group2"));
        List<String> jobGroupNames = jobStore.getJobGroupNames();
        assertEquals(jobGroupNames.size(), 2);
        assertTrue(jobGroupNames.contains("group1"));
        assertTrue(jobGroupNames.contains("group2"));

        jobStore.removeJob(buildJob.getKey());

        jobGroupNames = jobStore.getJobGroupNames();
        assertEquals(jobGroupNames.size(), 1);
        assertTrue(jobGroupNames.contains("group2"));
    }

    @Test
    public void testJobKeyByGroup()
            throws JobPersistenceException {

        JobDetail job1group1 = buildJob("job1", "group1");
        storeJob(job1group1);
        JobDetail job1group2 = buildJob("job1", "group2");
        storeJob(job1group2);
        storeJob(buildJob("job2", "group2"));
        List<String> jobGroupNames = jobStore.getJobGroupNames();
        assertEquals(jobGroupNames.size(), 2);
        assertTrue(jobGroupNames.contains("group1"));
        assertTrue(jobGroupNames.contains("group2"));

        jobStore.removeJob(job1group1.getKey());

        jobGroupNames = jobStore.getJobGroupNames();
        assertEquals(jobGroupNames.size(), 1);
        assertTrue(jobGroupNames.contains("group2"));

        jobStore.removeJob(job1group2.getKey());

        jobGroupNames = jobStore.getJobGroupNames();
        assertEquals(jobGroupNames.size(), 1);
        assertTrue(jobGroupNames.contains("group2"));
    }

    @Test
    public void testGetTriggerGroupNames()
            throws JobPersistenceException {

        JobDetail storeJob = storeJob(buildJob("job"));

        jobStore.storeTrigger(buildTrigger("trigger1", "group1", storeJob), false);
        jobStore.storeTrigger(buildTrigger("trigger2", "group2", storeJob), false);

        List<String> triggerGroupNames = jobStore.getTriggerGroupNames();
        assertEquals(triggerGroupNames.size(), 2);
        assertTrue(triggerGroupNames.contains("group1"));
        assertTrue(triggerGroupNames.contains("group2"));
    }

    @Test
    public void testCalendarNames()
            throws JobPersistenceException {

        storeCalendar("cal1");
        storeCalendar("cal2");
        List<String> calendarNames = jobStore.getCalendarNames();
        assertEquals(calendarNames.size(), 2);
        assertTrue(calendarNames.contains("cal1"));
        assertTrue(calendarNames.contains("cal2"));
    }

    @Test
    public void storeJobAndTriggers()
            throws JobPersistenceException {

        Map<JobDetail, Set<? extends Trigger>> triggersAndJobs = new HashMap<>();

        JobDetail job1 = buildJob();
        OperableTrigger trigger1 = buildTrigger(job1);
        Set<Trigger> set1 = new HashSet<>();
        set1.add(trigger1);
        triggersAndJobs.put(job1, set1);

        JobDetail job2 = buildJob();
        OperableTrigger trigger2 = buildTrigger(job2);
        Set<Trigger> set2 = new HashSet<>();
        set2.add(trigger2);
        triggersAndJobs.put(job2, set2);

        jobStore.storeJobsAndTriggers(triggersAndJobs, false);

        JobDetail retrieveJob1 = retrieveJob(job1.getKey().getName());
        assertNotNull(retrieveJob1);

        JobDetail retrieveJob2 = retrieveJob(job2.getKey().getName());
        assertNotNull(retrieveJob2);

        OperableTrigger retrieveTrigger1 = retrieveTrigger(trigger1.getKey());
        assertNotNull(retrieveTrigger1);

        OperableTrigger retrieveTrigger2 = retrieveTrigger(trigger2.getKey());
        assertNotNull(retrieveTrigger2);
    }

    @Test
    public void storeJobAndTriggersThrowException()
            throws JobPersistenceException {

        Map<JobDetail, Set<? extends Trigger>> triggersAndJobs = new HashMap<>();
        JobDetail job1 = buildJob();
        storeJob(job1);
        OperableTrigger trigger1 = buildTrigger(job1);
        Set<Trigger> set1 = new HashSet<>();
        set1.add(trigger1);
        triggersAndJobs.put(job1, set1);
        assertThrows(ObjectAlreadyExistsException.class, () -> jobStore.storeJobsAndTriggers(triggersAndJobs, false));
    }

    @Test
    public void testGetTriggersForJob()
            throws JobPersistenceException {

        JobDetail job = buildAndStoreJob();
        OperableTrigger trigger1 = buildTrigger(job);
        OperableTrigger trigger2 = buildTrigger(job);
        jobStore.storeTrigger(trigger1, false);
        jobStore.storeTrigger(trigger2, false);

        List<OperableTrigger> triggersForJob = jobStore.getTriggersForJob(job.getKey());

        assertEquals(triggersForJob.size(), 2);
        assertTrue(triggersForJob.contains(trigger1));
        assertTrue(triggersForJob.contains(trigger2));
    }

    @Test
    public void testPauseTrigger()
            throws JobPersistenceException {

        OperableTrigger trigger = buildTrigger();
        jobStore.storeTrigger(trigger, false);
        TriggerKey triggerKey = trigger.getKey();
        Trigger.TriggerState triggerState = jobStore.getTriggerState(triggerKey);
        assertEquals(Trigger.TriggerState.NORMAL, triggerState);
        jobStore.pauseTrigger(triggerKey);
        triggerState = jobStore.getTriggerState(triggerKey);
        assertEquals(Trigger.TriggerState.PAUSED, triggerState);
    }

    @Test
    public void testResumeTrigger()
            throws JobPersistenceException {

        OperableTrigger trigger = buildTrigger();
        jobStore.storeTrigger(trigger, false);
        TriggerKey triggerKey = trigger.getKey();
        Trigger.TriggerState triggerState = jobStore.getTriggerState(triggerKey);
        assertEquals(triggerState, Trigger.TriggerState.NORMAL);
        jobStore.pauseTrigger(triggerKey);
        triggerState = jobStore.getTriggerState(triggerKey);
        assertEquals(triggerState, Trigger.TriggerState.PAUSED);

        jobStore.resumeTrigger(triggerKey);
        triggerState = jobStore.getTriggerState(triggerKey);
        assertEquals(triggerState, Trigger.TriggerState.NORMAL);
    }

    @Test
    public void testPauseTriggers()
            throws JobPersistenceException {

        OperableTrigger trigger = buildAndStoreTrigger();
        OperableTrigger trigger1 = buildAndStoreTrigger();
        OperableTrigger trigger2 = buildTrigger("trigger2", "group2", buildAndStoreJob());
        jobStore.storeTrigger(trigger2, false);
        assertEquals(jobStore.getTriggerState(trigger.getKey()), Trigger.TriggerState.NORMAL);
        assertEquals(jobStore.getTriggerState(trigger1.getKey()), Trigger.TriggerState.NORMAL);

        Collection<String> pauseTriggers = jobStore
                .pauseTriggers(GroupMatcher.triggerGroupEquals(trigger.getKey().getGroup()));

        assertEquals(pauseTriggers.size(), 1);
        assertTrue(pauseTriggers.contains(trigger.getKey().getGroup()));

        assertEquals(jobStore.getPausedTriggerGroups().size(), 1);
        assertTrue(jobStore.getPausedTriggerGroups().contains(trigger.getKey().getGroup()));

        OperableTrigger trigger3 = buildAndStoreTrigger();

        assertEquals(jobStore.getTriggerState(trigger.getKey()), Trigger.TriggerState.PAUSED);
        assertEquals(jobStore.getTriggerState(trigger1.getKey()), Trigger.TriggerState.PAUSED);
        assertEquals(jobStore.getTriggerState(trigger2.getKey()), Trigger.TriggerState.NORMAL);
        assertEquals(jobStore.getTriggerState(trigger3.getKey()), Trigger.TriggerState.PAUSED);
    }

    @Test
    public void testResumeTriggers()
            throws JobPersistenceException {

        OperableTrigger trigger = buildAndStoreTrigger();
        OperableTrigger trigger1 = buildAndStoreTrigger();
        OperableTrigger trigger2 = buildTrigger("trigger2", "group2", buildAndStoreJob());
        jobStore.storeTrigger(trigger2, false);
        assertEquals(Trigger.TriggerState.NORMAL, jobStore.getTriggerState(trigger.getKey()));
        assertEquals(Trigger.TriggerState.NORMAL, jobStore.getTriggerState(trigger1.getKey()));

        Collection<String> pauseTriggers = jobStore
                .pauseTriggers(GroupMatcher.triggerGroupEquals(trigger.getKey().getGroup()));

        assertEquals(pauseTriggers.size(), 1);
        assertTrue(pauseTriggers.contains(trigger.getKey().getGroup()));

        OperableTrigger trigger3 = buildAndStoreTrigger();

        assertEquals(jobStore.getTriggerState(trigger.getKey()), Trigger.TriggerState.PAUSED);
        assertEquals(jobStore.getTriggerState(trigger1.getKey()), Trigger.TriggerState.PAUSED);
        assertEquals(jobStore.getTriggerState(trigger2.getKey()), Trigger.TriggerState.NORMAL);
        assertEquals(jobStore.getTriggerState(trigger3.getKey()), Trigger.TriggerState.PAUSED);

        Collection<String> resumeTriggers = jobStore.resumeTriggers(GroupMatcher.triggerGroupEquals(trigger.getKey()
                .getGroup()));

        assertEquals(resumeTriggers.size(), 1);
        assertTrue(resumeTriggers.contains(trigger.getKey().getGroup()));

        assertEquals(jobStore.getTriggerState(trigger.getKey()), Trigger.TriggerState.NORMAL);
        assertEquals(jobStore.getTriggerState(trigger1.getKey()), Trigger.TriggerState.NORMAL);
        assertEquals(jobStore.getTriggerState(trigger3.getKey()), Trigger.TriggerState.NORMAL);

        OperableTrigger trigger4 = buildAndStoreTrigger();
        assertEquals(jobStore.getTriggerState(trigger4.getKey()), Trigger.TriggerState.NORMAL);
    }

    @Test
    public void testResumeTriggerWithPausedJobs()
            throws JobPersistenceException {

        JobDetail job1 = buildJob("job", "group3");
        storeJob(job1);
        OperableTrigger trigger5 = buildTrigger(job1);
        jobStore.storeTrigger(trigger5, false);

        assertEquals(jobStore.getTriggerState(trigger5.getKey()), Trigger.TriggerState.NORMAL);
        jobStore.pauseJobs(GroupMatcher.jobGroupEquals("group3"));
        jobStore.resumeTriggers(GroupMatcher.triggerGroupEquals(trigger5.getKey().getGroup()));
        assertEquals(jobStore.getTriggerState(trigger5.getKey()), Trigger.TriggerState.PAUSED);
    }

    @Test
    public void testPauseJob()
            throws JobPersistenceException {

        JobDetail jobDetail = buildAndStoreJob();
        OperableTrigger trigger = buildTrigger(jobDetail);
        jobStore.storeTrigger(trigger, false);
        Trigger.TriggerState triggerState = jobStore.getTriggerState(trigger.getKey());
        assertEquals(triggerState, Trigger.TriggerState.NORMAL);

        jobStore.pauseJob(jobDetail.getKey());

        triggerState = jobStore.getTriggerState(trigger.getKey());
        assertEquals(triggerState, Trigger.TriggerState.PAUSED);
    }

    @Test
    public void testResumeJob()
            throws JobPersistenceException {

        JobDetail jobDetail = buildAndStoreJob();
        OperableTrigger trigger = buildTrigger(jobDetail);
        jobStore.storeTrigger(trigger, false);

        Trigger.TriggerState triggerState = jobStore.getTriggerState(trigger.getKey());
        assertEquals(Trigger.TriggerState.NORMAL, triggerState);

        jobStore.pauseJob(jobDetail.getKey());

        triggerState = jobStore.getTriggerState(trigger.getKey());
        assertEquals(Trigger.TriggerState.PAUSED, triggerState);

        jobStore.resumeJob(jobDetail.getKey());

        triggerState = jobStore.getTriggerState(trigger.getKey());
        assertEquals(Trigger.TriggerState.NORMAL, triggerState);
    }

    @Test
    public void testPauseJobs()
            throws JobPersistenceException {

        JobDetail job1 = buildAndStoreJobWithTrigger();
        JobDetail job2 = buildAndStoreJobWithTrigger();

        JobDetail job3 = buildJob("job3", "newgroup");
        storeJob(job3);
        jobStore.storeTrigger(buildTrigger(job3), false);

        List<OperableTrigger> triggersForJob = jobStore.getTriggersForJob(job1.getKey());
        triggersForJob.addAll(jobStore.getTriggersForJob(job2.getKey()));
        for (OperableTrigger trigger : triggersForJob) {
            assertEquals(jobStore.getTriggerState(trigger.getKey()), Trigger.TriggerState.NORMAL);
        }

        Collection<String> pauseJobs = jobStore.pauseJobs(GroupMatcher.jobGroupEquals(job1.getKey().getGroup()));

        assertEquals(pauseJobs.size(), 1);
        assertTrue(pauseJobs.contains(job1.getKey().getGroup()));

        JobDetail job4 = buildAndStoreJobWithTrigger();

        triggersForJob = jobStore.getTriggersForJob(job1.getKey());
        triggersForJob.addAll(jobStore.getTriggersForJob(job2.getKey()));
        triggersForJob.addAll(jobStore.getTriggersForJob(job4.getKey()));
        for (OperableTrigger trigger : triggersForJob) {
            Trigger.TriggerState triggerState = jobStore.getTriggerState(trigger.getKey());
            LOG.debug("State : [" + triggerState
                    + "]Should be PAUSED for trigger : [" + trigger.getKey()
                    + "] and job [" + trigger.getJobKey() + "]");
            assertEquals(triggerState, Trigger.TriggerState.PAUSED);
        }

        triggersForJob = jobStore.getTriggersForJob(job3.getKey());

        for (OperableTrigger trigger : triggersForJob) {
            assertEquals(jobStore.getTriggerState(trigger.getKey()), Trigger.TriggerState.NORMAL);
        }
    }

    @Test
    public void testResumeJobs()
            throws JobPersistenceException {

        JobDetail job1 = buildAndStoreJobWithTrigger();
        JobDetail job2 = buildAndStoreJob();
        OperableTrigger trigger2 = buildTrigger("trigger", "trigGroup2", job2);

        jobStore.storeTrigger(trigger2, false);

        JobDetail job3 = buildJob("job3", "newgroup");
        storeJob(job3);

        jobStore.storeTrigger(buildTrigger(job3), false);

        Collection<String> pauseJobs = jobStore.pauseJobs(GroupMatcher.anyJobGroup());

        assertEquals(pauseJobs.size(), 2);
        assertTrue(pauseJobs.contains(job1.getKey().getGroup()));
        assertTrue(pauseJobs.contains("newgroup"));

        List<OperableTrigger> triggersForJob = jobStore.getTriggersForJob(job1.getKey());

        for (OperableTrigger trigger : triggersForJob) {
            Trigger.TriggerState triggerState = jobStore.getTriggerState(trigger.getKey());
            assertEquals(triggerState, Trigger.TriggerState.PAUSED);
        }

        triggersForJob = jobStore.getTriggersForJob(job3.getKey());
        for (OperableTrigger trigger : triggersForJob) {
            assertEquals(jobStore.getTriggerState(trigger.getKey()), Trigger.TriggerState.PAUSED);
        }

        jobStore.pauseTriggers(GroupMatcher.triggerGroupEquals("trigGroup2"));

        jobStore.resumeJobs(GroupMatcher.jobGroupEquals(job1.getKey().getGroup()));

        triggersForJob = jobStore.getTriggersForJob(job3.getKey());
        for (OperableTrigger trigger : triggersForJob) {
            assertEquals(jobStore.getTriggerState(trigger.getKey()), Trigger.TriggerState.PAUSED);
        }

        triggersForJob = jobStore.getTriggersForJob(job1.getKey());
        for (OperableTrigger trigger : triggersForJob) {
            assertEquals(jobStore.getTriggerState(trigger.getKey()), Trigger.TriggerState.NORMAL);
        }
    }

    @Test
    public void testPauseAll()
            throws JobPersistenceException {

        OperableTrigger trigger1 = buildAndStoreTrigger();
        OperableTrigger trigger2 = buildAndStoreTrigger();

        OperableTrigger trigger3 = buildTrigger("SpecialTriggerG2", "SpecialGroupG2");
        jobStore.storeTrigger(trigger3, false);

        assertEquals(jobStore.getTriggerState(trigger1.getKey()), Trigger.TriggerState.NORMAL);

        jobStore.pauseAll();

        assertEquals(jobStore.getPausedTriggerGroups().size(), 2);
        assertTrue(jobStore.getPausedTriggerGroups().contains(trigger1.getKey().getGroup()));
        assertTrue(jobStore.getPausedTriggerGroups().contains(trigger2.getKey().getGroup()));
        assertTrue(jobStore.getPausedTriggerGroups().contains(trigger3.getKey().getGroup()));

        OperableTrigger trigger4 = buildAndStoreTrigger();

        assertEquals(jobStore.getTriggerState(trigger1.getKey()), Trigger.TriggerState.PAUSED);
        assertEquals(jobStore.getTriggerState(trigger2.getKey()), Trigger.TriggerState.PAUSED);
        assertEquals(jobStore.getTriggerState(trigger3.getKey()), Trigger.TriggerState.PAUSED);
        assertEquals(jobStore.getTriggerState(trigger4.getKey()), Trigger.TriggerState.PAUSED);
    }

    @Test
    public void testResumeAll()
            throws JobPersistenceException {

        OperableTrigger trigger1 = buildTrigger("SpecialTrigger1", "SpecialGroupG1");
        OperableTrigger trigger2 = buildTrigger("SpecialTrigger2", "SpecialGroupG1");
        OperableTrigger trigger3 = buildTrigger("SpecialTriggerG2", "SpecialGroupG2");
        jobStore.storeTrigger(trigger1, false);
        jobStore.storeTrigger(trigger2, false);
        jobStore.storeTrigger(trigger3, false);

        assertEquals(jobStore.getTriggerState(trigger1.getKey()), Trigger.TriggerState.NORMAL);

        jobStore.pauseAll();

        assertEquals(jobStore.getPausedTriggerGroups().size(), 2);
        assertTrue(jobStore.getPausedTriggerGroups().contains(trigger1.getKey().getGroup()));
        assertTrue(jobStore.getPausedTriggerGroups().contains(trigger2.getKey().getGroup()));
        assertTrue(jobStore.getPausedTriggerGroups().contains(trigger3.getKey().getGroup()));

        OperableTrigger trigger4 = buildTrigger("SpecialTrigger3", "SpecialGroupG1");
        jobStore.storeTrigger(trigger4, false);

        assertEquals(jobStore.getTriggerState(trigger1.getKey()), Trigger.TriggerState.PAUSED);
        assertEquals(jobStore.getTriggerState(trigger2.getKey()), Trigger.TriggerState.PAUSED);
        assertEquals(jobStore.getTriggerState(trigger3.getKey()), Trigger.TriggerState.PAUSED);
        assertEquals(jobStore.getTriggerState(trigger4.getKey()), Trigger.TriggerState.PAUSED);

        jobStore.resumeAll();
        assertEquals(jobStore.getTriggerState(trigger1.getKey()), Trigger.TriggerState.NORMAL);
        assertEquals(jobStore.getTriggerState(trigger2.getKey()), Trigger.TriggerState.NORMAL);
        assertEquals(jobStore.getTriggerState(trigger3.getKey()), Trigger.TriggerState.NORMAL);
        assertEquals(jobStore.getTriggerState(trigger4.getKey()), Trigger.TriggerState.NORMAL);
    }

    @Test
    public void testGetTriggerState()
            throws JobPersistenceException {

        Trigger.TriggerState triggerState = jobStore.getTriggerState(new TriggerKey("noname"));
        assertEquals(triggerState, Trigger.TriggerState.NONE);

    }

    @Test
    public void testTriggersFired()
            throws Exception {

        long baseFireTime = System.currentTimeMillis();

        JobDetail newJob = JobBuilder.newJob(MyJob.class).withIdentity("job1", "testTriggersFired").build();

        jobStore.storeJob(newJob, false);

        OperableTrigger trigger1 = buildAndComputeTrigger("triggerFired1",
                "triggerFiredGroup",
                newJob,
                baseFireTime + 100,
                baseFireTime + 100);

        jobStore.storeTrigger(trigger1, false);

        long firstFireTime = new Date(trigger1.getNextFireTime().getTime()).getTime();

        List<OperableTrigger> acquiredTriggers = jobStore.acquireNextTriggers(firstFireTime + 500, 1, 0L);
        assertEquals(acquiredTriggers.size(), 1);

        List<TriggerFiredResult> triggerFired = jobStore.triggersFired(acquiredTriggers);
        assertEquals(triggerFired.size(), 1);

        TriggerFiredBundle bundle = triggerFired.get(0).getTriggerFiredBundle();

        jobStore.triggeredJobComplete(bundle.getTrigger(), bundle.getJobDetail(), bundle.getTrigger().executionComplete(null, null));

        // Since trigger executed without exceptions, it must be deleted is jobStore.triggeredJobComplete
        assertFalse(jobStore.checkExists(trigger1.getKey()));
    }

    private void assertAcquiredAndRelease(long baseFireTime, int numTriggersExpected)
            throws JobPersistenceException {
        List<OperableTrigger> operableTriggers = jobStore.acquireNextTriggers(baseFireTime + 600, 10, 0L);
        assertEquals(numTriggersExpected, operableTriggers.size());

        jobStore.triggersFired(operableTriggers);

        operableTriggers.forEach(t -> jobStore.releaseAcquiredTrigger(t));
    }

    static HazelcastJobStore createJobStore(HazelcastInstance instance, String name) {
        HazelcastJobStore jobStore = AbstractTest.createJobStore(instance, name);
        // shorter sleep interval to shorten test duration
        jobStore.setMisfireThreshold(1000);
        return jobStore;
    }

    JobDetail storeJob(String jobName)
            throws JobPersistenceException {

        return storeJob(buildJob(jobName));
    }

    JobDetail storeJob(JobDetail jobDetail)
            throws JobPersistenceException {

        jobStore.storeJob(jobDetail, false);
        return jobDetail;
    }

    JobDetail buildAndStoreJob()
            throws JobPersistenceException {

        JobDetail buildJob = buildJob();
        jobStore.storeJob(buildJob, false);
        return buildJob;
    }

    JobDetail buildAndStoreJobWithTrigger()
            throws JobPersistenceException {

        JobDetail buildJob = buildJob();
        jobStore.storeJob(buildJob, false);

        OperableTrigger trigger = buildTrigger(buildJob);
        jobStore.storeTrigger(trigger, false);

        return buildJob;
    }

    JobDetail retrieveJob(String jobName)
            throws JobPersistenceException {

        return jobStore.retrieveJob(new JobKey(jobName, DEFAULT_GROUP));
    }

    OperableTrigger buildTrigger()
            throws JobPersistenceException {

        return buildTrigger("triggerName" + buildTriggerIndex++, DEFAULT_GROUP, buildAndStoreJob());
    }

    @SuppressWarnings("SameParameterValue")
    OperableTrigger buildTrigger(String triggerName, String groupName)
            throws JobPersistenceException {

        return buildTrigger(triggerName, groupName, buildAndStoreJob());
    }

    OperableTrigger buildAndStoreTrigger() throws JobPersistenceException {

        OperableTrigger trigger = buildTrigger();
        jobStore.storeTrigger(trigger, false);
        return trigger;
    }

    OperableTrigger retrieveTrigger(TriggerKey triggerKey)
            throws JobPersistenceException {

        return jobStore.retrieveTrigger(triggerKey);
    }

    void storeCalendar(String calName) throws JobPersistenceException {

        jobStore.storeCalendar(calName, new BaseCalendar(), false, false);
    }
}
