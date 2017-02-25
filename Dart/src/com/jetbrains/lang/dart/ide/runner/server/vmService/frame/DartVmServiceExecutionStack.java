package com.jetbrains.lang.dart.ide.runner.server.vmService.frame;

import com.intellij.icons.AllIcons;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XStackFrame;
import com.jetbrains.lang.dart.ide.runner.server.vmService.DartVmServiceDebugProcess;
import org.dartlang.vm.service.element.Frame;
import org.dartlang.vm.service.element.InstanceRef;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;

public class DartVmServiceExecutionStack extends XExecutionStack {
  private final DartVmServiceDebugProcess myDebugProcess;
  private final String myIsolateId;
  @Nullable private final XStackFrame myTopFrame;
  @Nullable private final InstanceRef myException;

  public DartVmServiceExecutionStack(@NotNull final DartVmServiceDebugProcess debugProcess,
                                     @NotNull final String isolateId,
                                     @NotNull final String isolateName,
                                     @Nullable final Frame topFrame,
                                     @Nullable final InstanceRef exception) {
    // topFrame is not null for (and only for) the active execution stack
    super(debugProcess.isIsolateSuspended(isolateId) ? isolateName
                                                     : isolateName + " (running)",
          topFrame != null ? AllIcons.Debugger.ThreadCurrent
                           : debugProcess.isIsolateSuspended(isolateId) ? AllIcons.Debugger.ThreadAtBreakpoint
                                                                        : AllIcons.Debugger.ThreadRunning);
    myDebugProcess = debugProcess;
    myIsolateId = isolateId;
    myException = exception;
    myTopFrame = topFrame == null ? null : new DartVmServiceStackFrame(debugProcess, isolateId, topFrame, null, exception);
  }

  @Nullable
  @Override
  public XStackFrame getTopFrame() {
    // engine calls getTopFrame for active execution stack only, for which myTopFrame is calculated in constructor
    return myTopFrame;
  }

  @Override
  public void computeStackFrames(final int firstFrameIndex, @NotNull final XStackFrameContainer container) {
    if (myDebugProcess.isIsolateSuspended(myIsolateId)) {
      myDebugProcess.getVmServiceWrapper().computeStackFrames(myIsolateId, firstFrameIndex, container, myException);
    }
    else {
      container.addStackFrames(Collections.emptyList(), true);
    }
  }

  public String getIsolateId() {
    return myIsolateId;
  }
}
