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
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.cp.lock.FencedLock;
import com.hazelcast.map.IMap;
import com.idvp.data.infrastructure.scheduling.quarz.store.hazelcast.wrappers.FiredTrigger;
import com.idvp.data.infrastructure.scheduling.quarz.store.hazelcast.wrappers.JobWrapper;
import com.idvp.data.infrastructure.scheduling.quarz.store.hazelcast.wrappers.TriggerWrapper;
import org.quartz.Calendar;
import org.quartz.JobKey;
import org.quartz.TriggerKey;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * How JOBS mappings will look? <br>
 * JobKey(name, groupname) -> JobWrapper <br>
 * groupName -> List<String> <br>
 * List -> allGroupNames<br>
 */
public class InstanceHolder {
    private static final String JOBS_MAP_PREFIX = "_tc_quartz_jobs";
    private static final String ALL_JOBS_GROUP_NAMES_SET_PREFIX = "_tc_quartz_grp_names";
    private static final String PAUSED_GROUPS_SET_PREFIX = "_tc_quartz_grp_paused_names";
    private static final String BLOCKED_JOBS_SET_PREFIX = "_tc_quartz_blocked_jobs";
    private static final String JOBS_GROUP_MAP_PREFIX = "_tc_quartz_grp_jobs_";

    private static final String TRIGGERS_MAP_PREFIX = "_tc_quartz_triggers";
    private static final String TRIGGERS_GROUP_MAP_PREFIX = "_tc_quartz_grp_triggers_";
    private static final String ALL_TRIGGERS_GROUP_NAMES_SET_PREFIX = "_tc_quartz_grp_names_triggers";
    private static final String PAUSED_TRIGGER_GROUPS_SET_PREFIX = "_tc_quartz_grp_paused_trogger_names";
    private static final String TIME_TRIGGER_SORTED_SET_PREFIX = "_tc_time_trigger_sorted_set";
    private static final String FIRED_TRIGGER_MAP_PREFIX = "_tc_quartz_fired_trigger";
    private static final String CALENDAR_WRAPPER_MAP_PREFIX = "_tc_quartz_calendar_wrapper";
    private static final String SINGLE_LOCK_NAME_PREFIX = "_tc_quartz_single_lock";

    private static final String DELIMITER = "|";
    private final String jobStoreName;
    private final HazelcastInstance toolkit;

    private final AtomicReference<IMap<JobKey, JobWrapper>> jobsMapReference = new AtomicReference<>();
    private final AtomicReference<IMap<TriggerKey, TriggerWrapper>> triggersMapReference = new AtomicReference<>();

    private final AtomicReference<ISet<String>> allGroupsReference = new AtomicReference<>();
    private final AtomicReference<ISet<String>> allTriggersGroupsReference = new AtomicReference<>();
    private final AtomicReference<ISet<String>> pausedGroupsReference = new AtomicReference<>();
    private final AtomicReference<ISet<JobKey>> blockedJobsReference = new AtomicReference<>();
    private final Map<String, ISet<String>> jobsGroupSet = new HashMap<>();
    private final Map<String, ISet<String>> triggersGroupSet = new HashMap<>();
    private final AtomicReference<ISet<String>> pausedTriggerGroupsReference = new AtomicReference<>();

    private final AtomicReference<IMap<String, FiredTrigger>> firedTriggersMapReference = new AtomicReference<>();
    private final AtomicReference<IMap<String, Calendar>> calendarWrapperMapReference = new AtomicReference<>();
    private final AtomicReference<TimeTriggerSet> timeTriggerSetReference = new AtomicReference<>();

    private final Map<String, IMap<?, ?>> toolkitMaps = new HashMap<>();

    public InstanceHolder(String jobStoreName, HazelcastInstance toolkit) {
        this.jobStoreName = jobStoreName;
        this.toolkit = toolkit;
    }

    private String generateName(String prefix) {
        return prefix + DELIMITER + jobStoreName;
    }

    public IMap<JobKey, JobWrapper> getOrCreateJobsMap() {
        String jobsMapName = generateName(JOBS_MAP_PREFIX);
        IMap<JobKey, JobWrapper> temp = createStore(jobsMapName);
        jobsMapReference.compareAndSet(null, temp);
        return jobsMapReference.get();
    }

    protected IMap<?, ?> toolkitMap(String nameOfMap) {
        IMap<?, ?> map = toolkitMaps.get(nameOfMap);
        if (map != null) {
            return map;
        } else {
            map = createStore(nameOfMap);
            toolkitMaps.put(nameOfMap, map);
            return map;
        }
    }

    private <K, V> IMap<K, V> createStore(String nameOfMap) {
        return toolkit.getMap(nameOfMap);
    }

