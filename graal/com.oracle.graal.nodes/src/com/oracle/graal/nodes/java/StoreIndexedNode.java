/*
 * Copyright (c) 2009, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes.java;

import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaType;

import com.oracle.graal.compiler.common.type.StampFactory;
import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.nodeinfo.InputType;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodes.FrameState;
import com.oracle.graal.nodes.StateSplit;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.spi.Lowerable;
import com.oracle.graal.nodes.spi.Virtualizable;
import com.oracle.graal.nodes.spi.VirtualizerTool;
import com.oracle.graal.nodes.type.StampTool;
import com.oracle.graal.nodes.virtual.VirtualArrayNode;
import com.oracle.graal.nodes.virtual.VirtualObjectNode;

/**
 * The {@code StoreIndexedNode} represents a write to an array element.
 */
@NodeInfo
public final class StoreIndexedNode extends AccessIndexedNode implements StateSplit, Lowerable, Virtualizable {

    public static final NodeClass<StoreIndexedNode> TYPE = NodeClass.create(StoreIndexedNode.class);
    @Input ValueNode value;
    @OptionalInput(InputType.State) FrameState stateAfter;

    @Override
    public FrameState stateAfter() {
        return stateAfter;
    }

    @Override
    public void setStateAfter(FrameState x) {
        assert x == null || x.isAlive() : "frame state must be in a graph";
        updateUsages(stateAfter, x);
        stateAfter = x;
    }

    @Override
    public boolean hasSideEffect() {
        return true;
    }

    public ValueNode value() {
        return value;
    }

    public StoreIndexedNode(ValueNode array, ValueNode index, JavaKind elementKind, ValueNode value) {
        super(TYPE, StampFactory.forVoid(), array, index, elementKind);
        this.value = value;
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        ValueNode alias = tool.getAlias(array());
        if (alias instanceof VirtualObjectNode) {
            ValueNode indexValue = tool.getAlias(index());
            int idx = indexValue.isConstant() ? indexValue.asJavaConstant().asInt() : -1;
            VirtualArrayNode virtual = (VirtualArrayNode) alias;
            if (idx >= 0 && idx < virtual.entryCount()) {
                ResolvedJavaType componentType = virtual.type().getComponentType();
                if (componentType.isPrimitive() || StampTool.isPointerAlwaysNull(value) || componentType.getSuperclass() == null ||
                                (StampTool.typeReferenceOrNull(value) != null && componentType.isAssignableFrom(StampTool.typeOrNull(value)))) {
                    tool.setVirtualEntry(virtual, idx, value(), false);
                    tool.delete();
                }
            }
        }
    }

    public FrameState getState() {
        return stateAfter;
    }
}
