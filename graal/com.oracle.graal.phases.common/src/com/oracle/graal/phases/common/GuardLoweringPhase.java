/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.phases.common;

import static com.oracle.graal.compiler.common.GraalOptions.OptImplicitNullChecks;

import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import jdk.vm.ci.meta.JavaConstant;

import com.oracle.graal.compiler.common.cfg.Loop;
import com.oracle.graal.debug.Debug;
import com.oracle.graal.debug.DebugCloseable;
import com.oracle.graal.debug.DebugMetric;
import com.oracle.graal.graph.Node;
import com.oracle.graal.nodes.AbstractBeginNode;
import com.oracle.graal.nodes.BeginNode;
import com.oracle.graal.nodes.DeoptimizeNode;
import com.oracle.graal.nodes.FixedWithNextNode;
import com.oracle.graal.nodes.GuardNode;
import com.oracle.graal.nodes.IfNode;
import com.oracle.graal.nodes.LogicNode;
import com.oracle.graal.nodes.LoopBeginNode;
import com.oracle.graal.nodes.LoopExitNode;
import com.oracle.graal.nodes.PiNode;
import com.oracle.graal.nodes.StateSplit;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.StructuredGraph.GuardsStage;
import com.oracle.graal.nodes.StructuredGraph.ScheduleResult;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.calc.IsNullNode;
import com.oracle.graal.nodes.cfg.Block;
import com.oracle.graal.nodes.memory.Access;
import com.oracle.graal.nodes.memory.FixedAccessNode;
import com.oracle.graal.nodes.memory.FloatingAccessNode;
import com.oracle.graal.nodes.memory.MemoryNode;
import com.oracle.graal.nodes.memory.address.OffsetAddressNode;
import com.oracle.graal.nodes.util.GraphUtil;
import com.oracle.graal.phases.BasePhase;
import com.oracle.graal.phases.graph.ScheduledNodeIterator;
import com.oracle.graal.phases.schedule.SchedulePhase;
import com.oracle.graal.phases.schedule.SchedulePhase.SchedulingStrategy;
import com.oracle.graal.phases.tiers.MidTierContext;

/**
 * This phase lowers {@link GuardNode GuardNodes} into corresponding control-flow structure and
 * {@link DeoptimizeNode DeoptimizeNodes}.
 *
 * This allow to enter the {@link GuardsStage#FIXED_DEOPTS FIXED_DEOPTS} stage of the graph where
 * all node that may cause deoptimization are fixed.
 * <p>
 * It first makes a schedule in order to know where the control flow should be placed. Then, for
 * each block, it applies two passes. The first one tries to replace null-check guards with implicit
 * null checks performed by access to the objects that need to be null checked. The second phase
 * does the actual control-flow expansion of the remaining {@link GuardNode GuardNodes}.
 */
public class GuardLoweringPhase extends BasePhase<MidTierContext> {

    private static final DebugMetric metricImplicitNullCheck = Debug.metric("ImplicitNullCheck");

    private static class UseImplicitNullChecks extends ScheduledNodeIterator {

        private final Map<ValueNode, ValueNode> nullGuarded = Node.newIdentityMap();
        private final int implicitNullCheckLimit;

        UseImplicitNullChecks(int implicitNullCheckLimit) {
            this.implicitNullCheckLimit = implicitNullCheckLimit;
        }

        @Override
        protected void processNode(Node node) {
            if (node instanceof GuardNode) {
                processGuard(node);
            } else if (node instanceof Access) {
                processAccess((Access) node);
            } else if (node instanceof PiNode) {
                processPi((PiNode) node);
            }
            if (node instanceof StateSplit && ((StateSplit) node).stateAfter() != null) {
                nullGuarded.clear();
            } else {
                /*
                 * The OffsetAddressNode itself never forces materialization of a null check, even
                 * if its input is a PiNode. The null check will be folded into the first usage of
                 * the OffsetAddressNode, so we need to keep it in the nullGuarded map.
                 */
                if (!(node instanceof OffsetAddressNode)) {
                    Iterator<Entry<ValueNode, ValueNode>> it = nullGuarded.entrySet().iterator();
                    while (it.hasNext()) {
                        Entry<ValueNode, ValueNode> entry = it.next();
                        ValueNode guard = entry.getValue();
                        if (guard.usages().contains(node)) {
                            it.remove();
                        } else if (guard instanceof PiNode && guard != node) {
                            PiNode piNode = (PiNode) guard;
                            if (piNode.getGuard().asNode().usages().contains(node)) {
                                it.remove();
                            }
                        }
                    }
                }
            }
        }

        private boolean processPi(PiNode node) {
            ValueNode guardNode = nullGuarded.get(node.object());
            if (guardNode != null && node.getGuard() == guardNode) {
                nullGuarded.put(node, node);
                return true;
            }
            return false;
        }

        private void processAccess(Access access) {
            if (access.canNullCheck() && access.getAddress() instanceof OffsetAddressNode) {
                OffsetAddressNode address = (OffsetAddressNode) access.getAddress();
                check(access, address);
            }
        }

