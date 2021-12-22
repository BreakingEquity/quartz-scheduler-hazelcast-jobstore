/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 */

package com.breakingequity.quarz.store.hazelcast;

import com.breakingequity.quarz.store.hazelcast.collections.TimeTriggerSet;
import com.breakingequity.quarz.store.hazelcast.wrappers.*;
import com.hazelcast.cluster.Member;
import com.hazelcast.cluster.MembershipEvent;
import com.hazelcast.core.HazelcastException;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleEvent;
import com.hazelcast.core.LifecycleListener;
import com.hazelcast.cp.lock.FencedLock;
import com.hazelcast.map.IMap;
import com.breakingequity.quarz.store.hazelcast.collections.InstanceHolder;
import org.quartz.Calendar;
import org.quartz.*;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.impl.matchers.StringMatcher;
import org.quartz.impl.triggers.SimpleTriggerImpl;
import org.quartz.spi.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * @author olegzinovev
 * @since 2019-01-15
 **/
public class HazelcastJobStore implements ClusteredJobStore {

    private final InstanceHolder instanceHolder;
    private final HazelcastInstance instance;

    private final JobFacade jobFacade;
    private final TriggerFacade triggerFacade;
    private final TimeTriggerSet timeTriggers;

    private final IMap<String, Calendar> calendarsByName;
    private long misfireThreshold = 60000L;

    private transient final FencedLock lock;

    private final WrapperFactory wrapperFactory;

    private long ftrCtr;
    private volatile SchedulerSignaler signaler;
    private final Logger logger;
    private volatile UUID nodeId;
    private long estimatedTimeToReleaseAndAcquireTrigger = 15L;
    private volatile LocalLockState localStateLock;
    private volatile boolean toolkitShutdown;
    private long retryInterval;
    private UUID membershipListenerId;
    private UUID shutdownListenerId;

    // This is a hack to prevent certain objects from ever being flushed. "this" should never be flushed (at least not
    // until the scheduler is shutdown) since it is referenced from the scheduler (which is not a shared object)
    // private transient Set<Object> hardRefs = new HashSet<Object>();

    public HazelcastJobStore(HazelcastInstance toolkit, String jobStoreName) {
        this(toolkit, new InstanceHolder(jobStoreName, toolkit), new DefaultWrapperFactory());
    }

    public HazelcastJobStore(HazelcastInstance toolkit, InstanceHolder instanceHolder, WrapperFactory wrapperFactory) {
        this.instance = toolkit;
        this.wrapperFactory = wrapperFactory;

        this.instanceHolder = instanceHolder;

        this.jobFacade = new JobFacade(instanceHolder);
        this.triggerFacade = new TriggerFacade(instanceHolder);

        this.timeTriggers = instanceHolder.getOrCreateTimeTriggerSet();
        this.calendarsByName = instanceHolder.getOrCreateCalendarWrapperMap();

        this.lock = instanceHolder.getLock();

        this.logger = LoggerFactory.getLogger(getClass());
    }

    private Logger getLog() {
        return logger;
    }

    private void disable() {
        toolkitShutdown = true;
        try {
            getLocalLockState().disableLocking();
        } catch (InterruptedException e) {
            getLog().error("failed to disable the job store", e);
        }

        if (membershipListenerId != null) {
            instance.getCluster().removeMembershipListener(membershipListenerId);
        }
        if (shutdownListenerId != null) {
            instance.getLifecycleService().removeLifecycleListener(shutdownListenerId);
        }
    }

    private LocalLockState getLocalLockState() {
        LocalLockState rv = localStateLock;
        if (rv != null) {
            return rv;
        }

        synchronized (HazelcastJobStore.class) {
            if (localStateLock == null) {
                localStateLock = new LocalLockState();
            }
            return localStateLock;
        }
    }

    private void lock() throws JobPersistenceException {
        getLocalLockState().attemptAcquireBegin();
        try {
            lock.lock();
        } catch (HazelcastException e) {
            getLocalLockState().release();
            throw e;
        }
    }

    private void unlock() {
        try {
            lock.unlock();
        } finally {
            getLocalLockState().release();
        }
    }

    /**
     * <p>
     * Called by the QuartzScheduler before the <code>JobStore</code> is used, in order to give the it a chance to
     * initialize.
     * </p>
     */

    @Override
    public void initialize(ClassLoadHelper loadHelper, SchedulerSignaler schedulerSignaler) {
        this.nodeId = instance.getCluster().getLocalMember().getUuid();
        this.ftrCtr = System.currentTimeMillis();

        this.signaler = schedulerSignaler;

        getLog().info(getClass().getSimpleName() + " initialized.");

        shutdownListenerId = instance.getLifecycleService().addLifecycleListener(new ShutdownHook(this));
    }

    @Override
    public void schedulerStarted() throws SchedulerException {
        membershipListenerId = instance.getCluster().addMembershipListener(this);

        Collection<Member> nodes = instance.getCluster().getMembers();

        Set<UUID> activeNodeIDs = new HashSet<>();
        for (Member node : nodes) {
            boolean added = activeNodeIDs.add(node.getUuid());
            if (!added) {
                getLog().error("DUPLICATE node ID detected: " + node);
            }
        }

        lock();
        try {
            List<TriggerWrapper> toEval = new ArrayList<>();

            // scan for orphaned triggers
            for (TriggerKey triggerKey : triggerFacade.allTriggerKeys()) {
                TriggerWrapper tw = triggerFacade.get(triggerKey);
                UUID lastHazelcastClientId = tw.getLastHazelcastClientId();
                if (lastHazelcastClientId == null) {
                    continue;
                }

                if (!activeNodeIDs.contains(lastHazelcastClientId) || tw.getState() == TriggerWrapper.TriggerState.ERROR) {
                    toEval.add(tw);
                }
            }

            for (TriggerWrapper tw : toEval) {
                evalOrphanedTrigger(tw, true);
            }

            // scan firedTriggers
            for (String key : triggerFacade.allFiredTriggers()) {
                FiredTrigger ft = triggerFacade.getFiredTrigger(key);
                if (ft == null) {
                    continue;
                }

                if (!activeNodeIDs.contains(ft.getClientId())) {
                    getLog().info("Found non-complete fired trigger: " + ft);
                    triggerFacade.removeFiredTrigger(key);

                    TriggerWrapper tw = triggerFacade.get(ft.getTriggerKey());
                    if (tw == null) {
                        getLog().error("no trigger found for executing trigger: " + ft.getTriggerKey());
                        continue;
                    }

                    scheduleRecoveryIfNeeded(tw, ft);
                }
            }
        } finally {
            unlock();
        }
    }

    @Override
    public void schedulerPaused() {
        // do nothing
    }

    @Override
    public void schedulerResumed() {
        // do nothing
    }

    private void evalOrphanedTrigger(TriggerWrapper tw, boolean newNode) {
        getLog().info("Evaluating orphaned trigger " + tw);

        JobWrapper jobWrapper = jobFacade.get(tw.getJobKey());

        if (jobWrapper == null) {
            getLog().error("No job found for orphaned trigger: " + tw);
            // even if it was deleted, there may be cleanup to do
            jobFacade.removeBlockedJob(tw.getJobKey());
            return;
        }

        if (newNode && tw.getState() == TriggerWrapper.TriggerState.ERROR) {
            tw.setState(TriggerWrapper.TriggerState.WAITING, nodeId, triggerFacade);
            timeTriggers.add(tw);
        }

        if (tw.getState() == TriggerWrapper.TriggerState.BLOCKED) {
            tw.setState(TriggerWrapper.TriggerState.WAITING, nodeId, triggerFacade);
            timeTriggers.add(tw);
        } else if (tw.getState() == TriggerWrapper.TriggerState.PAUSED_BLOCKED) {
            tw.setState(TriggerWrapper.TriggerState.PAUSED, nodeId, triggerFacade);
        }

        if (tw.getState() == TriggerWrapper.TriggerState.ACQUIRED) {
            tw.setState(TriggerWrapper.TriggerState.WAITING, nodeId, triggerFacade);
            timeTriggers.add(tw);
        }

        if (!tw.mayFireAgain() && !jobWrapper.requestsRecovery()) {
            try {
                removeTrigger(tw.getKey());
            } catch (JobPersistenceException e) {
                getLog().error("Can't remove completed trigger (and related job) " + tw, e);
            }
        }

        if (jobWrapper.isConcurrentExectionDisallowed()) {
            jobFacade.removeBlockedJob(jobWrapper.getKey());
            List<TriggerWrapper> triggersForJob = triggerFacade.getTriggerWrappersForJob(jobWrapper.getKey());

            for (TriggerWrapper trigger : triggersForJob) {
                if (trigger.getState() == TriggerWrapper.TriggerState.BLOCKED) {
                    trigger.setState(TriggerWrapper.TriggerState.WAITING, nodeId, triggerFacade);
                    timeTriggers.add(trigger);
                } else if (trigger.getState() == TriggerWrapper.TriggerState.PAUSED_BLOCKED) {
                    trigger.setState(TriggerWrapper.TriggerState.PAUSED, nodeId, triggerFacade);
                }
            }
        }
    }

