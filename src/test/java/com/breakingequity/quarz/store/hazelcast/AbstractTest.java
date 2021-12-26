package com.breakingequity.quarz.store.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.quartz.*;
import org.quartz.spi.OperableTrigger;

import java.util.Date;

import static org.quartz.Scheduler.DEFAULT_GROUP;


public abstract class AbstractTest {

    protected int buildTriggerIndex = 0;
    protected int buildJobIndex = 0;

    HazelcastInstance createHazelcastInstance(String clusterName) {
        Config config = new Config();
        config.setClusterName(clusterName);
        config.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(true);
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
                                 ScheduleBuilder<?> scheduleBuilder) {

        ScheduleBuilder<?> schedule = scheduleBuilder != null ? scheduleBuilder : SimpleScheduleBuilder.simpleSchedule();
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
                                           ScheduleBuilder<?> scheduleBuilder) {

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

}
