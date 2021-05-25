/**********************************************************************
 * Copyright (c) 2022 École Polytechnique de Montréal
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License 2.0 which
 * accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 **********************************************************************/

package org.eclipse.tracecompass.incubator.internal.rocm.core.analysis.handlers;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.tracecompass.incubator.callstack.core.instrumented.statesystem.CallStackStateProvider;
import org.eclipse.tracecompass.incubator.callstack.core.instrumented.statesystem.InstrumentedCallStackAnalysis;
import org.eclipse.tracecompass.incubator.internal.rocm.core.analysis.RocmCallStackStateProvider;
import org.eclipse.tracecompass.incubator.internal.rocm.core.analysis.RocmStrings;
import org.eclipse.tracecompass.incubator.internal.rocm.core.analysis.handlers.HostThreadIdentifier.KERNEL_CATEGORY;
import org.eclipse.tracecompass.incubator.rocm.core.analysis.dependency.IDependencyMaker;
import org.eclipse.tracecompass.incubator.rocm.core.trace.GpuAspect;
import org.eclipse.tracecompass.statesystem.core.ITmfStateSystemBuilder;
import org.eclipse.tracecompass.statesystem.core.exceptions.AttributeNotFoundException;
import org.eclipse.tracecompass.tmf.core.event.ITmfEvent;
import org.eclipse.tracecompass.tmf.core.event.ITmfEventField;
import org.eclipse.tracecompass.tmf.core.statesystem.ITmfStateProvider.FutureEventType;
import org.eclipse.tracecompass.tmf.core.trace.TmfTraceUtils;
import org.eclipse.tracecompass.tmf.ctf.core.trace.CtfTmfTrace;

/**
 * The HIP activity event handler It is possible to have different backends
 * generate the GPU activity events. This event handler is for the GPU activity
 * events generated by HIP.
 *
 * @author Arnaud Fiorini
 */
public class HipActivityEventHandler extends AbstractGpuEventHandler {

    /**
     * @param stateProvider
     *            The state provider that is using this event handler
     */
    public HipActivityEventHandler(RocmCallStackStateProvider stateProvider) {
        super(stateProvider);
    }

    @Override
    public void handleEvent(ITmfStateSystemBuilder ssb, ITmfEvent event) throws AttributeNotFoundException {
        // Can be memory tranfer event or kernel execution event
        int callStackQuark = getCallStackQuark(ssb, event);

        ITmfEventField content = event.getContent();
        Long timestamp = event.getTimestamp().toNanos();
        String eventName = content.getFieldValue(String.class, RocmStrings.NAME);

        if (eventName != null && eventName.equals(RocmStrings.KERNEL_EXECUTION)) {
            int hipStreamCallStackQuark = -1;
            Long correlationId = content.getFieldValue(Long.class, RocmStrings.CORRELATION_ID);
            int gpuId = getGpuId(event);
            int hipStreamId = -1;
            // Placeholder value in case we do not match any api event.
            String kernelName = RocmStrings.KERNEL_EXECUTION;
            IDependencyMaker dependencyMaker = fStateProvider.getDependencyMaker();
            if (dependencyMaker != null) {
                Map<Long, ITmfEvent> apiEventCorrelationMap = dependencyMaker.getApiEventCorrelationMap();
                ITmfEvent apiEvent = apiEventCorrelationMap.get(correlationId);
                if (apiEvent != null) {
                    hipStreamId = Integer.parseInt(ApiEventHandler.getArg(apiEvent.getContent(), 4));
                    hipStreamCallStackQuark = getHipStreamCallStackQuark(ssb, apiEvent, gpuId);
                    kernelName = ApiEventHandler.getArg(apiEvent.getContent(), 6);
                }
            }
            Integer queueId = content.getFieldValue(Integer.class, RocmStrings.QUEUE_ID);
            if (queueId == null || correlationId == null) {
                return;
            }
            Long timestampEnd = content.getFieldValue(Long.class, RocmStrings.END);
            if (timestampEnd != null) {
                pushParallelActivityOnCallStack(ssb, callStackQuark, kernelName, timestamp,
                        ((CtfTmfTrace) event.getTrace()).timestampCyclesToNanos(timestampEnd));
                if (hipStreamCallStackQuark > 0) {
                    pushParallelActivityOnCallStack(ssb, hipStreamCallStackQuark, kernelName,
                            timestamp, ((CtfTmfTrace) event.getTrace()).timestampCyclesToNanos(timestampEnd));
                }
            }

            // Add Host Thread Identifier for dependency arrows
            HostThreadIdentifier queueHostThreadIdentifier = new HostThreadIdentifier(queueId.intValue(), KERNEL_CATEGORY.QUEUE, gpuId);
            addHostIdToStateSystemIfNotDefined(ssb, event.getTrace(), queueHostThreadIdentifier, callStackQuark);
            if (hipStreamCallStackQuark > 0) {
                HostThreadIdentifier streamHostThreadIdentifier = new HostThreadIdentifier(hipStreamId, KERNEL_CATEGORY.STREAM, gpuId);
                addHostIdToStateSystemIfNotDefined(ssb, event.getTrace(), streamHostThreadIdentifier, hipStreamCallStackQuark);
            }
        } else {
            if (eventName == null) {
                ssb.modifyAttribute(timestamp, null, callStackQuark);
                return;
            }
            Long timestampEnd = content.getFieldValue(Long.class, RocmStrings.END);
            ssb.pushAttribute(timestamp, eventName, callStackQuark);
            if (timestampEnd != null) {
                fStateProvider.addFutureEvent(((CtfTmfTrace) event.getTrace()).timestampCyclesToNanos(timestampEnd),
                        timestampEnd, callStackQuark, FutureEventType.POP);
            }
            // Add CallStack Identifier (tid equivalent) for the memory quark
            HostThreadIdentifier hostThreadIdentifier = new HostThreadIdentifier();
            addHostIdToStateSystemIfNotDefined(ssb, event.getTrace(), hostThreadIdentifier, callStackQuark);
        }
    }

