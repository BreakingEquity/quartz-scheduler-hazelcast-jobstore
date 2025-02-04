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

package com.breakingequity.quarz.store.hazelcast.collections;

import org.quartz.Trigger;
import org.quartz.TriggerKey;

import java.io.Serializable;
import java.util.Date;

public class TimeTrigger implements Comparable<TimeTrigger>, Serializable {

    private final TriggerKey triggerKey;
    private final Long nextFireTime;
    private final int priority;

    TimeTrigger(TriggerKey triggerKey, Date next, int priority) {
        this.triggerKey = triggerKey;
        this.nextFireTime = next == null ? null : next.getTime();
        this.priority = priority;
    }

    TriggerKey getTriggerKey() {
        return triggerKey;
    }

    int getPriority() {
        return priority;
    }

    Date getNextFireTime() {
        return nextFireTime == null ? null : new Date(nextFireTime);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof TimeTrigger) {
            TimeTrigger other = (TimeTrigger) obj;
            return triggerKey.equals(other.triggerKey);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return triggerKey.hashCode();
    }

    @Override
    public String toString() {
        return "TimeTrigger [triggerKey=" + triggerKey + ", nextFireTime=" + new Date(nextFireTime) + ", priority="
                + priority + "]";
    }

    @Override
    public int compareTo(TimeTrigger tt2) {
        TimeTrigger tt1 = this;
        return Trigger.TriggerTimeComparator.compare(tt1.getNextFireTime(), tt1.getPriority(), tt1.getTriggerKey(),
                tt2.getNextFireTime(), tt2.getPriority(), tt2.getTriggerKey());
    }

}