package com.breakingequity.quarz.store.hazelcast;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.quartz.*;
import org.quartz.Calendar;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.spi.*;

import java.util.*;

/**
 * @author olegzinovev
 * @since 2019-01-15
 **/
public class HazelcastJobStoreDelegate implements JobStore {

    private static HazelcastInstance globalInstance;

    public static void setInstance(HazelcastInstance instance) {
        HazelcastJobStoreDelegate.globalInstance = instance;
    }

    private String hazelcastInstanceName;
    private String schedInstanceId;
    private String schedName;
    private long misfireThreshold = 5000;
    private int threadPoolSize;

    private HazelcastJobStore clusteredJobStore;
    private HazelcastInstance instance;
    private boolean shutdownInstance = false;

    @Override
    public void setThreadPoolSize(final int size) {
        this.threadPoolSize = size;
    }

    @Override
    public List<OperableTrigger> acquireNextTriggers(long noLaterThan, int maxCount, long timeWindow)
            throws JobPersistenceException {
        return clusteredJobStore.acquireNextTriggers(noLaterThan, maxCount, timeWindow);
    }

    @Override
    public List<String> getCalendarNames() throws JobPersistenceException {
        return clusteredJobStore.getCalendarNames();
    }

    @Override
    public List<String> getJobGroupNames() throws JobPersistenceException {
        return clusteredJobStore.getJobGroupNames();
    }

    @Override
    public Set<JobKey> getJobKeys(final GroupMatcher<JobKey> matcher) throws JobPersistenceException {
        return clusteredJobStore.getJobKeys(matcher);
    }

    @Override
    public int getNumberOfCalendars() throws JobPersistenceException {
        return clusteredJobStore.getNumberOfCalendars();
    }

    @Override
    public int getNumberOfJobs() throws JobPersistenceException {
        return clusteredJobStore.getNumberOfJobs();
    }

    @Override
    public int getNumberOfTriggers() throws JobPersistenceException {
        return clusteredJobStore.getNumberOfTriggers();
    }

    @Override
    public Set<String> getPausedTriggerGroups() throws JobPersistenceException {
        return clusteredJobStore.getPausedTriggerGroups();
    }

    @Override
    public List<String> getTriggerGroupNames() throws JobPersistenceException {
        return clusteredJobStore.getTriggerGroupNames();
    }

    @Override
    public Set<TriggerKey> getTriggerKeys(final GroupMatcher<TriggerKey> matcher) throws JobPersistenceException {
        return clusteredJobStore.getTriggerKeys(matcher);
    }

    @Override
    public List<OperableTrigger> getTriggersForJob(final JobKey jobKey) throws JobPersistenceException {
        return clusteredJobStore.getTriggersForJob(jobKey);
    }

    @Override
    public Trigger.TriggerState getTriggerState(final TriggerKey triggerKey) throws JobPersistenceException {
        return clusteredJobStore.getTriggerState(triggerKey);
    }

    @Override
    public void resetTriggerFromErrorState(final TriggerKey triggerKey) {
        clusteredJobStore.resetTriggerFromErrorState(triggerKey);
    }

    @Override
    public synchronized void initialize(ClassLoadHelper loadHelper, SchedulerSignaler signaler) {
        if (clusteredJobStore != null) {
            throw new IllegalStateException("already initialized");
        }

        if (hazelcastInstanceName != null) {
            instance = Hazelcast.getHazelcastInstanceByName(hazelcastInstanceName);
            Objects.requireNonNull(instance, "Hazelcast instance not found by name: " + hazelcastInstanceName);
        } else if (globalInstance != null) {
            instance = globalInstance;
        } else {
            instance = Hazelcast.newHazelcastInstance();
            shutdownInstance = true;
        }

        clusteredJobStore = new HazelcastJobStore(instance, schedName);
        clusteredJobStore.setThreadPoolSize(threadPoolSize);

        // apply deferred misfire threshold if present
        clusteredJobStore.setMisfireThreshold(misfireThreshold);
        clusteredJobStore.setInstanceId(schedInstanceId);
        clusteredJobStore.setTcRetryInterval(100);
        clusteredJobStore.initialize(loadHelper, signaler);
    }

