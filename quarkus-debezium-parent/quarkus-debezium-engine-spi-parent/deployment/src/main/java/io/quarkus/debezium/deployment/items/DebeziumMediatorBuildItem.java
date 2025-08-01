/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.quarkus.debezium.deployment.items;

import org.jboss.jandex.DotName;
import org.jboss.jandex.MethodInfo;

import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.builder.item.MultiBuildItem;

/**
 * Represents a method annotated with {@code Capturing, PostProcessing}
 */
public final class DebeziumMediatorBuildItem extends MultiBuildItem {
    private final BeanInfo bean;
    private final MethodInfo methodInfo;
    private final DotName dotName;

    public DebeziumMediatorBuildItem(BeanInfo bean, MethodInfo methodInfo, DotName dotName) {
        this.bean = bean;
        this.methodInfo = methodInfo;
        this.dotName = dotName;
    }

    public BeanInfo getBean() {
        return bean;
    }

    public MethodInfo getMethodInfo() {
        return methodInfo;
    }

    public DotName getDotName() {
        return dotName;
    }
}
