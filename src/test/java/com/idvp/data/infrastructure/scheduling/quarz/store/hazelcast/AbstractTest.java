package com.idvp.data.infrastructure.scheduling.quarz.store.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.quartz.*;
import org.quartz.impl.calendar.BaseCalendar;
import org.quartz.spi.OperableTrigger;

import java.util.Date;

import static org.quartz.Scheduler.DEFAULT_GROUP;


public abstract class AbstractTest {

    static HazelcastInstance hazelcastInstance;
    static HazelcastJobStore jobStore;
    private int buildTriggerIndex = 0;
    private int buildJobIndex = 0;

    HazelcastInstance createHazelcastInstance(String clusterName) {

        Config config = new Config();
        config.setClusterName(clusterName);
        config.getNetworkConfig().getJoin().getMulticastConfig().setMulticastGroup("224.0.0.1");
        config.setProperty("hazelcast.logging.type", "slf4j");
        config.setProperty("hazelcast.heartbeat.interval.seconds", "1");
        config.setProperty("hazelcast.max.no.heartbeat.seconds", "3");
        return Hazelcast.newHazelcastInstance(config);
    }

    static HazelcastJobStore createJobStore(HazelcastInstance instance, String name) {
        HazelcastJobStore hzJobStore = new HazelcastJobStore(instance, name);
        hzJobStore.setInstanceName(name);
        return hzJobStore;
    }

    JobDetail buildJob() {

        return buildJob("jobName" + buildJobIndex++, DEFAULT_GROUP);
    }

    JobDetail buildJob(String jobName) {

        return buildJob(jobName, DEFAULT_GROUP);
    }

    JobDetail buildJob(String jobName, String grouName) {

        return buildJob(jobName, grouName, Job.class);
    }

    JobDetail buildJob(String jobName, String grouName, Class<? extends Job> jobClass) {

        return JobBuilder.newJob(jobClass).withIdentity(jobName, grouName).build();
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

    OperableTrigger buildTrigger(String triggerName,
                                 String triggerGroup,
                                 JobDetail job,
                                 Long startAt,
                                 Long endAt) {
        return buildTrigger(triggerName, triggerGroup, job, startAt, endAt, null);
    }

    OperableTrigger buildTrigger(String triggerName,
                                 String triggerGroup,
                                 JobDetail job,
                                 Long startAt,
                                 Long endAt,
                                 ScheduleBuilder scheduleBuilder) {

        ScheduleBuilder schedule = scheduleBuilder != null ? scheduleBuilder : SimpleScheduleBuilder.simpleSchedule();
        //noinspection unchecked
        return (OperableTrigger) TriggerBuilder
                .newTrigger()
                .withIdentity(triggerName, triggerGroup)
                .forJob(job)
                .startAt(startAt != null ? new Date(startAt) : null)
                .endAt(endAt != null ? new Date(endAt) : null)
                .withSchedule(schedule)
                .build();
    }

    OperableTrigger buildTrigger(String triggerName, String triggerGroup, JobDetail job, Long startAt) {

        return buildTrigger(triggerName, triggerGroup, job, startAt, null, null);
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

    OperableTrigger buildTrigger(JobDetail jobDetail) {

        return buildTrigger("triggerName" + buildTriggerIndex++, DEFAULT_GROUP, jobDetail);
    }

    OperableTrigger buildTrigger(String triggerName, String groupName, JobDetail jobDetail) {

        return buildTrigger(triggerName, groupName, jobDetail, System.currentTimeMillis());
    }

    OperableTrigger buildAndComputeTrigger(String triggerName, String triggerGroup, JobDetail job, Long startAt) {

        return buildAndComputeTrigger(triggerName, triggerGroup, job, startAt, null);
    }

    @SuppressWarnings("SameParameterValue")
    OperableTrigger buildAndComputeTrigger(String triggerName,
                                           String triggerGroup,
                                           JobDetail job,
                                           Long startAt,
                                           Long endAt,
                                           ScheduleBuilder scheduleBuilder) {

        OperableTrigger trigger = buildTrigger(triggerName, triggerGroup, job, startAt, endAt, scheduleBuilder);
        trigger.computeFirstFireTime(null);
        return trigger;
    }

    OperableTrigger buildAndComputeTrigger(String triggerName,
                                           String triggerGroup,
                                           JobDetail job,
                                           Long startAt,
                                           Long endAt) {

        OperableTrigger trigger = buildTrigger(triggerName, triggerGroup, job, startAt, endAt, null);
        trigger.computeFirstFireTime(null);
        return trigger;
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
