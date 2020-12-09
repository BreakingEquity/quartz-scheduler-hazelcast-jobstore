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

package com.idvp.data.infrastructure.scheduling.quarz.store.hazelcast.collections;

import com.hazelcast.collection.ISet;
import com.idvp.data.infrastructure.scheduling.quarz.store.hazelcast.wrappers.TriggerWrapper;
import org.quartz.TriggerKey;

import java.util.Iterator;
import java.util.TreeSet;

public class TimeTriggerSet {
    private final ISet<TimeTrigger> timeTriggers;

    TimeTriggerSet(ISet<TimeTrigger> timeTriggers) {
        this.timeTriggers = timeTriggers;
    }

    public boolean add(TriggerWrapper wrapper) {
        TimeTrigger timeTrigger = new TimeTrigger(wrapper.getKey(), wrapper.getNextFireTime(), wrapper.getPriority());
        return timeTriggers.add(timeTrigger);
    }

    public boolean remove(TriggerWrapper wrapper) {
        TimeTrigger timeTrigger = new TimeTrigger(wrapper.getKey(), wrapper.getNextFireTime(), wrapper.getPriority());
        return timeTriggers.remove(timeTrigger);
    }

    public TriggerKey removeFirst() {
        Iterator<TimeTrigger> iter = new TreeSet<>(timeTriggers).iterator();
        TimeTrigger tt = null;
        if (iter.hasNext()) {
            tt = iter.next();
        }

        if (tt == null) {
            return null;
        }

        timeTriggers.remove(tt);
        return tt.getTriggerKey();
    }

    public void destroy() {
        timeTriggers.destroy();
    }

    public int size() {
        return timeTriggers.size();
    }
}
