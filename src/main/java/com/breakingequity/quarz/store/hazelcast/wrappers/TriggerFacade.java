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

package com.breakingequity.quarz.store.hazelcast.wrappers;

import com.hazelcast.map.IMap;
import com.breakingequity.quarz.store.hazelcast.collections.InstanceHolder;
import org.quartz.JobKey;
import org.quartz.TriggerKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

@SuppressWarnings("UnusedReturnValue")
public class TriggerFacade {
    private final IMap<TriggerKey, TriggerWrapper> triggersByFQN;
    private final Set<String> allTriggersGroupNames;
    private final Set<String> pausedTriggerGroupNames;
    private final IMap<String, FiredTrigger> firedTriggers;

    public TriggerFacade(InstanceHolder toolkitDSHolder) {
        this.triggersByFQN = toolkitDSHolder.getOrCreateTriggersMap();
        this.allTriggersGroupNames = toolkitDSHolder.getOrCreateAllTriggersGroupsSet();
        this.pausedTriggerGroupNames = toolkitDSHolder.getOrCreatePausedTriggerGroupsSet();
        this.firedTriggers = toolkitDSHolder.getOrCreateFiredTriggersMap();
    }

    public TriggerWrapper get(TriggerKey key) {
        return triggersByFQN.get(key);
    }

    public boolean containsKey(TriggerKey key) {
        return triggersByFQN.containsKey(key);
    }

    public void put(TriggerKey key, TriggerWrapper value) {
        triggersByFQN.put(key, value);
    }

    public TriggerWrapper remove(TriggerKey key) {
        return triggersByFQN.remove(key);
    }

    public FiredTrigger getFiredTrigger(String key) {
        return firedTriggers.get(key);
    }

    public boolean containsFiredTrigger(String key) {
        return firedTriggers.containsKey(key);
    }

    public void putFiredTrigger(String key, FiredTrigger value) {
        firedTriggers.put(key, value);
    }

    public FiredTrigger removeFiredTrigger(String key) {
        return firedTriggers.remove(key);
    }

    @SuppressWarnings("UnusedReturnValue")
    public boolean addGroup(String name) {
        return this.allTriggersGroupNames.add(name);
    }

    public boolean hasGroup(String name) {
        return this.allTriggersGroupNames.contains(name);
    }

    public boolean removeGroup(String name) {
        return this.allTriggersGroupNames.remove(name);
    }

    public boolean addPausedGroup(String name) {
        return this.pausedTriggerGroupNames.add(name);
    }

    public boolean pausedGroupsContain(String name) {
        return this.pausedTriggerGroupNames.contains(name);
    }

    public Set<String> allTriggersGroupNames() {
        return this.allTriggersGroupNames;
    }

    public Set<String> allPausedTriggersGroupNames() {
        return this.pausedTriggerGroupNames;
    }

    public Set<TriggerKey> allTriggerKeys() {
        return this.triggersByFQN.keySet();
    }

    public Collection<String> allFiredTriggers() {
        return firedTriggers.keySet();
    }

    public int numberOfTriggers() {
        return triggersByFQN.size();
    }

    public List<TriggerWrapper> getTriggerWrappersForJob(JobKey key) {
        List<TriggerWrapper> trigList = new ArrayList<TriggerWrapper>();

        for (TriggerKey triggerKey : triggersByFQN.keySet()) {
            TriggerWrapper tw = triggersByFQN.get(triggerKey);
            if (tw.getJobKey().equals(key)) {
                trigList.add(tw);
            }
        }

        return trigList;
    }

    public List<TriggerWrapper> getTriggerWrappersForCalendar(String calName) {
        List<TriggerWrapper> trigList = new ArrayList<TriggerWrapper>();

        for (TriggerKey triggerKey : triggersByFQN.keySet()) {
            TriggerWrapper tw = triggersByFQN.get(triggerKey);
            String tcalName = tw.getCalendarName();
            if (tcalName != null && tcalName.equals(calName)) {
                trigList.add(tw);
            }
        }

        return trigList;
    }

    public void removeAllPausedGroups(Collection<String> groups) {
        this.pausedTriggerGroupNames.removeAll(groups);
    }
}