    private void scheduleRecoveryIfNeeded(TriggerWrapper tw, FiredTrigger recovering) {
        JobWrapper jobWrapper = jobFacade.get(tw.getJobKey());

        if (jobWrapper == null) {
            getLog().error("No job found for orphaned trigger: " + tw);
            return;
        }

        if (jobWrapper.requestsRecovery()) {
            OperableTrigger recoveryTrigger = createRecoveryTrigger(tw, jobWrapper, "recover_" + nodeId + "_"
                    + ftrCtr++, recovering);

            JobDataMap jd = tw.getTriggerClone().getJobDataMap();
            jd.put(Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_NAME, tw.getKey().getName());
            jd.put(Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_GROUP, tw.getKey().getGroup());
            jd.put(Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_FIRETIME_IN_MILLISECONDS, String.valueOf(recovering.getFireTime()));
            jd.put(Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_SCHEDULED_FIRETIME_IN_MILLISECONDS, String.valueOf(recovering.getScheduledFireTime()));

            recoveryTrigger.setJobDataMap(jd);
            recoveryTrigger.computeFirstFireTime(null);

            try {
                storeTrigger(recoveryTrigger, false);
                if (!tw.mayFireAgain()) {
                    removeTrigger(tw.getKey());
                }
                getLog().info("Recovered job " + jobWrapper + " for trigger " + tw);
            } catch (JobPersistenceException e) {
                getLog().error("Can't recover job " + jobWrapper + " for trigger " + tw, e);
            }
        }
    }

    private OperableTrigger createRecoveryTrigger(TriggerWrapper tw, JobWrapper jw,
                                                  String name, FiredTrigger recovering) {
        //noinspection deprecation
        final SimpleTriggerImpl recoveryTrigger = new SimpleTriggerImpl(name, Scheduler.DEFAULT_RECOVERY_GROUP, new Date(recovering.getScheduledFireTime()));
        recoveryTrigger.setJobName(jw.getKey().getName());
        recoveryTrigger.setJobGroup(jw.getKey().getGroup());
        recoveryTrigger.setMisfireInstruction(SimpleTrigger.MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY);
        recoveryTrigger.setPriority(tw.getPriority());
        return recoveryTrigger;
    }

    long getMisfireThreshold() {
        return misfireThreshold;
    }

    /**
     * The number of milliseconds by which a trigger must have missed its next-fire-time, in order for it to be considered
     * "misfired" and thus have its misfire instruction applied.
     */
    @Override
    public void setMisfireThreshold(long misfireThreshold) {
        if (misfireThreshold < 1) {
            throw new IllegalArgumentException("Misfire threshold must be larger than 0");
        }
        this.misfireThreshold = misfireThreshold;
    }

    /**
     * <p>
     * Called by the QuartzScheduler to inform the <code>JobStore</code> that it should free up all of it's resources
     * because the scheduler is shutting down.
     * </p>
     */
    @Override
    public void shutdown() {
        // nothing to do
    }

    @Override
    public boolean supportsPersistence() {
        // We throw an assertion here since this method should never be called directly on this instance.
        throw new AssertionError();
    }

    /**
     * <p>
     * Store the given <code>{@link org.quartz.JobDetail}</code> and <code>{@link org.quartz.Trigger}</code>.
     * </p>
     *
     * @param newJob     The <code>JobDetail</code> to be stored.
     * @param newTrigger The <code>Trigger</code> to be stored.
     * @throws ObjectAlreadyExistsException if a <code>Job</code> with the same name/group already exists.
     */
    @Override
    public void storeJobAndTrigger(JobDetail newJob, OperableTrigger newTrigger) throws JobPersistenceException {
        lock();
        try {
            storeJob(newJob, false);
            storeTrigger(newTrigger, false);
        } catch (JobPersistenceException e) {
            throw e;
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot store job and trigger", e);
        } finally {
            unlock();
        }
    }

    /**
     * <p>
     * Store the given <code>{@link org.quartz.Job}</code>.
     * </p>
     *
     * @param newJob          The <code>Job</code> to be stored.
     * @param replaceExisting If <code>true</code>, any <code>Job</code> existing in the <code>JobStore</code> with the
     *                        same name & group should be over-written.
     * @throws ObjectAlreadyExistsException if a <code>Job</code> with the same name/group already exists, and
     *                                      replaceExisting is set to false.
     */
    @Override
    public void storeJob(JobDetail newJob, boolean replaceExisting) throws JobPersistenceException {
        JobDetail clone = (JobDetail) newJob.clone();

        lock();
        try {
            // wrapper construction must be done in lock since serializer is unlocked
            JobWrapper jw = wrapperFactory.createJobWrapper(clone);

            if (jobFacade.containsKey(jw.getKey())) {
                if (!replaceExisting) {
                    throw new ObjectAlreadyExistsException(newJob);
                }
            } else {
                // get job group
                Set<String> grpSet = instanceHolder.getOrCreateJobsGroupMap(newJob.getKey().getGroup());
                // add to jobs by group
                grpSet.add(jw.getKey().getName());

                if (!jobFacade.hasGroup(jw.getKey().getGroup())) {
                    jobFacade.addGroup(jw.getKey().getGroup());
                }
            }

            // add/update jobs FQN map
            jobFacade.put(jw.getKey(), jw);
        } catch (JobPersistenceException e) {
            throw e;
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot store job", e);
        } finally {
            unlock();
        }
    }

    /**
     * <p>
     * Remove (delete) the <code>{@link org.quartz.Job}</code> with the given name, and any
     * <code>{@link org.quartz.Trigger}</code> s that reference it.
     * </p>
     *
     * @param jobKey The key of the <code>Job</code> to be removed.
     * @return <code>true</code> if a <code>Job</code> with the given name & group was found and removed from the store.
     */
    @Override
    public boolean removeJob(JobKey jobKey) throws JobPersistenceException {
        boolean found = false;
        lock();
        try {
            List<OperableTrigger> trigger = getTriggersForJob(jobKey);
            for (OperableTrigger trig : trigger) {
                this.removeTrigger(trig.getKey());
                found = true;
            }

            found = (jobFacade.remove(jobKey) != null) | found;
            if (found) {
                Set<String> grpSet = instanceHolder.getOrCreateJobsGroupMap(jobKey.getGroup());
                grpSet.remove(jobKey.getName());
                if (grpSet.isEmpty()) {
                    instanceHolder.removeJobsGroupMap(jobKey.getGroup());
                    jobFacade.removeGroup(jobKey.getGroup());
                }
            }
        } catch (JobPersistenceException e) {
            throw e;
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot remove job", e);
        } finally {
            unlock();
        }

        return found;
    }

    @Override
    public boolean removeJobs(List<JobKey> jobKeys) throws JobPersistenceException {
        boolean allFound = true;

        lock();
        try {
            for (JobKey key : jobKeys) {
                allFound = removeJob(key) && allFound;
            }
        } catch (JobPersistenceException e) {
            throw e;
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot remove jobs", e);
        } finally {
            unlock();
        }

        return allFound;
    }

    @Override
    public boolean removeTriggers(List<TriggerKey> triggerKeys) throws JobPersistenceException {
        boolean allFound = true;

        lock();
        try {
            for (TriggerKey key : triggerKeys) {
                allFound = removeTrigger(key) && allFound;
            }
        } catch (JobPersistenceException e) {
            throw e;
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot remove triggers", e);
        } finally {
            unlock();
        }

        return allFound;
    }

    @Override
    public void storeJobsAndTriggers(Map<JobDetail, Set<? extends Trigger>> triggersAndJobs, boolean replace) throws JobPersistenceException {

        lock();
        try {
            // make sure there are no collisions...
            if (!replace) {
                for (JobDetail job : triggersAndJobs.keySet()) {
                    if (checkExists(job.getKey())) throw new ObjectAlreadyExistsException(job);
                    for (Trigger trigger : triggersAndJobs.get(job)) {
                        if (checkExists(trigger.getKey())) throw new ObjectAlreadyExistsException(trigger);
                    }
                }
            }
            // do bulk add...
            for (JobDetail job : triggersAndJobs.keySet()) {
                storeJob(job, true);
                for (Trigger trigger : triggersAndJobs.get(job)) {
                    storeTrigger((OperableTrigger) trigger, true);
                }
            }
        } catch (JobPersistenceException e) {
            throw e;
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot store jobs and triggers", e);
        } finally {
            unlock();
        }
    }