    private static int getCallStackQuark(ITmfStateSystemBuilder ssb, ITmfEvent event) {
        String eventName = event.getContent().getFieldValue(String.class, RocmStrings.NAME);
        if (eventName == null) {
            return -1;
        }
        if (eventName.equals(RocmStrings.KERNEL_EXECUTION)) {
            Long queueId = event.getContent().getFieldValue(Long.class, RocmStrings.QUEUE_ID);
            Long gpuId = event.getContent().getFieldValue(Long.class, RocmStrings.DEVICE_ID);
            if (queueId == null || gpuId == null) {
                return -1;
            }
            int gpuQuark = ssb.getQuarkAbsoluteAndAdd(CallStackStateProvider.PROCESSES, RocmStrings.GPU + gpuId.toString());
            int queuesQuark = ssb.getQuarkRelativeAndAdd(gpuQuark, RocmStrings.QUEUES);
            int queueQuark = ssb.getQuarkRelativeAndAdd(queuesQuark, RocmStrings.QUEUE + Long.toString(queueId));
            return ssb.getQuarkRelativeAndAdd(queueQuark, InstrumentedCallStackAnalysis.CALL_STACK);
        }
        int copyQuark = ssb.getQuarkAbsoluteAndAdd(CallStackStateProvider.PROCESSES, RocmStrings.MEMORY);
        int tempQuark1 = ssb.getQuarkRelativeAndAdd(copyQuark, StringUtils.EMPTY);
        int tempQuark2 = ssb.getQuarkRelativeAndAdd(tempQuark1, RocmStrings.MEMORY_TRANSFERS);
        return ssb.getQuarkRelativeAndAdd(tempQuark2, InstrumentedCallStackAnalysis.CALL_STACK);
    }

    private static int getHipStreamCallStackQuark(ITmfStateSystemBuilder ssb, @NonNull ITmfEvent event, Integer gpuId) {
        int gpuQuark = ssb.getQuarkAbsoluteAndAdd(CallStackStateProvider.PROCESSES, RocmStrings.GPU + gpuId.toString());
        int hipStreamsQuark = ssb.getQuarkRelativeAndAdd(gpuQuark, RocmStrings.STREAMS);
        int hipStreamId = Integer.parseInt(ApiEventHandler.getArg(event.getContent(), 4));
        int hipStreamQuark = ssb.getQuarkRelativeAndAdd(hipStreamsQuark, RocmStrings.STREAM + Integer.toString(hipStreamId));
        return ssb.getQuarkRelativeAndAdd(hipStreamQuark, InstrumentedCallStackAnalysis.CALL_STACK);
    }

    private static int getGpuId(ITmfEvent event) {
        Integer gpuId = (Integer) TmfTraceUtils.resolveEventAspectOfClassForEvent(event.getTrace(), GpuAspect.class, event);
        if (gpuId != null) {
            return gpuId;
        }
        return -1;
    }
}