    @Override
    public void pauseAll() throws JobPersistenceException {
        clusteredJobStore.pauseAll();
    }

    @Override
    public void pauseJob(final JobKey jobKey) throws JobPersistenceException {
        clusteredJobStore.pauseJob(jobKey);
    }

    @Override
    public Collection<String> pauseJobs(GroupMatcher<JobKey> matcher) throws JobPersistenceException {
        return clusteredJobStore.pauseJobs(matcher);
    }

    @Override
    public void pauseTrigger(TriggerKey triggerKey) throws JobPersistenceException {
        clusteredJobStore.pauseTrigger(triggerKey);
    }

    @Override
    public Collection<String> pauseTriggers(GroupMatcher<TriggerKey> matcher) throws JobPersistenceException {
        return clusteredJobStore.pauseTriggers(matcher);
    }

    @Override
    public void releaseAcquiredTrigger(final OperableTrigger trigger) {
        clusteredJobStore.releaseAcquiredTrigger(trigger);
    }

    @Override
    public List<TriggerFiredResult> triggersFired(final List<OperableTrigger> triggers) throws JobPersistenceException {
        return clusteredJobStore.triggersFired(triggers);
    }

    @Override
    public boolean removeCalendar(String calName) throws JobPersistenceException {
        return clusteredJobStore.removeCalendar(calName);
    }

    @Override
    public boolean removeJob(JobKey jobKey) throws JobPersistenceException {
        return clusteredJobStore.removeJob(jobKey);
    }

    @Override
    public boolean removeTrigger(TriggerKey triggerKey) throws JobPersistenceException {
        return clusteredJobStore.removeTrigger(triggerKey);
    }

    @Override
    public boolean removeJobs(List<JobKey> jobKeys) throws JobPersistenceException {
        return clusteredJobStore.removeJobs(jobKeys);
    }

    @Override
    public boolean removeTriggers(List<TriggerKey> triggerKeys) throws JobPersistenceException {
        return clusteredJobStore.removeTriggers(triggerKeys);
    }

    @Override
    public void storeJobsAndTriggers(Map<JobDetail, Set<? extends Trigger>> triggersAndJobs, boolean replace)
            throws JobPersistenceException {
        clusteredJobStore.storeJobsAndTriggers(triggersAndJobs, replace);
    }

    @Override
    public boolean replaceTrigger(TriggerKey triggerKey, OperableTrigger newTrigger) throws JobPersistenceException {
        return clusteredJobStore.replaceTrigger(triggerKey, newTrigger);
    }

    @Override
    public void resumeAll() throws JobPersistenceException {
        clusteredJobStore.resumeAll();
    }

    @Override
    public void resumeJob(JobKey jobKey) throws JobPersistenceException {
        clusteredJobStore.resumeJob(jobKey);
    }

    @Override
    public Collection<String> resumeJobs(GroupMatcher<JobKey> matcher) throws JobPersistenceException {
        return clusteredJobStore.resumeJobs(matcher);
    }

    @Override
    public void resumeTrigger(TriggerKey triggerKey) throws JobPersistenceException {
        clusteredJobStore.resumeTrigger(triggerKey);
    }

    @Override
    public Collection<String> resumeTriggers(GroupMatcher<TriggerKey> matcher) throws JobPersistenceException {
        return clusteredJobStore.resumeTriggers(matcher);
    }

    @Override
    public Calendar retrieveCalendar(String calName) throws JobPersistenceException {
        return clusteredJobStore.retrieveCalendar(calName);
    }

    @Override
    public JobDetail retrieveJob(JobKey jobKey) throws JobPersistenceException {
        return clusteredJobStore.retrieveJob(jobKey);
    }