    /**
     * <p>
     * Store the given <code>{@link org.quartz.Trigger}</code>.
     * </p>
     *
     * @param newTrigger      The <code>Trigger</code> to be stored.
     * @param replaceExisting If <code>true</code>, any <code>Trigger</code> existing in the <code>JobStore</code> with
     *                        the same name & group should be over-written.
     * @throws ObjectAlreadyExistsException if a <code>Trigger</code> with the same name/group already exists, and
     *                                      replaceExisting is set to false.
     * @see #pauseTriggers(org.quartz.impl.matchers.GroupMatcher)
     */
    @Override
    public void storeTrigger(OperableTrigger newTrigger, boolean replaceExisting) throws JobPersistenceException {
        OperableTrigger clone = (OperableTrigger) newTrigger.clone();

        lock();
        try {
            JobDetail job = retrieveJob(newTrigger.getJobKey());
            if (job == null) {
                //
                throw new JobPersistenceException("The job (" + newTrigger.getJobKey()
                        + ") referenced by the trigger does not exist.");
            }

            // wrapper construction must be done in lock since serializer is unlocked
            TriggerWrapper tw = wrapperFactory.createTriggerWrapper(clone, job.isConcurrentExectionDisallowed());

            if (triggerFacade.containsKey(tw.getKey())) {
                if (!replaceExisting) {
                    throw new ObjectAlreadyExistsException(newTrigger);
                }

                removeTrigger(newTrigger.getKey(), false);
            }

            // add to triggers by group
            Set<String> grpSet = instanceHolder.getOrCreateTriggersGroupMap(newTrigger.getKey().getGroup());
            grpSet.add(newTrigger.getKey().getName());
            if (!triggerFacade.hasGroup(newTrigger.getKey().getGroup())) {
                triggerFacade.addGroup(newTrigger.getKey().getGroup());
            }

            if (triggerFacade.pausedGroupsContain(newTrigger.getKey().getGroup())
                    || jobFacade.pausedGroupsContain(newTrigger.getJobKey().getGroup())) {
                tw.setState(TriggerWrapper.TriggerState.PAUSED, nodeId, triggerFacade);
                if (jobFacade.blockedJobsContain(tw.getJobKey())) {
                    tw.setState(TriggerWrapper.TriggerState.PAUSED_BLOCKED, nodeId, triggerFacade);
                }
            } else if (jobFacade.blockedJobsContain(tw.getJobKey())) {
                tw.setState(TriggerWrapper.TriggerState.BLOCKED, nodeId, triggerFacade);
            } else {
                timeTriggers.add(tw);
            }

            // add to triggers by FQN map
            triggerFacade.put(tw.getKey(), tw);
        } catch (JobPersistenceException e) {
            throw e;
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot store trigger", e);
        } finally {
            unlock();
        }
    }

    /**
     * <p>
     * Remove (delete) the <code>{@link org.quartz.Trigger}</code> with the given name.
     * </p>
     *
     * @param triggerKey The key of the <code>Trigger</code> to be removed.
     * @return <code>true</code> if a <code>Trigger</code> with the given name & group was found and removed from the
     * store.
     */
    @Override
    public boolean removeTrigger(TriggerKey triggerKey) throws JobPersistenceException {
        return removeTrigger(triggerKey, true);
    }

    private boolean removeTrigger(TriggerKey triggerKey, boolean removeOrphanedJob) throws JobPersistenceException {

        lock();
        TriggerWrapper tw;
        try {
            // remove from triggers by FQN map
            tw = triggerFacade.remove(triggerKey);

            if (tw != null) {
                // remove from triggers by group
                Set<String> grpSet = instanceHolder.getOrCreateTriggersGroupMap(triggerKey.getGroup());
                grpSet.remove(triggerKey.getName());
                if (grpSet.size() == 0) {
                    instanceHolder.removeTriggersGroupMap(triggerKey.getGroup());
                    triggerFacade.removeGroup(triggerKey.getGroup());
                }
                // remove from triggers array
                timeTriggers.remove(tw);

                if (removeOrphanedJob) {
                    JobWrapper jw = jobFacade.get(tw.getJobKey());
                    List<OperableTrigger> trigs = getTriggersForJob(tw.getJobKey());
                    if ((trigs == null || trigs.size() == 0) && !jw.isDurable()) {
                        JobKey jobKey = tw.getJobKey();
                        if (removeJob(jobKey)) {
                            signaler.notifySchedulerListenersJobDeleted(jobKey);
                        }
                    }
                }
            }
        } catch (JobPersistenceException e) {
            throw e;
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot remove trigger", e);
        } finally {
            unlock();
        }

        return tw != null;
    }

    /**
     * @see org.quartz.spi.JobStore#replaceTrigger
     */
    @Override
    public boolean replaceTrigger(TriggerKey triggerKey, OperableTrigger newTrigger) throws JobPersistenceException {
        boolean found;

        lock();
        try {
            // remove from triggers by FQN map
            TriggerWrapper tw = triggerFacade.remove(triggerKey);
            found = tw != null;

            if (tw != null) {
                if (!tw.getJobKey().equals(newTrigger.getJobKey())) {
                    throw new JobPersistenceException(
                            "New trigger is not related to the same job as the old trigger.");
                }
                // remove from triggers by group
                Set<String> grpSet = instanceHolder.getOrCreateTriggersGroupMap(triggerKey.getGroup());
                grpSet.remove(triggerKey.getName());
                if (grpSet.size() == 0) {
                    instanceHolder.removeTriggersGroupMap(triggerKey.getGroup());
                    triggerFacade.removeGroup(triggerKey.getGroup());
                }
                timeTriggers.remove(tw);

                try {
                    storeTrigger(newTrigger, false);
                } catch (JobPersistenceException jpe) {
                    storeTrigger(tw.getTriggerClone(), false); // put previous trigger back...
                    throw jpe;
                }
            }
        } catch (JobPersistenceException e) {
            throw e;
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot replace trigger", e);
        } finally {
            unlock();
        }

        return found;
    }

    /**
     * <p>
     * Retrieve the <code>{@link org.quartz.JobDetail}</code> for the given <code>{@link org.quartz.Job}</code>.
     * </p>
     *
     * @param jobKey The key of the <code>Job</code> to be retrieved.
     * @return The desired <code>Job</code>, or null if there is no match.
     */
    @Override
    public JobDetail retrieveJob(JobKey jobKey) throws JobPersistenceException {
        JobWrapper jobWrapper = getJob(jobKey);
        return jobWrapper == null ? null : jobWrapper.getJobDetailClone();
    }

    private JobWrapper getJob(final JobKey key) throws JobPersistenceException {
        lock();
        try {
            return jobFacade.get(key);
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot store job and trigger", e);
        } finally {
            unlock();
        }
    }

    /**
     * <p>
     * Retrieve the given <code>{@link org.quartz.Trigger}</code>.
     * </p>
     *
     * @param triggerKey The key of the <code>Trigger</code> to be retrieved.
     * @return The desired <code>Trigger</code>, or null if there is no match.
     */
    @Override
    public OperableTrigger retrieveTrigger(TriggerKey triggerKey) throws JobPersistenceException {
        lock();
        try {
            TriggerWrapper tw = triggerFacade.get(triggerKey);
            return (tw != null) ? tw.getTriggerClone() : null;
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot retrieve trigger", e);
        } finally {
            unlock();
        }
    }

