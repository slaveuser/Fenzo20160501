/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.fenzo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This encapsulates preferential resource sets available on a VM. Resource sets are two level resources that can
 * be assigned to tasks that specify a name to reserve for an available resource set, and the number of sub-resources
 * (second level of the two level resource) it needs.
 * <P>A {@link PreferentialNamedConsumableResourceSet} contains 1 or more resource sets,
 * {@link com.netflix.fenzo.PreferentialNamedConsumableResourceSet.PreferentialNamedConsumableResource}, each of which
 * can be assigned (or reserved to) a name requested by tasks, if currently unassigned. Each resource set contains one
 * or more count of resources available for assignment to tasks.
 * <P>A task can be assigned to one of the resource sets if it either has no tasks assigned to it, or the name assigned
 * to the resource set matches what the task being assigned is requesting. The number of sub-resources requested by task
 * is also checked. Tasks may request 0 or more sub-resources. The assignment attempts to use as few resource sets as
 * possible and returns a fitness score that helps scheduler pick between multiple VMs that can potentially fit the task.
 * The resulting assignment contains the index of the resource set assigned. The resource sets are assigned indexes
 * starting with 0.
 */
public class PreferentialNamedConsumableResourceSet {

    final static String attributeName = "ResourceSet";

    private static String getResNameVal(String name, TaskRequest request) {
        final Map<String, TaskRequest.NamedResourceSetRequest> customNamedResources = request.getCustomNamedResources();
        if(customNamedResources!=null) {
            final TaskRequest.NamedResourceSetRequest setRequest = customNamedResources.get(name);
            return setRequest==null? CustomResAbsentKey : setRequest.getResValue();
        }
        return CustomResAbsentKey;
    }

    public static class ConsumeResult {
        private final int index;
        private final String attrName;
        private final String resName;
        private final double fitness;

        @JsonCreator
        @JsonIgnoreProperties(ignoreUnknown=true)
        public ConsumeResult(@JsonProperty("index") int index,
                             @JsonProperty("attrName") String attrName,
                             @JsonProperty("resName") String resName,
                             @JsonProperty("fitness") double fitness) {
            this.index = index;
            this.attrName = attrName;
            this.resName = resName;
            this.fitness = fitness;
        }

        public int getIndex() {
            return index;
        }

        public String getAttrName() {
            return attrName;
        }

        public String getResName() {
            return resName;
        }

        public double getFitness() {
            return fitness;
        }
    }

    public static class PreferentialNamedConsumableResource {
        private final double maxFitness;
        private final int index;
        private final String attrName;
        private String resName=null;
        private final int limit;
        private final Map<String, TaskRequest.NamedResourceSetRequest> usageBy;
        private int usedSubResources=0;

        PreferentialNamedConsumableResource(int i, String attrName, int limit) {
            this.index = i;
            this.attrName = attrName;
            this.limit = limit;
            usageBy = new HashMap<>();
            // we add 1.0 to max fitness possible since we add 1.0 for the situation when there is already a task
            // assigned with the same resValue even though it uses 0.0 subResources, versus, there are no assignments yet.
            maxFitness = limit + 1.0;
        }

        public int getIndex() {
            return index;
        }

        public String getResName() {
            return resName;
        }

        public int getLimit() {
            return limit;
        }

        public Map<String, TaskRequest.NamedResourceSetRequest> getUsageBy() {
            return usageBy;
        }

        double getUsedCount() {
            if(resName==null)
                return -1;
            return usedSubResources;
        }

        double getFitness(TaskRequest request) {
            String r = getResNameVal(attrName, request);
            if(resName == null)
                return 0.5 / maxFitness; // unassigned: 0.0 indicates no fitness, so return 0.5, which is less than
                // the case of assigned with 0 sub-resources
            if(!resName.equals(r))
                return 0.0;
            final TaskRequest.NamedResourceSetRequest setRequest = request.getCustomNamedResources()==null?
                    null : request.getCustomNamedResources().get(attrName);
            double subResNeed = setRequest==null? 0.0 : setRequest.getNumSubResources();
            if(usedSubResources + subResNeed > limit)
                return 0.0;
            return Math.min(1.0, usedSubResources + subResNeed + 1.0 / maxFitness);
        }

        void consume(TaskRequest request) {
            String r = getResNameVal(attrName, request);
            if(resName != null && !resName.equals(r))
                throw new RuntimeException(this.getClass().getName() + " already consumed by " + resName + ", can't consumed for " + r);
            if(resName == null) {
                resName = r;
                usageBy.clear();
            }
            final TaskRequest.NamedResourceSetRequest setRequest = request.getCustomNamedResources()==null?
                    null : request.getCustomNamedResources().get(attrName);
            double subResNeed = setRequest==null? 0.0 : setRequest.getNumSubResources();
            if(usedSubResources + subResNeed > limit)
                throw new RuntimeException(this.getClass().getName() + " already consumed for " + resName +
                        " up to the limit of " + limit);
            usageBy.put(request.getId(), setRequest);
            usedSubResources += subResNeed;
        }

        boolean release(TaskRequest request) {
            String r = getResNameVal(attrName, request);
            if(resName != null && !resName.equals(r)) {
                return false;
            }
            final TaskRequest.NamedResourceSetRequest removed = usageBy.remove(request.getId());
            if(removed == null)
                return false;
            usedSubResources -= removed.getNumSubResources();
            if(usageBy.isEmpty())
                resName = null;
            return true;
        }
    }

    public static final String CustomResAbsentKey = "CustomResAbsent";
    private final String name;
    private final List<PreferentialNamedConsumableResource> usageBy;

    public PreferentialNamedConsumableResourceSet(String name, int val0, int val1) {
        this.name = name;
        usageBy = new ArrayList<>(val0);
        for(int i=0; i<val0; i++)
            usageBy.add(new PreferentialNamedConsumableResource(i, name, val1));
    }

    public String getName() {
        return name;
    }

//    boolean hasAvailability(TaskRequest request) {
//        for(PreferentialNamedConsumableResource r: usageBy) {
//            if(r.hasAvailability(request))
//                return true;
//        }
//        return false;
//    }

    ConsumeResult consume(TaskRequest request) {
        return consumeIntl(request, false);
    }

    // returns 0.0 for no fitness at all, or <=1.0 for fitness
    double getFitness(TaskRequest request) {
        return consumeIntl(request, true).fitness;
    }

    private ConsumeResult consumeIntl(TaskRequest request, boolean skipConsume) {
        PreferentialNamedConsumableResource best = null;
        double bestFitness=0.0;
        for(PreferentialNamedConsumableResource r: usageBy) {
            double f = r.getFitness(request);
            if(f == 0.0)
                continue;
            if(bestFitness < f) {
                best = r;
                bestFitness = f;
            }
        }
        if(!skipConsume) {
            if (best == null)
                throw new RuntimeException("Unexpected to have no availability for job " + request.getId() + " for consumable resource " + name);
            best.consume(request);
        }
        return new ConsumeResult(
                best==null? -1 : best.index,
                best==null? null : best.attrName,
                best==null? null : best.resName,
                bestFitness
        );
    }

    boolean release(TaskRequest request) {
        for(PreferentialNamedConsumableResource r: usageBy)
            if(r.release(request))
                return true;
        return false;
    }

    int getNumSubResources() {
        return usageBy.get(0).getLimit()-1;
    }

    List<Double> getUsedCounts() {
        List<Double> counts = new ArrayList<>(usageBy.size());
        for(PreferentialNamedConsumableResource r: usageBy)
            counts.add(r.getUsedCount());
        return counts;
    }
}