    @Override
    public OperableTrigger retrieveTrigger(TriggerKey triggerKey) throws JobPersistenceException {
        return clusteredJobStore.retrieveTrigger(triggerKey);
    }

    @Override
    public boolean checkExists(final JobKey jobKey) {
        return clusteredJobStore.checkExists(jobKey);
    }

    @Override
    public boolean checkExists(final TriggerKey triggerKey) {
        return clusteredJobStore.checkExists(triggerKey);
    }

    @Override
    public void clearAllSchedulingData() throws JobPersistenceException {
        clusteredJobStore.clearAllSchedulingData();
    }

    @Override
    public void schedulerStarted() throws SchedulerException {
        clusteredJobStore.schedulerStarted();
    }

    @Override
    public void schedulerPaused() {
        if (clusteredJobStore != null) {
            clusteredJobStore.schedulerPaused();
        }
    }

    @Override
    public void schedulerResumed() {
        clusteredJobStore.schedulerResumed();
    }

    @Override
    public void shutdown() {
        if (clusteredJobStore != null) {
            clusteredJobStore.shutdown();
        }

        if (shutdownInstance) {
            instance.shutdown();
        }
    }

    @Override
    public void storeCalendar(String name, Calendar calendar, boolean replaceExisting, boolean updateTriggers)
            throws JobPersistenceException {
        clusteredJobStore.storeCalendar(name, calendar, replaceExisting, updateTriggers);
    }

    @Override
    public void storeJob(JobDetail newJob, boolean replaceExisting) throws JobPersistenceException {
        clusteredJobStore.storeJob(newJob, replaceExisting);
    }

    @Override
    public void storeJobAndTrigger(JobDetail newJob, OperableTrigger newTrigger) throws JobPersistenceException {
        clusteredJobStore.storeJobAndTrigger(newJob, newTrigger);
    }

    @Override
    public void storeTrigger(OperableTrigger newTrigger, boolean replaceExisting) throws JobPersistenceException {
        clusteredJobStore.storeTrigger(newTrigger, replaceExisting);
    }

    @Override
    public boolean supportsPersistence() {
        return true;
    }

    @Override
    public long getEstimatedTimeToReleaseAndAcquireTrigger() {
        return clusteredJobStore.getEstimatedTimeToReleaseAndAcquireTrigger();
    }

    @Override
    public String toString() {
        return clusteredJobStore.toString();
    }

    @Override
    public void triggeredJobComplete(OperableTrigger trigger, JobDetail jobDetail,
                                     Trigger.CompletedExecutionInstruction triggerInstCode) {
        clusteredJobStore.triggeredJobComplete(trigger, jobDetail, triggerInstCode);
    }

    @Override
    public void setInstanceId(String schedInstId) {
        this.schedInstanceId = schedInstId;
    }

    @Override
    public void setInstanceName(String schedName) {
        this.schedName = schedName;
    }


    @Override
    public long getAcquireRetryDelay(int failureCount) {
        return 1000;
    }

    @Override
    public boolean isClustered() {
        return true;
    }

    @SuppressWarnings("unused")
    public long getMisfireThreshold() {
        return misfireThreshold;
    }

    /**
     * The number of milliseconds by which a trigger must have missed its
     * next-fire-time, in order for it to be considered "misfired" and thus
     * have its misfire instruction applied.
     *
     * @param misfireThreshold the new misfire threshold
     */
    @SuppressWarnings("UnusedDeclaration")
    public void setMisfireThreshold(long misfireThreshold) {
        if (misfireThreshold < 1) {
            throw new IllegalArgumentException("Misfire threshold must be larger than 0");
        }

        this.misfireThreshold = misfireThreshold;
    }

    @SuppressWarnings("unused")
    public void setHazelcastInstanceName(String hazelcastInstanceName) {
        this.hazelcastInstanceName = hazelcastInstanceName;
    }
}