    @Override
    public boolean checkExists(final JobKey jobKey) {
        return jobFacade.containsKey(jobKey);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean checkExists(final TriggerKey triggerKey) {
        return triggerFacade.containsKey(triggerKey);
    }

    @Override
    public void clearAllSchedulingData() throws JobPersistenceException {
        lock();
        try {
            // unschedule jobs (delete triggers)
            List<String> lst = getTriggerGroupNames();
            for (String group : lst) {
                Set<TriggerKey> keys = getTriggerKeys(GroupMatcher.triggerGroupEquals(group));
                for (TriggerKey key : keys) {
                    removeTrigger(key);
                }
            }
            // delete jobs
            lst = getJobGroupNames();
            for (String group : lst) {
                Set<JobKey> keys = getJobKeys(GroupMatcher.jobGroupEquals(group));
                for (JobKey key : keys) {
                    removeJob(key);
                }
            }
            // delete calendars
            lst = getCalendarNames();
            for (String name : lst) {
                removeCalendar(name);
            }
        } catch (JobPersistenceException e) {
            throw e;
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot clear all scheduling data", e);
        } finally {
            unlock();
        }
    }

    /**
     * <p>
     * Get the current state of the identified <code>{@link Trigger}</code>.
     * </p>
     *
     * @see Trigger.TriggerState
     */
    @Override
    public Trigger.TriggerState getTriggerState(org.quartz.TriggerKey key) throws JobPersistenceException {

        TriggerWrapper tw;
        lock();
        try {
            tw = triggerFacade.get(key);
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot get trigger state", e);
        } finally {
            unlock();
        }

        if (tw == null) {
            return Trigger.TriggerState.NONE;
        }

        if (tw.getState() == TriggerWrapper.TriggerState.COMPLETE) {
            return Trigger.TriggerState.COMPLETE;
        }

        if (tw.getState() == TriggerWrapper.TriggerState.PAUSED) {
            return Trigger.TriggerState.PAUSED;
        }

        if (tw.getState() == TriggerWrapper.TriggerState.PAUSED_BLOCKED) {
            return Trigger.TriggerState.PAUSED;
        }

        if (tw.getState() == TriggerWrapper.TriggerState.BLOCKED) {
            return Trigger.TriggerState.BLOCKED;
        }

        if (tw.getState() == TriggerWrapper.TriggerState.ERROR) {
            return Trigger.TriggerState.ERROR;
        }

        return Trigger.TriggerState.NORMAL;
    }

    @Override
    public void resetTriggerFromErrorState(final TriggerKey triggerKey) {

        TriggerWrapper tw = triggerFacade.get(triggerKey);
        // was the trigger deleted since being acquired?
        if (tw == null) {
            return;
        }
        // was the trigger completed, paused, blocked, etc. since being acquired?
        if (tw.getState() != TriggerWrapper.TriggerState.ERROR) {
            return;
        }

        if (triggerFacade.pausedGroupsContain(triggerKey.getGroup())) {
            tw.setState(TriggerWrapper.TriggerState.PAUSED, nodeId, triggerFacade);
        } else {
            tw.setState(TriggerWrapper.TriggerState.WAITING, nodeId, triggerFacade);
            timeTriggers.add(tw);
        }
    }


    /**
     * <p>
     * Store the given <code>{@link org.quartz.Calendar}</code>.
     * </p>
     *
     * @param calendar        The <code>Calendar</code> to be stored.
     * @param replaceExisting If <code>true</code>, any <code>Calendar</code> existing in the <code>JobStore</code> with
     *                        the same name & group should be over-written.
     * @param updateTriggers  If <code>true</code>, any <code>Trigger</code>s existing in the <code>JobStore</code> that
     *                        reference an existing Calendar with the same name with have their next fire time re-computed with the new
     *                        <code>Calendar</code>.
     * @throws ObjectAlreadyExistsException if a <code>Calendar</code> with the same name already exists, and
     *                                      replaceExisting is set to false.
     */
    @Override
    public void storeCalendar(String name, Calendar calendar, boolean replaceExisting, boolean updateTriggers) throws JobPersistenceException {

        Calendar clone = (Calendar) calendar.clone();

        lock();
        try {
            Calendar cal = calendarsByName.get(name);

            if (cal != null && !replaceExisting) {
                throw new ObjectAlreadyExistsException("Calendar with name '" + name + "' already exists.");
            } else if (cal != null) {
                calendarsByName.remove(name);
            }

            calendarsByName.put(name, clone);

            if (cal != null && updateTriggers) {
                for (TriggerWrapper tw : triggerFacade.getTriggerWrappersForCalendar(name)) {
                    boolean removed = timeTriggers.remove(tw);

                    tw.updateWithNewCalendar(clone, getMisfireThreshold(), triggerFacade);

                    if (removed) {
                        timeTriggers.add(tw);
                    }
                }
            }
        } catch (JobPersistenceException e) {
            throw e;
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot store calendar", e);
        } finally {
            unlock();
        }
    }

    /**
     * <p>
     * Remove (delete) the <code>{@link org.quartz.Calendar}</code> with the given name.
     * </p>
     * <p>
     * If removal of the <code>Calendar</code> would result in <code.Trigger</code>s pointing to non-existent calendars,
     * then a <code>JobPersistenceException</code> will be thrown.
     * </p>
     * *
     *
     * @param calName The name of the <code>Calendar</code> to be removed.
     * @return <code>true</code> if a <code>Calendar</code> with the given name was found and removed from the store.
     */
    @Override
    public boolean removeCalendar(String calName) throws JobPersistenceException {
        int numRefs = 0;

        lock();
        try {
            for (TriggerKey triggerKey : triggerFacade.allTriggerKeys()) {
                TriggerWrapper tw = triggerFacade.get(triggerKey);
                if (tw.getCalendarName() != null && tw.getCalendarName().equals(calName)) {
                    numRefs++;
                }
            }

            if (numRefs > 0) {
                throw new JobPersistenceException("Calender cannot be removed if it referenced by a Trigger!");
            }

            return (calendarsByName.remove(calName) != null);
        } catch (JobPersistenceException e) {
            throw e;
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot remove calendar", e);
        } finally {
            unlock();
        }
    }

    /**
     * <p>
     * Retrieve the given <code>{@link org.quartz.Trigger}</code>.
     * </p>
     *
     * @param calName The name of the <code>Calendar</code> to be retrieved.
     * @return The desired <code>Calendar</code>, or null if there is no match.
     */
    @Override
    public Calendar retrieveCalendar(String calName) throws JobPersistenceException {
        lock();
        try {
            Calendar cw = calendarsByName.get(calName);
            return (Calendar) (cw == null ? null : cw.clone());
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot retrieve calendar", e);
        } finally {
            unlock();
        }
    }

    /**
     * <p>
     * Get the number of <code>{@link org.quartz.JobDetail}</code> s that are stored in the <code>JobsStore</code>.
     * </p>
     */
    @Override
    public int getNumberOfJobs() throws JobPersistenceException {
        lock();
        try {
            return jobFacade.numberOfJobs();
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot get number of jobs", e);
        } finally {
            unlock();
        }
    }

    /**
     * <p>
     * Get the number of <code>{@link org.quartz.Trigger}</code> s that are stored in the <code>JobsStore</code>.
     * </p>
     */
    @Override
    public int getNumberOfTriggers() throws JobPersistenceException {
        lock();
        try {
            return triggerFacade.numberOfTriggers();
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot get number of triggers", e);
        } finally {
            unlock();
        }
    }

    /**
     * <p>
     * Get the number of <code>{@link org.quartz.Calendar}</code> s that are stored in the <code>JobsStore</code>.
     * </p>
     */
    @Override
    public int getNumberOfCalendars() throws JobPersistenceException {
        lock();
        try {
            return calendarsByName.size();
        } catch (Exception e) {
            throw new JobPersistenceException("get number of calendars", e);
        } finally {
            unlock();
        }
    }

    /**
     * <p>
     * Get the names of all of the <code>{@link org.quartz.Job}</code> s that have the given group name.
     * </p>
     */
    @Override
    public Set<JobKey> getJobKeys(GroupMatcher<JobKey> matcher) throws JobPersistenceException {
        lock();
        try {
            Set<String> matchingGroups = new HashSet<>();
            if (matcher.getCompareWithOperator() == StringMatcher.StringOperatorName.EQUALS) {
                matchingGroups.add(matcher.getCompareToValue());
            } else {
                for (String group : jobFacade.getAllGroupNames()) {
                    if (matcher.getCompareWithOperator().evaluate(group, matcher.getCompareToValue())) {
                        matchingGroups.add(group);
                    }
                }
            }

            Set<JobKey> out = new HashSet<>();
            for (String matchingGroup : matchingGroups) {

                Set<String> grpJobNames = instanceHolder.getOrCreateJobsGroupMap(matchingGroup);
                for (String jobName : grpJobNames) {
                    JobKey jobKey = new JobKey(jobName, matchingGroup);
                    if (jobFacade.containsKey(jobKey)) {
                        out.add(jobKey);
                    }
                }
            }

            return out;
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot get job keys", e);
        } finally {
            unlock();
        }
    }

    /**
     * <p>
     * Get the names of all of the <code>{@link org.quartz.Calendar}</code> s in the <code>JobStore</code>.
     * </p>
     * <p>
     * If there are no Calendars in the given group name, the result should be a zero-length array (not <code>null</code>
     * ).
     * </p>
     */
    @Override
    public List<String> getCalendarNames() throws JobPersistenceException {
        lock();
        try {
            Set<String> names = calendarsByName.keySet();
            return new ArrayList<>(names);
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot get calendar names", e);
        } finally {
            unlock();
        }
    }

    /**
     * <p>
     * Get the names of all of the <code>{@link org.quartz.Trigger}</code> s that have the given group name.
     * </p>
     */
    @Override
    public Set<TriggerKey> getTriggerKeys(GroupMatcher<TriggerKey> matcher) throws JobPersistenceException {
        lock();
        try {
            Set<String> groupNames = new HashSet<>();
            if (matcher.getCompareWithOperator() == StringMatcher.StringOperatorName.EQUALS) {
                groupNames.add(matcher.getCompareToValue());
            } else {
                for (String group : triggerFacade.allTriggersGroupNames()) {
                    if (matcher.getCompareWithOperator().evaluate(group, matcher.getCompareToValue())) {
                        groupNames.add(group);
                    }
                }
            }

            Set<TriggerKey> out = new HashSet<>();

            for (String groupName : groupNames) {
                Set<String> grpSet = instanceHolder.getOrCreateTriggersGroupMap(groupName);

                for (String key : grpSet) {
                    TriggerKey triggerKey = new TriggerKey(key, groupName);
                    TriggerWrapper tw = triggerFacade.get(triggerKey);
                    if (tw != null) {
                        out.add(triggerKey);
                    }
                }
            }

            return out;
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot get trigger keys", e);
        } finally {
            unlock();
        }
    }

    /**
     * <p>
     * Get the names of all of the <code>{@link org.quartz.Job}</code> groups.
     * </p>
     */
    @Override
    public List<String> getJobGroupNames() throws JobPersistenceException {
        lock();
        try {
            return new ArrayList<>(jobFacade.getAllGroupNames());
        } finally {
            unlock();
        }
    }

    /**
     * <p>
     * Get the names of all of the <code>{@link org.quartz.Trigger}</code> groups.
     * </p>
     */
    @Override
    public List<String> getTriggerGroupNames() throws JobPersistenceException {
        lock();
        try {
            return new ArrayList<>(triggerFacade.allTriggersGroupNames());
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot get trigger groups", e);
        } finally {
            unlock();
        }
    }

    /**
     * <p>
     * Get all of the Triggers that are associated to the given Job.
     * </p>
     * <p>
     * If there are no matches, a zero-length array should be returned.
     * </p>
     */
    @Override
    public List<OperableTrigger> getTriggersForJob(final JobKey jobKey) throws JobPersistenceException {
        List<OperableTrigger> trigList = new ArrayList<>();

        lock();
        try {
            for (TriggerKey triggerKey : triggerFacade.allTriggerKeys()) {
                TriggerWrapper tw = triggerFacade.get(triggerKey);
                if (tw.getJobKey().equals(jobKey)) {
                    trigList.add(tw.getTriggerClone());
                }
            }
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot get triggers for job", e);
        } finally {
            unlock();
        }

        return trigList;
    }

    /**
     * <p>
     * Pause the <code>{@link Trigger}</code> with the given name.
     * </p>
     */
    @Override
    public void pauseTrigger(TriggerKey triggerKey) throws JobPersistenceException {
        lock();
        try {
            TriggerWrapper tw = triggerFacade.get(triggerKey);

            // does the trigger exist?
            if (tw == null) {
                return;
            }

            // if the trigger is "complete" pausing it does not make sense...
            if (tw.getState() == TriggerWrapper.TriggerState.COMPLETE) {
                return;
            }

            if (tw.getState() == TriggerWrapper.TriggerState.BLOCKED) {
                tw.setState(TriggerWrapper.TriggerState.PAUSED_BLOCKED, nodeId, triggerFacade);
            } else {
                tw.setState(TriggerWrapper.TriggerState.PAUSED, nodeId, triggerFacade);
            }

            timeTriggers.remove(tw);
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot pause trigger", e);
        } finally {
            unlock();
        }
    }

    /**
     * <p>
     * Pause all of the <code>{@link Trigger}s</code> in the given group.
     * </p>
     * <p>
     * The JobStore should "remember" that the group is paused, and impose the pause on any new triggers that are added to
     * the group while the group is paused.
     * </p>
     */
    @Override
    public Collection<String> pauseTriggers(GroupMatcher<TriggerKey> matcher) throws JobPersistenceException {
        HashSet<String> pausedGroups = new HashSet<>();
        lock();
        try {
            Set<TriggerKey> triggerKeys = getTriggerKeys(matcher);
            for (TriggerKey key : triggerKeys) {
                triggerFacade.addPausedGroup(key.getGroup());
                pausedGroups.add(key.getGroup());
                pauseTrigger(key);
            }
            // make sure to account for an exact group match for a group that doesn't yet exist
            StringMatcher.StringOperatorName operator = matcher.getCompareWithOperator();
            if (operator.equals(StringMatcher.StringOperatorName.EQUALS)) {
                triggerFacade.addPausedGroup(matcher.getCompareToValue());
                pausedGroups.add(matcher.getCompareToValue());
            }
        } catch (JobPersistenceException e) {
            throw e;
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot pause triggers", e);
        } finally {
            unlock();
        }
        return pausedGroups;
    }

    /**
     * <p>
     * Pause the <code>{@link org.quartz.JobDetail}</code> with the given name - by pausing all of its current
     * <code>Trigger</code>s.
     * </p>
     */
    @Override
    public void pauseJob(JobKey jobKey) throws JobPersistenceException {
        lock();
        try {
            for (OperableTrigger trigger : getTriggersForJob(jobKey)) {
                pauseTrigger(trigger.getKey());
            }
        } finally {
            unlock();
        }
    }

    /**
     * <p>
     * Pause all of the <code>{@link org.quartz.JobDetail}s</code> in the given group - by pausing all of their
     * <code>Trigger</code>s.
     * </p>
     * <p>
     * The JobStore should "remember" that the group is paused, and impose the pause on any new jobs that are added to the
     * group while the group is paused.
     * </p>
     */
    @Override
    public Collection<String> pauseJobs(GroupMatcher<JobKey> matcher) throws JobPersistenceException {
        Collection<String> pausedGroups = new HashSet<>();
        lock();
        try {

            Set<JobKey> jobKeys = getJobKeys(matcher);

            for (JobKey jobKey : jobKeys) {
                for (OperableTrigger trigger : getTriggersForJob(jobKey)) {
                    pauseTrigger(trigger.getKey());
                }
                pausedGroups.add(jobKey.getGroup());
            }
            // make sure to account for an exact group match for a group that doesn't yet exist
            StringMatcher.StringOperatorName operator = matcher.getCompareWithOperator();
            if (operator.equals(StringMatcher.StringOperatorName.EQUALS)) {
                jobFacade.addPausedGroup(matcher.getCompareToValue());
                pausedGroups.add(matcher.getCompareToValue());
            }
        } catch (JobPersistenceException e) {
            throw e;
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot pause jobs", e);
        } finally {
            unlock();
        }
        return pausedGroups;
    }

    /**
     * <p>
     * Resume (un-pause) the <code>{@link Trigger}</code> with the given name.
     * </p>
     * <p>
     * If the <code>Trigger</code> missed one or more fire-times, then the <code>Trigger</code>'s misfire instruction will
     * be applied.
     * </p>
     */
    @Override
    public void resumeTrigger(TriggerKey triggerKey) throws JobPersistenceException {
        lock();
        try {
            TriggerWrapper tw = triggerFacade.get(triggerKey);

            // does the trigger exist?
            if (tw == null) {
                return;
            }

            // if the trigger is not paused resuming it does not make sense...
            if (tw.getState() != TriggerWrapper.TriggerState.PAUSED && tw.getState() != TriggerWrapper.TriggerState.PAUSED_BLOCKED) {
                return;
            }

            if (jobFacade.blockedJobsContain(tw.getJobKey())) {
                tw.setState(TriggerWrapper.TriggerState.BLOCKED, nodeId, triggerFacade);
            } else {
                tw.setState(TriggerWrapper.TriggerState.WAITING, nodeId, triggerFacade);
            }

            applyMisfire(tw);

            if (tw.getState() == TriggerWrapper.TriggerState.WAITING) {
                timeTriggers.add(tw);
            }
        } catch (JobPersistenceException e) {
            throw e;
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot resume trigger", e);
        } finally {
            unlock();
        }
    }

    /**
     * <p>
     * Resume (un-pause) all of the <code>{@link Trigger}s</code> in the given group.
     * </p>
     * <p>
     * If any <code>Trigger</code> missed one or more fire-times, then the <code>Trigger</code>'s misfire instruction will
     * be applied.
     * </p>
     */
    @Override
    public Collection<String> resumeTriggers(GroupMatcher<TriggerKey> matcher) throws JobPersistenceException {
        Collection<String> groups = new HashSet<>();
        lock();
        try {
            Set<TriggerKey> triggerKeys = getTriggerKeys(matcher);

            for (TriggerKey triggerKey : triggerKeys) {
                TriggerWrapper tw = triggerFacade.get(triggerKey);
                if (tw != null) {
                    String jobGroup = tw.getJobKey().getGroup();

                    if (jobFacade.pausedGroupsContain(jobGroup)) {
                        continue;
                    }
                    groups.add(triggerKey.getGroup());
                }
                resumeTrigger(triggerKey);
            }
            triggerFacade.removeAllPausedGroups(groups);
        } catch (JobPersistenceException e) {
            throw e;
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot resume triggers", e);
        } finally {
            unlock();
        }
        return groups;
    }

    /**
     * <p>
     * Resume (un-pause) the <code>{@link org.quartz.JobDetail}</code> with the given name.
     * </p>
     * <p>
     * If any of the <code>Job</code>'s<code>Trigger</code> s missed one or more fire-times, then the <code>Trigger</code>
     * 's misfire instruction will be applied.
     * </p>
     */
    @Override
    public void resumeJob(JobKey jobKey) throws JobPersistenceException {

        lock();
        try {
            for (OperableTrigger trigger : getTriggersForJob(jobKey)) {
                resumeTrigger(trigger.getKey());
            }
        } catch (JobPersistenceException e) {
            throw e;
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot resume job", e);
        } finally {
            unlock();
        }
    }

    /**
     * <p>
     * Resume (un-pause) all of the <code>{@link org.quartz.JobDetail}s</code> in the given group.
     * </p>
     * <p>
     * If any of the <code>Job</code> s had <code>Trigger</code> s that missed one or more fire-times, then the
     * <code>Trigger</code>'s misfire instruction will be applied.
     * </p>
     */
    @Override
    public Collection<String> resumeJobs(GroupMatcher<JobKey> matcher) throws JobPersistenceException {
        Collection<String> groups = new HashSet<>();
        lock();
        try {
            Set<JobKey> jobKeys = getJobKeys(matcher);

            for (JobKey jobKey : jobKeys) {
                if (groups.add(jobKey.getGroup())) {
                    jobFacade.removePausedJobGroup(jobKey.getGroup());
                }
                for (OperableTrigger trigger : getTriggersForJob(jobKey)) {
                    resumeTrigger(trigger.getKey());
                }
            }
        } catch (JobPersistenceException e) {
            throw e;
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot resume jobs", e);
        } finally {
            unlock();
        }
        return groups;
    }

    /**
     * <p>
     * Pause all triggers - equivalent of calling <code>pauseTriggerGroup(group)</code> on every group.
     * </p>
     * <p>
     * When <code>resumeAll()</code> is called (to un-pause), trigger misfire instructions WILL be applied.
     * </p>
     *
     * @see #resumeAll()
     * @see #pauseTriggers(org.quartz.impl.matchers.GroupMatcher)
     */
    @Override
    public void pauseAll() throws JobPersistenceException {

        lock();
        try {
            List<String> names = getTriggerGroupNames();

            for (String name : names) {
                pauseTriggers(GroupMatcher.triggerGroupEquals(name));
            }
        } finally {
            unlock();
        }
    }

    /**
     * <p>
     * Resume (un-pause) all triggers - equivalent of calling <code>resumeTriggerGroup(group)</code> on every group.
     * </p>
     * <p>
     * If any <code>Trigger</code> missed one or more fire-times, then the <code>Trigger</code>'s misfire instruction will
     * be applied.
     * </p>
     *
     * @see #pauseAll()
     */
    @Override
    public void resumeAll() throws JobPersistenceException {

        lock();
        try {
            jobFacade.clearPausedJobGroups();
            List<String> names = getTriggerGroupNames();


            for (String name : names) {
                resumeTriggers(GroupMatcher.triggerGroupEquals(name));
            }

            // resume empty paused trigger groups
            triggerFacade.removeAllPausedGroups(getPausedTriggerGroups());
        } catch (JobPersistenceException e) {
            throw e;
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot resume all jobs", e);
        } finally {
            unlock();
        }
    }

    private boolean applyMisfire(TriggerWrapper tw) throws JobPersistenceException {
        long misfireTime = System.currentTimeMillis();
        if (getMisfireThreshold() > 0) {
            misfireTime -= getMisfireThreshold();
        }

        Date tnft = tw.getNextFireTime();
        if (tnft == null || tnft.getTime() > misfireTime
                || tw.getMisfireInstruction() == Trigger.MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY) {
            return false;
        }

        Calendar cal = null;
        if (tw.getCalendarName() != null) {
            cal = retrieveCalendar(tw.getCalendarName());
        }

        signaler.notifyTriggerListenersMisfired(tw.getTriggerClone());

        tw.updateAfterMisfire(cal, triggerFacade);

        if (tw.getNextFireTime() == null) {
            tw.setState(TriggerWrapper.TriggerState.COMPLETE, nodeId, triggerFacade);
            signaler.notifySchedulerListenersFinalized(tw.getTriggerClone());
            timeTriggers.remove(tw);
        } else {
            return !tnft.equals(tw.getNextFireTime());
        }

        return true;
    }

    @Override
    public List<OperableTrigger> acquireNextTriggers(long noLaterThan, int maxCount, long timeWindow)
            throws JobPersistenceException {
        List<OperableTrigger> result = new ArrayList<>();

        lock();
        try {
            for (TriggerWrapper tw : getNextTriggerWrappers(timeTriggers, noLaterThan, maxCount, timeWindow)) {
                result.add(markAndCloneTrigger(tw));
            }
            return result;
        } finally {
            try {
                unlock();
            } catch (HazelcastException e) {
                if (!validateAcquired(result)) {
                    //noinspection ThrowFromFinallyBlock
                    throw e;
                }
            }
        }
    }

    private boolean validateAcquired(List<OperableTrigger> result) {
        if (result.isEmpty()) {
            return false;
        } else {
            while (!toolkitShutdown) {
                try {
                    lock();
                    try {
                        for (OperableTrigger ot : result) {
                            TriggerWrapper tw = triggerFacade.get(ot.getKey());
                            if (!ot.getFireInstanceId().equals(tw.getTriggerClone().getFireInstanceId()) || !TriggerWrapper.TriggerState.ACQUIRED.equals(tw.getState())) {
                                return false;
                            }
                        }
                        return true;
                    } finally {
                        unlock();
                    }
                } catch (JobPersistenceException | HazelcastException e) {
                    try {
                        Thread.sleep(retryInterval);
                    } catch (InterruptedException f) {
                        throw new IllegalStateException("Received interrupted exception", f);
                    }
                }
            }
            throw new IllegalStateException("Scheduler has been shutdown");
        }
    }

    private OperableTrigger markAndCloneTrigger(final TriggerWrapper tw) {
        tw.setState(TriggerWrapper.TriggerState.ACQUIRED, nodeId, triggerFacade);

        String firedInstanceId = nodeId + "-" + ftrCtr++;
        tw.setFireInstanceId(firedInstanceId, triggerFacade);

        return tw.getTriggerClone();
    }

    private List<TriggerWrapper> getNextTriggerWrappers(final TimeTriggerSet source, final long noLaterThan, final int maxCount,
                                                        final long timeWindow) throws JobPersistenceException {

        List<TriggerWrapper> wrappers = new ArrayList<>();
        Set<JobKey> acquiredJobKeysForNoConcurrentExec = new HashSet<>();
        Set<TriggerWrapper> excludedTriggers = new HashSet<>();
        JobPersistenceException caughtJpe = null;
        long batchEnd = noLaterThan;

        try {
            while (true) {
                TriggerWrapper tw = null;

                try {
                    TriggerKey triggerKey = source.removeFirst();
                    if (triggerKey != null) {
                        tw = triggerFacade.get(triggerKey);
                    }
                    if (tw == null) break;
                } catch (java.util.NoSuchElementException nsee) {
                    break;
                }

                if (tw.getNextFireTime() == null) {
                    continue;
                }

                if (applyMisfire(tw)) {
                    if (tw.getNextFireTime() != null) {
                        source.add(tw);
                    }
                    continue;
                }

                if (tw.getNextFireTime().getTime() > batchEnd) {
                    source.add(tw);
                    break;
                }
                if (tw.jobDisallowsConcurrence()) {
                    if (acquiredJobKeysForNoConcurrentExec.contains(tw.getJobKey())) {
                        excludedTriggers.add(tw);
                        continue;
                    }
                    acquiredJobKeysForNoConcurrentExec.add(tw.getJobKey());
                }

                if (wrappers.isEmpty()) {
                    batchEnd = Math.max(tw.getNextFireTime().getTime(), System.currentTimeMillis()) + timeWindow;
                }

                wrappers.add(tw);
                if (wrappers.size() == maxCount) {
                    break;
                }
            }
        } catch (JobPersistenceException jpe) {
            caughtJpe = jpe; // hold the exception while we patch back up the collection ...
        }

        // If we did excluded triggers to prevent ACQUIRE state due to DisallowConcurrentExecution, we need to add them back
        // to store.
        if (excludedTriggers.size() > 0) {
            for (TriggerWrapper tw : excludedTriggers) {
                source.add(tw);
            }
        }

        // if we held and exception, now we need to put back all the TriggerWrappers that we may have removed from the
        // source set
        if (caughtJpe != null) {
            for (TriggerWrapper tw : wrappers) {
                source.add(tw);
            }
            // and now throw the exception...
            throw new JobPersistenceException("Exception encountered while trying to select triggers for firing.", caughtJpe);
        }

        return wrappers;
    }

    /**
     * <p>
     * Inform the <code>JobStore</code> that the scheduler no longer plans to fire the given <code>Trigger</code>, that it
     * had previously acquired (reserved).
     * </p>
     */
    @Override
    public void releaseAcquiredTrigger(OperableTrigger trigger) {
        while (!toolkitShutdown) {
            try {
                lock();
                try {
                    TriggerWrapper tw = triggerFacade.get(trigger.getKey());
                    if (tw != null
                            && Objects.equals(trigger.getFireInstanceId(), tw.getTriggerClone().getFireInstanceId())
                            && tw.getState() == TriggerWrapper.TriggerState.ACQUIRED) {
                        tw.setState(TriggerWrapper.TriggerState.WAITING, nodeId, triggerFacade);
                        timeTriggers.add(tw);
                    }
                } finally {
                    unlock();
                }
            } catch (HazelcastException | JobPersistenceException e) {
                try {
                    Thread.sleep(retryInterval);
                } catch (InterruptedException f) {
                    throw new IllegalStateException("Received interrupted exception", f);
                }
                continue;
            }
            break;
        }
    }

    /**
     * <p>
     * Inform the <code>JobStore</code> that the scheduler is now firing the given <code>Trigger</code> (executing its
     * associated <code>Job</code>), that it had previously acquired (reserved).
     * </p>
     */
    @Override
    public List<TriggerFiredResult> triggersFired(List<OperableTrigger> triggersFired) throws JobPersistenceException {

        List<TriggerFiredResult> results = new ArrayList<>();
        lock();
        try {
            for (OperableTrigger trigger : triggersFired) {
                TriggerWrapper tw = triggerFacade.get(trigger.getKey());
                // was the trigger deleted since being acquired?
                if (tw == null) {
                    results.add(new TriggerFiredResult((TriggerFiredBundle) null));
                    continue;
                }
                // was the trigger completed, paused, blocked, etc. since being acquired?
                if (tw.getState() != TriggerWrapper.TriggerState.ACQUIRED) {
                    results.add(new TriggerFiredResult((TriggerFiredBundle) null));
                    continue;
                }

                Calendar cal = null;
                if (tw.getCalendarName() != null) {
                    cal = retrieveCalendar(tw.getCalendarName());
                    if (cal == null) {
                        results.add(new TriggerFiredResult((TriggerFiredBundle) null));
                        continue;
                    }
                }
                Date prevFireTime = trigger.getPreviousFireTime();
                // in case trigger was replaced between acquiring and firering
                timeTriggers.remove(tw);

                // call triggered on our copy, and the scheduler's copy
                tw.triggered(cal, triggerFacade);
                trigger.triggered(cal); // calendar is already clone()'d so it is okay to pass out to trigger

                // tw.state = EXECUTING;
                tw.setState(TriggerWrapper.TriggerState.WAITING, nodeId, triggerFacade);

                TriggerFiredBundle bndle = new TriggerFiredBundle(retrieveJob(trigger.getJobKey()), trigger, cal, false,
                        new Date(), trigger.getPreviousFireTime(), prevFireTime,
                        trigger.getNextFireTime());

                String fireInstanceId = trigger.getFireInstanceId();
                FiredTrigger prev = triggerFacade.getFiredTrigger(fireInstanceId);
                triggerFacade.putFiredTrigger(fireInstanceId, new FiredTrigger(nodeId, tw.getKey(), trigger.getPreviousFireTime().getTime()));
                getLog().trace("Tracking " + trigger + " has fired on " + fireInstanceId);
                if (prev != null) {
                    // this shouldn't happen
                    throw new AssertionError("duplicate fireInstanceId detected (" + fireInstanceId + ") for " + trigger
                            + ", previous is " + prev);
                }

                JobDetail job = bndle.getJobDetail();

                if (job.isConcurrentExectionDisallowed()) {
                    List<TriggerWrapper> trigs = triggerFacade.getTriggerWrappersForJob(job.getKey());
                    for (TriggerWrapper ttw : trigs) {
                        if (ttw.getKey().equals(tw.getKey())) {
                            continue;
                        }
                        if (ttw.getState() == TriggerWrapper.TriggerState.WAITING) {
                            ttw.setState(TriggerWrapper.TriggerState.BLOCKED, nodeId, triggerFacade);
                        }
                        if (ttw.getState() == TriggerWrapper.TriggerState.PAUSED) {
                            ttw.setState(TriggerWrapper.TriggerState.PAUSED_BLOCKED, nodeId, triggerFacade);
                        }
                        timeTriggers.remove(ttw);
                    }
                    jobFacade.addBlockedJob(job.getKey());
                } else if (tw.getNextFireTime() != null) {
                    timeTriggers.add(tw);
                }

                results.add(new TriggerFiredResult(bndle));
            }
            return results;
        } catch (JobPersistenceException e) {
            throw e;
        } catch (Exception e) {
            throw new JobPersistenceException("Cannot fire triggers", e);
        } finally {
            try {
                unlock();
            } catch (HazelcastException e) {
                if (!validateFiring(results)) {
                    //noinspection ThrowFromFinallyBlock
                    throw e;
                }
            }
        }
    }

    private boolean validateFiring(List<TriggerFiredResult> result) {
        if (result.isEmpty()) {
            return false;
        } else {
            while (!toolkitShutdown) {
                try {
                    lock();
                    try {
                        for (TriggerFiredResult tfr : result) {
                            TriggerFiredBundle tfb = tfr.getTriggerFiredBundle();
                            if (tfb != null && !triggerFacade.containsFiredTrigger(tfb.getTrigger().getFireInstanceId())) {
                                return false;
                            }
                        }
                        return true;
                    } finally {
                        unlock();
                    }
                } catch (JobPersistenceException | HazelcastException e) {
                    try {
                        Thread.sleep(retryInterval);
                    } catch (InterruptedException f) {
                        throw new IllegalStateException("Received interrupted exception", f);
                    }
                }
            }
            throw new IllegalStateException("Scheduler has been shutdown");
        }
    }

    /**
     * <p>
     * Inform the <code>JobStore</code> that the scheduler has completed the firing of the given <code>Trigger</code> (and
     * the execution its associated <code>Job</code>), and that the <code>{@link org.quartz.JobDataMap}</code> in the
     * given <code>JobDetail</code> should be updated if the <code>Job</code> is stateful.
     * </p>
     */
    @Override
    public void triggeredJobComplete(OperableTrigger trigger, JobDetail jobDetail,
                                     Trigger.CompletedExecutionInstruction triggerInstCode) {
        while (!toolkitShutdown) {
            try {
                lock();
                try {
                    String fireId = trigger.getFireInstanceId();
                    FiredTrigger removed = triggerFacade.removeFiredTrigger(fireId);
                    if (removed == null) {
                        getLog().warn("No fired trigger record found for " + trigger + " (" + fireId + ")");
                        break;
                    }

                    JobKey jobKey = jobDetail.getKey();
                    JobWrapper jw = jobFacade.get(jobKey);
                    TriggerWrapper tw = triggerFacade.get(trigger.getKey());

                    // It's possible that the job is null if:
                    // 1- it was deleted during execution
                    // 2- RAMJobStore is being used only for volatile jobs / triggers
                    // from the JDBC job store
                    if (jw != null) {
                        if (jw.isPersistJobDataAfterExecution()) {
                            JobDataMap newData = jobDetail.getJobDataMap();
                            if (newData != null) {
                                newData = (JobDataMap) newData.clone();
                                newData.clearDirtyFlag();
                            }
                            jw.setJobDataMap(newData, jobFacade);
                        }
                        if (jw.isConcurrentExectionDisallowed()) {
                            jobFacade.removeBlockedJob(jw.getKey());
                            tw.setState(TriggerWrapper.TriggerState.WAITING, nodeId, triggerFacade);
                            timeTriggers.add(tw);

                            List<TriggerWrapper> trigs = triggerFacade.getTriggerWrappersForJob(jw.getKey());

                            for (TriggerWrapper ttw : trigs) {
                                if (ttw.getState() == TriggerWrapper.TriggerState.BLOCKED) {
                                    ttw.setState(TriggerWrapper.TriggerState.WAITING, nodeId, triggerFacade);
                                    timeTriggers.add(ttw);
                                }
                                if (ttw.getState() == TriggerWrapper.TriggerState.PAUSED_BLOCKED) {
                                    ttw.setState(TriggerWrapper.TriggerState.PAUSED, nodeId, triggerFacade);
                                }
                            }
                            signaler.signalSchedulingChange(0L);
                        }
                    } else { // even if it was deleted, there may be cleanup to do
                        jobFacade.removeBlockedJob(jobKey);
                    }

                    // check for trigger deleted during execution...
                    if (tw != null) {
                        if (triggerInstCode == Trigger.CompletedExecutionInstruction.DELETE_TRIGGER) {

                            if (trigger.getNextFireTime() == null) {
                                // double check for possible reschedule within job
                                // execution, which would cancel the need to delete...
                                if (tw.getNextFireTime() == null) {
                                    removeTrigger(trigger.getKey());
                                }
                            } else {
                                removeTrigger(trigger.getKey());
                                signaler.signalSchedulingChange(0L);
                            }
                        } else if (triggerInstCode == Trigger.CompletedExecutionInstruction.SET_TRIGGER_COMPLETE) {
                            tw.setState(TriggerWrapper.TriggerState.COMPLETE, nodeId, triggerFacade);
                            timeTriggers.remove(tw);
                            signaler.signalSchedulingChange(0L);
                        } else if (triggerInstCode == Trigger.CompletedExecutionInstruction.SET_TRIGGER_ERROR) {
                            getLog().info("Trigger " + trigger.getKey() + " set to ERROR state.");
                            tw.setState(TriggerWrapper.TriggerState.ERROR, nodeId, triggerFacade);
                            signaler.signalSchedulingChange(0L);
                        } else if (triggerInstCode == Trigger.CompletedExecutionInstruction.SET_ALL_JOB_TRIGGERS_ERROR) {
                            getLog().info("All triggers of Job " + trigger.getJobKey() + " set to ERROR state.");
                            setAllTriggersOfJobToState(trigger.getJobKey(), TriggerWrapper.TriggerState.ERROR);
                            signaler.signalSchedulingChange(0L);
                        } else if (triggerInstCode == Trigger.CompletedExecutionInstruction.SET_ALL_JOB_TRIGGERS_COMPLETE) {
                            setAllTriggersOfJobToState(trigger.getJobKey(), TriggerWrapper.TriggerState.COMPLETE);
                            signaler.signalSchedulingChange(0L);
                        }
                    }
                } finally {
                    unlock();
                }
            } catch (HazelcastException | JobPersistenceException e) {
                try {
                    Thread.sleep(retryInterval);
                } catch (InterruptedException f) {
                    throw new IllegalStateException("Received interrupted exception", f);
                }
                continue;
            }
            break;
        }
    }

    private void setAllTriggersOfJobToState(JobKey jobKey, TriggerWrapper.TriggerState state) {
        List<TriggerWrapper> tws = triggerFacade.getTriggerWrappersForJob(jobKey);

        for (TriggerWrapper tw : tws) {
            tw.setState(state, nodeId, triggerFacade);
            if (state != TriggerWrapper.TriggerState.WAITING) {
                timeTriggers.remove(tw);
            }
        }
    }

    /**
     * @see org.quartz.spi.JobStore#getPausedTriggerGroups()
     */
    @Override
    public Set<String> getPausedTriggerGroups() throws JobPersistenceException {
        lock();
        try {
            return new HashSet<>(triggerFacade.allPausedTriggersGroupNames());
        } finally {
            unlock();
        }
    }

    @Override
    public void setInstanceId(String schedInstId) {
        //
    }

    @Override
    public void setInstanceName(String schedName) {
        //
    }

    @Override
    public void setTcRetryInterval(long retryInterval) {
        this.retryInterval = retryInterval;
    }

    private void nodeLeft(MembershipEvent event) {
        final UUID nodeLeft = event.getMember().getUuid();

        try {
            lock();
        } catch (JobPersistenceException e) {
            getLog().info("Job store is already disabled, not processing nodeLeft() for " + nodeLeft);
            return;
        }

        try {

            List<TriggerWrapper> toEval = new ArrayList<>();

            for (TriggerKey triggerKey : triggerFacade.allTriggerKeys()) {
                TriggerWrapper tw = triggerFacade.get(triggerKey);
                UUID clientId = tw.getLastHazelcastClientId();
                if (clientId != null && clientId.equals(nodeLeft)) {
                    toEval.add(tw);
                }
            }

            for (TriggerWrapper tw : toEval) {
                evalOrphanedTrigger(tw, false);
            }

            for (String key : triggerFacade.allFiredTriggers()) {
                FiredTrigger ft = triggerFacade.getFiredTrigger(key);
                if (ft == null) {
                    continue;
                }

                if (nodeLeft.equals(ft.getClientId())) {
                    getLog().info("Found non-complete fired trigger: " + ft);
                    triggerFacade.removeFiredTrigger(key);

                    TriggerWrapper tw = triggerFacade.get(ft.getTriggerKey());
                    if (tw == null) {
                        getLog().error("no trigger found for executing trigger: " + ft.getTriggerKey());
                        continue;
                    }

                    scheduleRecoveryIfNeeded(tw, ft);
                }
            }
        } finally {
            unlock();
        }

        // nudge the local scheduler. This is a lazy way to do it. This should perhaps be conditionally happening and
        // also passing a real next job time (as opposed to 0)
        signaler.signalSchedulingChange(0);
    }

    @Override
    public long getEstimatedTimeToReleaseAndAcquireTrigger() {
        // right now this is a static (but configurable) value. It could be based on actual observation
        // of trigger acquire/release at runtime in the future though
        return this.estimatedTimeToReleaseAndAcquireTrigger;
    }

    @Override
    public void setEstimatedTimeToReleaseAndAcquireTrigger(long estimate) {
        this.estimatedTimeToReleaseAndAcquireTrigger = estimate;
    }

    @Override
    public void setThreadPoolSize(final int size) {
        //
    }

    @Override
    public boolean isClustered() {
        // We throw an assertion here since this method should never be called directly on this instance.
        throw new AssertionError();
    }

    @Override
    public long getAcquireRetryDelay(int failureCount) {
        return retryInterval;
    }

    private static class LocalLockState {
        private int acquires = 0;
        private boolean disabled;

        synchronized void attemptAcquireBegin() throws JobPersistenceException {
            if (disabled) {
                throw new JobPersistenceException("HazelcastJobStore is disabled");
            }
            acquires++;
        }

        synchronized void release() {
            acquires--;
            notifyAll();
        }

        synchronized void disableLocking() throws InterruptedException {
            disabled = true;

            while (acquires > 0) {
                wait();
            }
        }
    }

    @Override
    public void memberAdded(MembershipEvent membershipEvent) {
        // do nothing
    }

    @Override
    public void memberRemoved(MembershipEvent membershipEvent) {
        getLog().info("Received node left notification for " + membershipEvent.getMember().getUuid());
        nodeLeft(membershipEvent);
    }

    private static class ShutdownHook implements LifecycleListener {
        private final HazelcastJobStore store;

        ShutdownHook(HazelcastJobStore store) {
            this.store = store;
        }

        @Override
        public void stateChanged(LifecycleEvent event) {
            if (event.getState() == LifecycleEvent.LifecycleState.SHUTTING_DOWN) {
                this.store.disable();
            }
        }
    }
}
