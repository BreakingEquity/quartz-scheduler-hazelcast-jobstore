package com.breakingequity.quarz.store.hazelcast;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Date;
import java.util.LinkedList;
import java.util.Queue;


@DisallowConcurrentExecution
public final class MyNoConcurrentJob implements Job, Serializable {

    private static final Logger LOG = LoggerFactory.getLogger(MyNoConcurrentJob.class);

    static int count = 0;
    static Queue<String> jobKeys = new LinkedList<>();
    static Queue<String> triggerKeys = new LinkedList<>();
    static long waitTime = 300;

    @Override
    public void execute(final JobExecutionContext jobCtx) {

        jobKeys.add(jobCtx.getJobDetail().getKey().getName());
        triggerKeys.add(jobCtx.getTrigger().getKey().getName());
        count++;
        LOG.info("Processing Trigger " + jobCtx.getTrigger().getKey().getName() + " " + new Date());
        try {
            Thread.sleep(waitTime);
        } catch (InterruptedException ignored) {
        }
        LOG.info("All job done");
    }

}