        private void check(Access access, OffsetAddressNode address) {
            ValueNode base = address.getBase();
            ValueNode guard = nullGuarded.get(base);
            if (guard != null && isImplicitNullCheck(address.getOffset())) {
                if (guard instanceof PiNode) {
                    PiNode piNode = (PiNode) guard;
                    assert guard == address.getBase();
                    assert piNode.getGuard() instanceof GuardNode : piNode;
                    address.setBase(piNode.getOriginalNode());
                } else {
                    assert guard instanceof GuardNode;
                }
                metricImplicitNullCheck.increment();
                access.setGuard(null);
                FixedAccessNode fixedAccess;
                if (access instanceof FloatingAccessNode) {
                    FloatingAccessNode floatingAccessNode = (FloatingAccessNode) access;
                    MemoryNode lastLocationAccess = floatingAccessNode.getLastLocationAccess();
                    fixedAccess = floatingAccessNode.asFixedNode();
                    replaceCurrent(fixedAccess);
                    if (lastLocationAccess != null) {
                        // fixed accesses are not currently part of the memory graph
                        GraphUtil.tryKillUnused(lastLocationAccess.asNode());
                    }
                } else {
                    fixedAccess = (FixedAccessNode) access;
                }
                fixedAccess.setNullCheck(true);
                GuardNode guardNode = null;
                if (guard instanceof GuardNode) {
                    guardNode = (GuardNode) guard;
                } else {
                    PiNode piNode = (PiNode) guard;
                    guardNode = (GuardNode) piNode.getGuard();
                }
                LogicNode condition = guardNode.getCondition();
                guardNode.replaceAndDelete(fixedAccess);
                if (condition.hasNoUsages()) {
                    GraphUtil.killWithUnusedFloatingInputs(condition);
                }
                nullGuarded.remove(base);
            }
        }

        private void processGuard(Node node) {
            GuardNode guard = (GuardNode) node;
            if (guard.isNegated() && guard.getCondition() instanceof IsNullNode && (guard.getSpeculation() == null || guard.getSpeculation().equals(JavaConstant.NULL_POINTER))) {
                ValueNode obj = ((IsNullNode) guard.getCondition()).getValue();
                nullGuarded.put(obj, guard);
            }
        }

        private boolean isImplicitNullCheck(ValueNode offset) {
            JavaConstant c = offset.asJavaConstant();
            if (c != null) {
                return c.asLong() < implicitNullCheckLimit;
            } else {
                return false;
            }
        }
    }

    private static class LowerGuards extends ScheduledNodeIterator {

        private final Block block;
        private boolean useGuardIdAsDebugId;

        LowerGuards(Block block, boolean useGuardIdAsDebugId) {
            this.block = block;
            this.useGuardIdAsDebugId = useGuardIdAsDebugId;
        }

        @Override
        protected void processNode(Node node) {
            if (node instanceof GuardNode) {
                GuardNode guard = (GuardNode) node;
                FixedWithNextNode lowered = guard.lowerGuard();
                if (lowered != null) {
                    replaceCurrent(lowered);
                } else {
                    lowerToIf(guard);
                }
            }
        }

        @SuppressWarnings("try")
        private void lowerToIf(GuardNode guard) {
            try (DebugCloseable position = guard.withNodeSourcePosition()) {
                StructuredGraph graph = guard.graph();
                AbstractBeginNode fastPath = graph.add(new BeginNode());
                @SuppressWarnings("deprecation")
                int debugId = useGuardIdAsDebugId ? guard.getId() : DeoptimizeNode.DEFAULT_DEBUG_ID;
                DeoptimizeNode deopt = graph.add(new DeoptimizeNode(guard.getAction(), guard.getReason(), debugId, guard.getSpeculation(), null));
                AbstractBeginNode deoptBranch = BeginNode.begin(deopt);
                AbstractBeginNode trueSuccessor;
                AbstractBeginNode falseSuccessor;
                insertLoopExits(deopt);
                if (guard.isNegated()) {
                    trueSuccessor = deoptBranch;
                    falseSuccessor = fastPath;
                } else {
                    trueSuccessor = fastPath;
                    falseSuccessor = deoptBranch;
                }
                IfNode ifNode = graph.add(new IfNode(guard.getCondition(), trueSuccessor, falseSuccessor, trueSuccessor == fastPath ? 1 : 0));
                guard.replaceAndDelete(fastPath);
                insert(ifNode, fastPath);
            }
        }

        private void insertLoopExits(DeoptimizeNode deopt) {
            Loop<Block> loop = block.getLoop();
            StructuredGraph graph = deopt.graph();
            while (loop != null) {
                LoopExitNode exit = graph.add(new LoopExitNode((LoopBeginNode) loop.getHeader().getBeginNode()));
                graph.addBeforeFixed(deopt, exit);
                loop = loop.getParent();
            }
        }
    }

    @Override
    protected void run(StructuredGraph graph, MidTierContext context) {
        if (graph.getGuardsStage().allowsFloatingGuards()) {
            SchedulePhase schedulePhase = new SchedulePhase(SchedulingStrategy.EARLIEST);
            schedulePhase.apply(graph);
            ScheduleResult schedule = graph.getLastSchedule();

            for (Block block : schedule.getCFG().getBlocks()) {
                processBlock(block, schedule, context != null ? context.getTarget().implicitNullCheckLimit : 0);
            }
            graph.setGuardsStage(GuardsStage.FIXED_DEOPTS);
        }

        assert assertNoGuardsLeft(graph);
    }

    private static boolean assertNoGuardsLeft(StructuredGraph graph) {
        assert graph.getNodes().filter(GuardNode.class).isEmpty();
        return true;
    }

    private static void processBlock(Block block, ScheduleResult schedule, int implicitNullCheckLimit) {
        if (OptImplicitNullChecks.getValue() && implicitNullCheckLimit > 0) {
            new UseImplicitNullChecks(implicitNullCheckLimit).processNodes(block, schedule);
        }
        new LowerGuards(block, Debug.isDumpEnabledForMethod() || Debug.isLogEnabledForMethod()).processNodes(block, schedule);
    }
}
