/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.truffle.debug;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;

import jdk.vm.ci.meta.ResolvedJavaMethod;

import com.oracle.graal.graph.Node;
import com.oracle.graal.nodes.BeginNode;
import com.oracle.graal.nodes.DeoptimizeNode;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.VirtualState;
import com.oracle.graal.nodes.graphbuilderconf.InlineInvokePlugin;
import com.oracle.graal.nodes.java.MethodCallTargetNode;
import com.oracle.graal.nodes.virtual.VirtualObjectNode;
import com.oracle.graal.truffle.OptimizedCallTarget;

public class HistogramInlineInvokePlugin implements InlineInvokePlugin {

    private final Map<ResolvedJavaMethod, MethodStatistics> histogram = new HashMap<>();
    private final StructuredGraph graph;

    private HistogramInlineInvokePlugin.MethodStatistic currentStatistic;

    public HistogramInlineInvokePlugin(StructuredGraph graph) {
        this.graph = graph;
    }

    @Override
    public void notifyBeforeInline(ResolvedJavaMethod methodToInline) {
        currentStatistic = new MethodStatistic(currentStatistic, methodToInline, countNodes(), countCalls());
    }

    @Override
    public void notifyAfterInline(ResolvedJavaMethod methodToInline) {
        assert methodToInline.equals(currentStatistic.method);

        currentStatistic.applyNodeCountAfter(countNodes());
        currentStatistic.applyCallsAfter(countCalls());
        accept(currentStatistic);
        currentStatistic = currentStatistic.getParent();
    }

    private int countNodes() {
        return graph.getNodes().filter(node -> isNonTrivial(node)).count();
    }

    private int countCalls() {
        return graph.getNodes(MethodCallTargetNode.TYPE).count();
    }

    private static boolean isNonTrivial(Node node) {
        return !(node instanceof VirtualState || node instanceof VirtualObjectNode || node instanceof BeginNode || node instanceof DeoptimizeNode);
    }

    private void accept(MethodStatistic current) {
        ResolvedJavaMethod method = current.getMethod();
        HistogramInlineInvokePlugin.MethodStatistics statistics = histogram.get(method);
        if (statistics == null) {
            statistics = new MethodStatistics(method);
            histogram.put(method, statistics);
        }
        statistics.accept(current);
    }

    public void print(OptimizedCallTarget target) {
        OptimizedCallTarget.log(String.format("Truffle expansion histogram for %s", target));
        OptimizedCallTarget.log("  Invocations = Number of expanded invocations");
        OptimizedCallTarget.log("  Nodes = Number of non-trival Graal nodes created for this method during partial evaluation.");
        OptimizedCallTarget.log("  Calls = Number of not expanded calls created for this method during partial evaluation.");
        OptimizedCallTarget.log(String.format(" %-11s |Nodes %5s %5s %5s %8s |Calls %5s %5s %5s %8s | Method Name", "Invocations", "Sum", "Min", "Max", "Avg", "Sum", "Min", "Max", "Avg"));
        histogram.values().stream().filter(statistics -> statistics.shallowCount.getSum() > 0).sorted().forEach(statistics -> statistics.print());
    }

    private static class MethodStatistics implements Comparable<MethodStatistics> {

        private final ResolvedJavaMethod method;

        private int count;
        private final IntSummaryStatistics shallowCount = new IntSummaryStatistics();
        private final IntSummaryStatistics callCount = new IntSummaryStatistics();

        MethodStatistics(ResolvedJavaMethod method) {
            this.method = method;
        }

        public void print() {
            OptimizedCallTarget.log(String.format(" %11d |      %5d %5d %5d %8.2f |      %5d %5d %5d %8.2f | %s", //
                            count, shallowCount.getSum(), shallowCount.getMin(), shallowCount.getMax(), //
                            shallowCount.getAverage(), callCount.getSum(), callCount.getMin(), callCount.getMax(), //
                            callCount.getAverage(), method.format("%h.%n(%p)")));
        }

        @Override
        public int compareTo(MethodStatistics o) {
            int result = Long.compare(o.shallowCount.getSum(), shallowCount.getSum());
            if (result == 0) {
                return Integer.compare(o.count, count);
            }
            return result;
        }

        public void accept(MethodStatistic statistic) {
            if (!statistic.method.equals(method)) {
                throw new IllegalArgumentException("invalid statistic");
            }
            count++;
            callCount.accept(statistic.getShallowCallCount());
            shallowCount.accept(statistic.getShallowNodeCount());
        }
    }

    private static class MethodStatistic {

        private final MethodStatistic parent;
        private final List<MethodStatistic> children = new ArrayList<>();

        private final ResolvedJavaMethod method;
        private int deepNodeCount;
        private int callCount;

        MethodStatistic(MethodStatistic parent, ResolvedJavaMethod method, int nodeCountBefore, int callsBefore) {
            this.parent = parent;
            this.method = method;
            this.callCount = callsBefore;
            this.deepNodeCount = nodeCountBefore;
            if (parent != null) {
                this.parent.getChildren().add(this);
            }
        }

        public ResolvedJavaMethod getMethod() {
            return method;
        }

        public List<MethodStatistic> getChildren() {
            return children;
        }

        public int getShallowNodeCount() {
            int shallowCount = deepNodeCount;
            for (MethodStatistic child : children) {
                shallowCount -= child.deepNodeCount;
            }
            return shallowCount;
        }

        public int getShallowCallCount() {
            int shallowCount = callCount;
            for (MethodStatistic child : children) {
                shallowCount -= child.callCount;
            }
            return shallowCount;
        }

        public void applyNodeCountAfter(int nodeCountAfter) {
            deepNodeCount = nodeCountAfter - this.deepNodeCount;
        }

        public void applyCallsAfter(int callsAfter) {
            callCount = callsAfter - this.callCount;
        }

        public MethodStatistic getParent() {
            return parent;
        }

    }

}
