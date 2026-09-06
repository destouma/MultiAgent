package com.multiagent.desktop.action;

/**
 * Gate a mutating action behind explicit approval before it runs. Implementations may be
 * called from a background thread (the tool-loop executor) and are expected to block that
 * thread until the user responds - e.g. by marshaling onto the UI thread to show a dialog
 * and waiting on the result. There is deliberately no "auto-approve" implementation in
 * production code: ChatService/ToolLoopRunner treat a null approver as auto-approve only
 * as a testing convenience (see ToolLoopRunner's javadoc), so real usage always has one
 * installed from App.java.
 */
public interface ActionApprover {
    boolean approve(PendingAction action);
}
