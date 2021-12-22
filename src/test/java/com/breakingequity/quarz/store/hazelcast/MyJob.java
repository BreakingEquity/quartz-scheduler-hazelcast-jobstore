/**
 * Copyright (C) Bikeemotion
 * 2014
 *
 * The reproduction, transmission or use of this document or its contents is not
 * permitted without express written authorization. All rights, including rights
 * created by patent grant or registration of a utility model or design, are
 * reserved. Modifications made to this document are restricted to authorized
 * personnel only. Technical specifications and features are binding only when
 * specifically and expressly agreed upon in a written contract.
 */
package com.breakingequity.quarz.store.hazelcast;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Date;
import java.util.LinkedList;
import java.util.Queue;

public final class MyJob implements Job, Serializable {

  private static final Logger LOG = LoggerFactory.getLogger(MyJob.class);

  volatile static int count = 0;
  static Queue<String> jobKeys = new LinkedList<>();
  static Queue<String> triggerKeys = new LinkedList<>();

  @Override
  public void execute(final JobExecutionContext jobCtx) {

    jobKeys.add(jobCtx.getJobDetail().getKey().getName());
    triggerKeys.add(jobCtx.getTrigger().getKey().getName());
    synchronized (this) {
      count++;
    }
    LOG.info("Processing Trigger " + jobCtx.getTrigger().getKey().getName() + " " + new Date());
  }

}
