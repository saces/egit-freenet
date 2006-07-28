/*
 *    Copyright 2006 Shawn Pearce <spearce@spearce.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.spearce.egit.ui.internal.actions;

import java.util.List;

import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.jface.action.IAction;
import org.spearce.egit.core.op.DisconnectProviderOperation;
import org.spearce.egit.ui.internal.decorators.GitResourceDecorator;

public class Disconnect extends AbstractOperationAction
{
    protected IWorkspaceRunnable createOperation(
        final IAction act,
        final List sel)
    {
        return sel.isEmpty() ? null : new DisconnectProviderOperation(sel);
    }

    protected void postOperation()
    {
        GitResourceDecorator.refresh();
    }
}
