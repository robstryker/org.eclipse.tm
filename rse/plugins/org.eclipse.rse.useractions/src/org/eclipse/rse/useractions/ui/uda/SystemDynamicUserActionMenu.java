/*********************************************************************************
 * Copyright (c) 2008 IBM Corporation. All rights reserved.
 * This program and the accompanying materials are made available under the terms
 * of the Eclipse Public License v1.0 which accompanies this distribution, and is 
 * available at http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 * Xuan Chen (IBM) - [222470] initial contribution.
 *********************************************************************************/
package org.eclipse.rse.useractions.ui.uda;

import java.util.ArrayList;

import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.rse.core.RSECorePlugin;
import org.eclipse.rse.core.model.ISystemProfile;
import org.eclipse.rse.core.subsystems.ISubSystem;
import org.eclipse.rse.core.subsystems.ISubSystemConfiguration;
import org.eclipse.rse.internal.useractions.files.uda.UDActionSubsystemUniversalFiles;
import org.eclipse.rse.internal.useractions.ui.uda.SystemUDAResources;
import org.eclipse.rse.internal.useractions.ui.uda.SystemUDActionSubsystem;
import org.eclipse.rse.internal.useractions.ui.uda.actions.SystemWorkWithUDAsAction;
import org.eclipse.rse.subsystems.files.core.subsystems.IRemoteFile;
import org.eclipse.rse.ui.SystemBasePlugin;
import org.eclipse.rse.ui.view.ISystemRemoteElementAdapter;
import org.eclipse.rse.ui.view.SystemAdapterHelpers;
import org.eclipse.rse.useractions.files.uda.ISystemUDActionSubsystemAdapter;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.CompoundContributionItem;

public class SystemDynamicUserActionMenu extends CompoundContributionItem 
{
	private class UserActionContribution extends ActionContributionItem {
		
		public UserActionContribution(Action action)
		{
			super(action);
		}
		
		/*
		public void fill(Menu menu, int index) 
		{
			
			MenuItem menuItem = new MenuItem(menu, SWT.RADIO);
			menuItem.setText("My First Contribution");
		}
		*/
	}

	protected IContributionItem[] getContributionItems() {
		
		ArrayList returnedItemList = new ArrayList();
		
		ISystemProfile[] activeProfiles = RSECorePlugin.getTheSystemRegistry().getActiveSystemProfiles();
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		ISelection selection = window.getSelectionService().getSelection();
		Object firstSelection = ((IStructuredSelection) selection).getFirstElement();
		if (firstSelection == null || (!(firstSelection instanceof IRemoteFile)))
		{
			return new IContributionItem[0];
		}
		ISystemRemoteElementAdapter rmtAdapter = SystemAdapterHelpers.getRemoteAdapter(firstSelection);
		
		 ISubSystem subsystem = rmtAdapter.getSubSystem(firstSelection);
		 ISubSystemConfiguration ssc = subsystem.getSubSystemConfiguration();
		 
		 SystemUDActionSubsystem systemUDActionSubsystem = null;
		 
		 if (firstSelection instanceof IAdaptable) {
			 ISystemUDActionSubsystemAdapter	adapter = (ISystemUDActionSubsystemAdapter)((IAdaptable)firstSelection).getAdapter(ISystemUDActionSubsystemAdapter.class);
			 if (null != adapter)
			 {
				 systemUDActionSubsystem = adapter.getSystemUDActionSubsystem(ssc);
				 systemUDActionSubsystem.setSubsystem(subsystem);
			 }
		 }
		 
		 if (null == systemUDActionSubsystem)
		 {
			 systemUDActionSubsystem = new UDActionSubsystemUniversalFiles();
			 systemUDActionSubsystem.setSubSystemFactory(ssc);
			 systemUDActionSubsystem.setSubsystem(subsystem);
		 }
		 
		 Shell shell = SystemBasePlugin.getActiveWorkbenchShell();
		for (int idx = 0; idx < activeProfiles.length; idx++)
		{
			 //Xuan: the code for systemUDActionSubsystem#addUserActions go here...
			// systemUDActionSubsystem.addUserActions(menu, (IStructuredSelection)selection, profi, shell);
			 if (null != systemUDActionSubsystem)
			 {
				 Action[] list = systemUDActionSubsystem.addUserActions(null, (IStructuredSelection)selection,  activeProfiles[idx], shell);
				 
				 for (int i = 0; i < list.length; i++)
				 {
					 UserActionContribution testContribution = new UserActionContribution(list[i]);
					 returnedItemList.add(testContribution);
				 }
			 }
		}
			 
		// add a separator before Work With Compile Commands... menu item
		//ourSubMenu.add(new Separator());
		// add Work With Commands... action
		if (returnedItemList.size() > 0)
			returnedItemList.add(new Separator());		
		
		SystemWorkWithUDAsAction wwAction;
		wwAction = new SystemWorkWithUDAsAction(shell, systemUDActionSubsystem.getSubsystem(), systemUDActionSubsystem);
		wwAction.setText(SystemUDAResources.RESID_WORKWITH_UDAS_ACTION_LABEL);
		wwAction.setToolTipText(SystemUDAResources.RESID_WORKWITH_UDAS_ACTION_TOOLTIP);
		wwAction.allowOnMultipleSelection(true);
		wwAction.setSelection(selection);
		UserActionContribution userActionContribution = new UserActionContribution(wwAction);
		returnedItemList.add(userActionContribution);
		
		IContributionItem[] list = (IContributionItem[])returnedItemList.toArray(new IContributionItem[]{});
        return list;
	}

}