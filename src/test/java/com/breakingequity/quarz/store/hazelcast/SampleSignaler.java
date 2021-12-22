package com.breakingequity.quarz.store.hazelcast;

import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.spi.SchedulerSignaler;

public class SampleSignaler implements SchedulerSignaler {

    @SuppressWarnings("WeakerAccess")
    volatile int fMisfireCount = 0;

    @Override
    public void notifyTriggerListenersMisfired(Trigger trigger) {

        HazelcastJobStoreTest.LOG.debug("Trigger misfired: " + trigger.getKey() + ", fire time: "
                + trigger.getNextFireTime());
        synchronized (this) {
            fMisfireCount++;
        }
    }

    @Override
    public void signalSchedulingChange(long candidateNewNextFireTime) {

    }

    @Override
    public void notifySchedulerListenersFinalized(Trigger trigger) {

    }

    @Override
    public void notifySchedulerListenersJobDeleted(JobKey jobKey) {

    }

    @Override
    public void notifySchedulerListenersError(String string,
                                              SchedulerException jpe) {

    }
}