    public IMap<TriggerKey, TriggerWrapper> getOrCreateTriggersMap() {
        String triggersMapName = generateName(TRIGGERS_MAP_PREFIX);
        IMap<TriggerKey, TriggerWrapper> temp = createStore(triggersMapName);
        triggersMapReference.compareAndSet(null, temp);
        return triggersMapReference.get();
    }

    public IMap<String, FiredTrigger> getOrCreateFiredTriggersMap() {
        String firedTriggerMapName = generateName(FIRED_TRIGGER_MAP_PREFIX);
        IMap<String, FiredTrigger> temp = createStore(firedTriggerMapName);
        firedTriggersMapReference.compareAndSet(null, temp);
        return firedTriggersMapReference.get();
    }

    public IMap<String, Calendar> getOrCreateCalendarWrapperMap() {
        String calendarWrapperName = generateName(CALENDAR_WRAPPER_MAP_PREFIX);
        IMap<String, Calendar> temp = createStore(calendarWrapperName);
        calendarWrapperMapReference.compareAndSet(null, temp);
        return calendarWrapperMapReference.get();
    }

    public Set<String> getOrCreateAllGroupsSet() {
        String allGrpSetNames = generateName(ALL_JOBS_GROUP_NAMES_SET_PREFIX);
        ISet<String> temp = toolkit.getSet(allGrpSetNames);
        allGroupsReference.compareAndSet(null, temp);

        return allGroupsReference.get();
    }

    public Set<JobKey> getOrCreateBlockedJobsSet() {
        String blockedJobsSetName = generateName(BLOCKED_JOBS_SET_PREFIX);
        ISet<JobKey> temp = toolkit.getSet(blockedJobsSetName);
        blockedJobsReference.compareAndSet(null, temp);

        return blockedJobsReference.get();
    }

    public Set<String> getOrCreatePausedGroupsSet() {
        String pausedGrpsSetName = generateName(PAUSED_GROUPS_SET_PREFIX);
        ISet<String> temp = toolkit.getSet(pausedGrpsSetName);
        pausedGroupsReference.compareAndSet(null, temp);

        return pausedGroupsReference.get();
    }

    public Set<String> getOrCreatePausedTriggerGroupsSet() {
        String pausedGrpsSetName = generateName(PAUSED_TRIGGER_GROUPS_SET_PREFIX);
        ISet<String> temp = toolkit.getSet(pausedGrpsSetName);
        pausedTriggerGroupsReference.compareAndSet(null, temp);

        return pausedTriggerGroupsReference.get();
    }

    public Set<String> getOrCreateJobsGroupMap(String name) {
        ISet<String> set = jobsGroupSet.get(name);

        if (set != null) {
            return set;
        } else {
            String nameForMap = generateName(JOBS_GROUP_MAP_PREFIX + name);
            set = toolkit.getSet(nameForMap);
            jobsGroupSet.put(name, set);
            return set;
        }
    }

    public void removeJobsGroupMap(String name) {
        ISet<String> set = jobsGroupSet.remove(name);
        if (set != null) {
            set.destroy();
        }
    }

    public Set<String> getOrCreateTriggersGroupMap(String name) {
        ISet<String> set = triggersGroupSet.get(name);

        if (set != null) {
            return set;
        } else {
            String nameForMap = generateName(TRIGGERS_GROUP_MAP_PREFIX + name);
            set = toolkit.getSet(nameForMap);
            triggersGroupSet.put(name, set);
            return set;
        }
    }

    public void removeTriggersGroupMap(String name) {
        ISet<String> set = triggersGroupSet.remove(name);
        if (set != null) {
            set.destroy();
        }
    }

    public Set<String> getOrCreateAllTriggersGroupsSet() {
        String allTriggersGroupName = generateName(ALL_TRIGGERS_GROUP_NAMES_SET_PREFIX);
        ISet<String> temp = toolkit.getSet(allTriggersGroupName);
        allTriggersGroupsReference.compareAndSet(null, temp);

        return allTriggersGroupsReference.get();
    }

    public TimeTriggerSet getOrCreateTimeTriggerSet() {
        String triggerSetName = generateName(TIME_TRIGGER_SORTED_SET_PREFIX);
        TimeTriggerSet set = new TimeTriggerSet(toolkit.getSet(triggerSetName));
        timeTriggerSetReference.compareAndSet(null, set);

        return timeTriggerSetReference.get();
    }

    public FencedLock getLock() {
        String lockName = generateName(SINGLE_LOCK_NAME_PREFIX);
        return toolkit.getCPSubsystem().getLock(lockName);
    }
}