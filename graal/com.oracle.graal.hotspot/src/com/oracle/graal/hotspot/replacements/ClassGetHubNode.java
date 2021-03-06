/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.replacements;

import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.LocationIdentity;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaType;

import com.oracle.graal.compiler.common.calc.Condition;
import com.oracle.graal.graph.Node;
import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.graph.spi.Canonicalizable;
import com.oracle.graal.graph.spi.CanonicalizerTool;
import com.oracle.graal.hotspot.nodes.type.KlassPointerStamp;
import com.oracle.graal.hotspot.word.KlassPointer;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodes.ConstantNode;
import com.oracle.graal.nodes.FloatingGuardedNode;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.calc.ConvertNode;
import com.oracle.graal.nodes.extended.GetClassNode;
import com.oracle.graal.nodes.extended.GuardingNode;
import com.oracle.graal.nodes.extended.LoadHubNode;
import com.oracle.graal.nodes.memory.ReadNode;
import com.oracle.graal.nodes.memory.address.AddressNode;
import com.oracle.graal.nodes.spi.Lowerable;
import com.oracle.graal.nodes.spi.LoweringTool;

/**
 * Read {@code Class::_klass} to get the hub for a {@link java.lang.Class}. This node mostly exists
 * to replace {@code _klass._java_mirror._klass} with {@code _klass}. The constant folding could be
 * handled by
 * {@link ReadNode#canonicalizeRead(ValueNode, AddressNode, LocationIdentity, CanonicalizerTool)}.
 */
@NodeInfo
public final class ClassGetHubNode extends FloatingGuardedNode implements Lowerable, Canonicalizable, ConvertNode {
    public static final NodeClass<ClassGetHubNode> TYPE = NodeClass.create(ClassGetHubNode.class);
    @Input protected ValueNode clazz;

    public ClassGetHubNode(ValueNode clazz) {
        this(clazz, null);
    }

    public ClassGetHubNode(ValueNode clazz, ValueNode guard) {
        super(TYPE, KlassPointerStamp.klass(), (GuardingNode) guard);
        this.clazz = clazz;
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (tool.allUsagesAvailable() && hasNoUsages()) {
            return null;
        } else {
            if (clazz.isConstant()) {
                MetaAccessProvider metaAccess = tool.getMetaAccess();
                if (metaAccess != null) {
                    ResolvedJavaType exactType = tool.getConstantReflection().asJavaType(clazz.asJavaConstant());
                    if (exactType.isPrimitive()) {
                        return ConstantNode.forConstant(stamp(), JavaConstant.NULL_POINTER, metaAccess);
                    } else {
                        return ConstantNode.forConstant(stamp(), tool.getConstantReflection().asObjectHub(exactType), metaAccess);
                    }
                }
            }
            if (clazz instanceof GetClassNode) {
                GetClassNode getClass = (GetClassNode) clazz;
                return new LoadHubNode(KlassPointerStamp.klassNonNull(), getClass.getObject());
            }
            if (clazz instanceof HubGetClassNode) {
                // replace _klass._java_mirror._klass -> _klass
                return ((HubGetClassNode) clazz).getHub();
            }
            return this;
        }
    }

    @Override
    public void lower(LoweringTool tool) {
        tool.getLowerer().lower(this, tool);
    }

    @NodeIntrinsic
    public static native KlassPointer readClass(Class<?> clazz);

    @NodeIntrinsic
    public static native KlassPointer readClass(Class<?> clazz, GuardingNode guard);

    @Override
    public ValueNode getValue() {
        return clazz;
    }

    @Override
    public Constant convert(Constant c, ConstantReflectionProvider constantReflection) {
        ResolvedJavaType exactType = constantReflection.asJavaType(c);
        if (exactType.isPrimitive()) {
            return JavaConstant.NULL_POINTER;
        } else {
            return constantReflection.asObjectHub(exactType);
        }
    }

    @Override
    public Constant reverse(Constant c, ConstantReflectionProvider constantReflection) {
        assert !c.equals(JavaConstant.NULL_POINTER);
        ResolvedJavaType objectType = constantReflection.asJavaType(c);
        return constantReflection.asJavaClass(objectType);
    }

    @Override
    public boolean isLossless() {
        return false;
    }

    @Override
    public boolean preservesOrder(Condition op, Constant value, ConstantReflectionProvider constantReflection) {
        assert op == Condition.EQ || op == Condition.NE;
        ResolvedJavaType exactType = constantReflection.asJavaType(value);
        return !exactType.isPrimitive();
    }

}
