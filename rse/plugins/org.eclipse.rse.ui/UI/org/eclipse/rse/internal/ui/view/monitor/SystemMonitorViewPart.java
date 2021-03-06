/********************************************************************************
 * Copyright (c) 2002, 2012 IBM Corporation and others. All rights reserved.
 * This program and the accompanying materials are made available under the terms
 * of the Eclipse Public License v1.0 which accompanies this distribution, and is 
 * available at http://www.eclipse.org/legal/epl-v10.html
 * 
 * Initial Contributors:
 * The following IBM employees contributed to the Remote System Explorer
 * component that contains this file: David McKnight, Kushal Munir, 
 * Michael Berger, David Dykstal, Phil Coulthard, Don Yantzi, Eric Simpson, 
 * Emily Bruner, Mazen Faraj, Adrian Storisteanu, Li Ding, and Kent Hawley.
 * 
 * Contributors:
 * Michael Berger (IBM) - 146339 Added refresh action graphic.
 * Martin Oberhuber (Wind River) - [168975] Move RSE Events API to Core
 * Martin Oberhuber (Wind River) - [186773] split ISystemRegistryUI from ISystemRegistry
 * Kevin Doyle (IBM) - [177587] Made MonitorViewPart a SelectionProvider
 * Kevin Doyle (IBM) - [160378] Subset action should be disabled when there are no tabs in Monitor
 * Kevin Doyle (IBM) - [196582] ClassCastException when doing copy/paste
 * Kevin Doyle		(IBM)		 - [212940] Duplicate Help Context Identifiers
 * David McKnight   (IBM)        - [223103] [cleanup] fix broken externalized strings
 * David McKnight   (IBM)        - [225506] [api][breaking] RSE UI leaks non-API types
 * Zhou Renjian     (Kortide)    - [282239] Monitor view does not update icon according to connection status
 * David McKnight   (IBM)        - [294663] bad cast in monitor view part refresh action
 * David McKnight   (IBM)        - [340912] inconsistencies with columns in RSE table viewers
 * David McKnight   (IBM)        - [372674] Enhancement - Preserve state of Remote Monitor view
 ********************************************************************************/

package org.eclipse.rse.internal.ui.view.monitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Vector;

import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.window.Window;
import org.eclipse.rse.core.RSECorePlugin;
import org.eclipse.rse.core.events.ISystemRemoteChangeEvent;
import org.eclipse.rse.core.events.ISystemRemoteChangeEvents;
import org.eclipse.rse.core.events.ISystemRemoteChangeListener;
import org.eclipse.rse.core.events.ISystemResourceChangeEvent;
import org.eclipse.rse.core.events.ISystemResourceChangeEvents;
import org.eclipse.rse.core.events.ISystemResourceChangeListener;
import org.eclipse.rse.core.filters.ISystemFilterReference;
import org.eclipse.rse.core.model.IHost;
import org.eclipse.rse.core.model.IRSECallback;
import org.eclipse.rse.core.model.ISystemContainer;
import org.eclipse.rse.core.model.ISystemProfile;
import org.eclipse.rse.core.model.ISystemRegistry;
import org.eclipse.rse.core.subsystems.ISubSystem;
import org.eclipse.rse.internal.ui.SystemPropertyResources;
import org.eclipse.rse.internal.ui.SystemResources;
import org.eclipse.rse.services.clientserver.messages.SystemMessage;
import org.eclipse.rse.ui.ISystemIconConstants;
import org.eclipse.rse.ui.RSEUIPlugin;
import org.eclipse.rse.ui.SystemPreferencesManager;
import org.eclipse.rse.ui.SystemWidgetHelpers;
import org.eclipse.rse.ui.dialogs.SystemPromptDialog;
import org.eclipse.rse.ui.messages.ISystemMessageLine;
import org.eclipse.rse.ui.model.ISystemShellProvider;
import org.eclipse.rse.ui.view.IRSEViewPart;
import org.eclipse.rse.ui.view.ISystemTableViewColumnManager;
import org.eclipse.rse.ui.view.ISystemViewElementAdapter;
import org.eclipse.rse.ui.view.SystemTableView;
import org.eclipse.rse.ui.view.SystemTableViewProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.ISelectionService;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.CellEditorActionHandler;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.views.properties.IPropertyDescriptor;

/**
 * This is the desktop view wrapper of the System View viewer.
 */
public class SystemMonitorViewPart
	extends ViewPart
	implements
		ISelectionListener,
		SelectionListener,
		ISelectionChangedListener,
		ISystemResourceChangeListener,
		ISystemShellProvider,
		ISystemRemoteChangeListener,
		ISystemMessageLine,
		IRSEViewPart,
		ISelectionProvider
{
	class RestoreStateRunnable extends Job
	{
		private IMemento _rmemento;
		public RestoreStateRunnable(IMemento memento)
		{
			super(SystemResources.RESID_RESTORE_RSE_MONITOR_JOB); 
			_rmemento = memento;
		}

		public IStatus run(IProgressMonitor monitor)
		{
			try {
				IStatus wstatus = RSECorePlugin.waitForInitCompletion();
				if (!wstatus.isOK() && wstatus.getSeverity() == IStatus.ERROR){
					return wstatus;
				}
			}
			catch (InterruptedException e){				
				return Status.CANCEL_STATUS;
			}
			
			Integer tabCountInt = _memento.getInteger(TAG_MONITOR_TAB_COUNT_ID);
			if (tabCountInt != null){
				int tabCount = tabCountInt.intValue();
				for (int i = 0; i < tabCount && !monitor.isCanceled(); i++){
					restoreTab(i, monitor);
				}
			}
			return Status.OK_STATUS;
		}
		
		protected void restoreTab(int index, IProgressMonitor monitor){			
			final IMemento memento = _rmemento;
			
			// matches new format for column width memento
			// new code - as of RSE 3.1
			final HashMap cachedColumnWidths = new HashMap();
			
			// set the cached column widths (for later use)
			String columnWidths = memento.getString(TAG_MONITOR_TAB_COLUMN_WIDTHS_ID+index);			
			if (columnWidths != null){			
				if (columnWidths.indexOf(";") > 0){	//$NON-NLS-1$

	
					// parse out set of columns
					String[] columnSets = columnWidths.split(";"); //$NON-NLS-1$
					for (int i = 0; i < columnSets.length; i++){
						String columnSet = columnSets[i];
						
						// parse out columns for set
						String[] pair = columnSet.split("="); //$NON-NLS-1$
						String key = pair[0];
	
						// parse out widths
						String widthArray = pair[1];
						String[] widthStrs = widthArray.split(","); //$NON-NLS-1$
						
						int[] widths = new int[widthStrs.length];
						for (int w = 0; w < widths.length; w++){
							widths[w] = Integer.parseInt(widthStrs[w]);
						}	
						cachedColumnWidths.put(key, widths);
					}										
				}
			}
			
			Boolean pollingOnBool = memento.getBoolean(TAG_MONITOR_TAB_POLLING_ON_ID+index);
			Integer pollingIntervalInteger = memento.getInteger(TAG_MONITOR_TAB_POLLING_INTERVAL_ID+index);
			boolean pollingOn = false;
			int pollingInterval = 100;
			if (pollingOnBool != null){
				pollingOn = pollingOnBool.booleanValue();
			}
			if (pollingIntervalInteger != null){
				pollingInterval = pollingIntervalInteger.intValue();
			}
			
						
			String profileId = memento.getString(TAG_MONITOR_TAB_PROFILE_ID+index);
			String connectionId = memento.getString(TAG_MONITOR_TAB_CONNECTION_ID+index);
			String subsystemId = memento.getString(TAG_MONITOR_TAB_SUBSYSTEM_ID+index);
			final String filterID = memento.getString(TAG_MONITOR_TAB_FILTER_ID+index);
			final String objectID = memento.getString(TAG_MONITOR_TAB_OBJECT_ID+index);

			ISystemRegistry registry = RSECorePlugin.getTheSystemRegistry();

			Object input = null;
			if (subsystemId == null){
				if (connectionId != null){
					ISystemProfile profile = registry.getSystemProfile(profileId);
					input = registry.getHost(profile, connectionId);
				}
				else{
				    // 191288 we now use registry instead of registry ui as input
					input = registry;
				}
			}
			else {
				// from the subsystem ID determine the profile, system and subsystem
				final ISubSystem subsystem = registry.getSubSystem(subsystemId);

				if (subsystem != null) {
					if (filterID == null && objectID == null) {
						input = subsystem;
					}
					else {
						if (!subsystem.isConnected()) {
							try {
								final Object finInput = input;
								final boolean fpollingOn = pollingOn;
								final int fpollingInterval = pollingInterval;
								subsystem.connect(false, new IRSECallback() {
									public void done(IStatus status, Object result) {										
										// this needs to be done on the main thread
										// so doing an asynchExec()
										runOnceConnected(new NullProgressMonitor(), finInput, subsystem, filterID, objectID, cachedColumnWidths, fpollingOn, fpollingInterval);
									}
								});
							}
							catch (Exception e) {
							}
						}
						else {
							runOnceConnected(monitor, input, subsystem, filterID, objectID, cachedColumnWidths, pollingOn, pollingInterval);
						}
						return;
					} // end else
				} // end if (subsystem != null)
			} // end else
			if (input != null){
				runWithInput(monitor, input, cachedColumnWidths, pollingOn, pollingInterval);
			}
		}


		public IStatus runOnceConnected(IProgressMonitor monitor, Object input, ISubSystem subsystem, String filterID, String objectID, 
				HashMap cachedColumnWidths, boolean pollingOn, int pollingInterval)
		{
			if (monitor.isCanceled()){
				return Status.CANCEL_STATUS;
			}
			if (subsystem.isConnected()) {
				if (filterID != null) {
					try {
						input = subsystem.getObjectWithAbsoluteName(filterID, monitor);
					}
					catch (Exception e) {
						//ignore
					}
				}
				else {
					if (objectID != null) {
						try {
							input = subsystem.getObjectWithAbsoluteName(objectID, monitor);
						}
						catch (Exception e)	{
							return Status.CANCEL_STATUS;
						}
					}
				} // end else
			} // end if (subsystem.isConnected)
			
			if (input != null){
				runWithInput(monitor, input, cachedColumnWidths, pollingOn, pollingInterval);
				return Status.OK_STATUS;
			}
			else {
				return Status.CANCEL_STATUS;
			}
		}
		
		private class WaitForAdapterJob extends Job {
			private IAdaptable _input;
			private HashMap _cachedColumnWidths;
			private boolean _pollingOn;
			private int _pollingInterval;
			public WaitForAdapterJob(IAdaptable input, HashMap cachedColumnWidths, boolean pollingOn, int pollingInterval){
				super(SystemResources.RESID_RESTORE_RSE_MONITOR_JOB);
				_input = input;
				_cachedColumnWidths = cachedColumnWidths;				
				_pollingOn = pollingOn;
				_pollingInterval = pollingInterval;
			}
			
			protected IStatus run(IProgressMonitor monitor) {
				ISystemViewElementAdapter adapter = (ISystemViewElementAdapter)_input.getAdapter(ISystemViewElementAdapter.class);
				while (adapter == null || monitor.isCanceled()){ 
					try {
						synchronized (_input){
							_input.wait(1000);
						}
					} catch (InterruptedException e) {
					}
					adapter = (ISystemViewElementAdapter)_input.getAdapter(ISystemViewElementAdapter.class);
				}
				if (monitor.isCanceled()){
					return Status.CANCEL_STATUS;
				}
				
				// got an adapter now
				// set input needs to be run on the main thread
				Display.getDefault().asyncExec(new Runnable()
				{
					public void run(){
						addItemToMonitor(_input);
						MonitorViewPage page = _folder.getCurrentTabItem(); // get the viewer
						
						// restore column widths
						page.getViewer().setCachedColumnWidths(_cachedColumnWidths);
						
						// restoring polling
						page.setPollingEnabled(_pollingOn);
						page.setPollingInterval(_pollingInterval);
					}
				});
				
				return Status.OK_STATUS;
			}			
		}

		public IStatus runWithInput(IProgressMonitor monitor, Object input, HashMap cachedColumnWidths, boolean pollingOn, int pollingInterval)
		{
			if (input != null && input instanceof IAdaptable){
				final IAdaptable mementoInput = (IAdaptable) input;
				final HashMap fcachedColumnWidths = cachedColumnWidths;
				final boolean fpollingOn = pollingOn;
				final int fpollingInterval = pollingInterval;
				if (mementoInput != null)
				{
					// first make sure the adapter factories are ready
					ISystemViewElementAdapter adapter = (ISystemViewElementAdapter)mementoInput.getAdapter(ISystemViewElementAdapter.class);
					if (adapter == null){
						WaitForAdapterJob job = new WaitForAdapterJob(mementoInput, cachedColumnWidths, pollingOn, pollingInterval);
						job.schedule();
					}
					else {						
						// set input needs to be run on the main thread
						Display.getDefault().asyncExec(new Runnable(){
							public void run(){
								addItemToMonitor(mementoInput);
								MonitorViewPage page = _folder.getCurrentTabItem(); // get the viewer
								
								// restore column widths
								page.getViewer().setCachedColumnWidths(fcachedColumnWidths);
								
								// restoring polling
								page.setPollingEnabled(fpollingOn);
								page.setPollingInterval(fpollingInterval);
							}
						});
					}
				}
			}
			return Status.OK_STATUS;
		}

	}


	
	
	// Restore memento tags
	public static final String TAG_MONITOR_TAB_COUNT_ID = "monitorTabCountID"; //$NON-NLS-1$
	
	public static final String TAG_MONITOR_TAB_PROFILE_ID = "monitorTabProfileID_"; //$NON-NLS-1$
	public static final String TAG_MONITOR_TAB_CONNECTION_ID = "monitorTabConnectionID_"; //$NON-NLS-1$
	public static final String TAG_MONITOR_TAB_SUBSYSTEM_ID = "monitorTabSubsystemID_"; //$NON-NLS-1$
	public static final String TAG_MONITOR_TAB_OBJECT_ID = "monitorTabObjectID_"; //$NON-NLS-1$
	public static final String TAG_MONITOR_TAB_FILTER_ID = "monitorTabFilterID_"; //$NON-NLS-1$

	// Subset memento tags
	public static final String TAG_MONITOR_TAB_SUBSET_ID = "monitorTabSubsetID_"; //$NON-NLS-1$

	// polling
	public static final String TAG_MONITOR_TAB_POLLING_ON_ID = "monitorTabPollingOnID_"; //$NON-NLS-1$
	public static final String TAG_MONITOR_TAB_POLLING_INTERVAL_ID= "monitorTabPollingIntervalID_"; // $NON-NLS-1$
	
	// layout memento tags
	public static final String TAG_MONITOR_TAB_COLUMN_WIDTHS_ID = "monitorTabColumnWidths_"; //$NON-NLS-1$
	


	
	
	class PositionToAction extends BrowseAction
	{
		class PositionToDialog extends SystemPromptDialog
		{
			private String _name;
			private Combo _cbName;


			public PositionToDialog(Shell shell, String title)
			{
				super(shell, title);
			}

			public String getPositionName()
			{
				return _name;
			}

			protected void buttonPressed(int buttonId)
			{
				setReturnCode(buttonId);
				_name = _cbName.getText();
				close();
			}

			protected Control getInitialFocusControl()
			{
				return _cbName;
			}

			public Control createInner(Composite parent)
			{
				Composite c = SystemWidgetHelpers.createComposite(parent, 2);

				Label aLabel = new Label(c, SWT.NONE);
				aLabel.setText(SystemPropertyResources.RESID_PROPERTY_NAME_LABEL);

				_cbName = SystemWidgetHelpers.createCombo(c, null);
				GridData textData = new GridData(GridData.HORIZONTAL_ALIGN_FILL | GridData.GRAB_HORIZONTAL);
				_cbName.setLayoutData(textData);
				_cbName.setText("*"); //$NON-NLS-1$
				_cbName.setToolTipText(SystemResources.RESID_TABLE_POSITIONTO_ENTRY_TOOLTIP);

				this.getShell().setText(SystemResources.RESID_TABLE_POSITIONTO_LABEL);
				setHelp();
				return c;
			}

			private void setHelp()
			{
				setHelp(RSEUIPlugin.HELPPREFIX + "gnpt0000"); //$NON-NLS-1$
			}
		}

		public PositionToAction()
		{
			super(SystemMonitorViewPart.this, SystemResources.ACTION_POSITIONTO_LABEL, null);
			setToolTipText(SystemResources.ACTION_POSITIONTO_TOOLTIP);
		}

		public void run()
		{

			PositionToDialog posDialog = new PositionToDialog(getViewer().getShell(), getTitle());
			if (posDialog.open() == Window.OK)
			{
				String name = posDialog.getPositionName();

				getViewer().positionTo(name);
			}
		}
	}

class SubSetAction extends BrowseAction
	{
		class SubSetDialog extends SystemPromptDialog
		{
			private String[] _filters;
			private Text[] _controls;
			private IPropertyDescriptor[] _uniqueDescriptors;


			public SubSetDialog(Shell shell, IPropertyDescriptor[] uniqueDescriptors)
			{
				super(shell, SystemResources.RESID_TABLE_SUBSET_LABEL);
				_uniqueDescriptors = uniqueDescriptors;
			}

			public String[] getFilters()
			{
				return _filters;
			}

			protected void buttonPressed(int buttonId)
			{
				setReturnCode(buttonId);

				for (int i = 0; i < _controls.length; i++)
				{
					_filters[i] = _controls[i].getText();
				}

				close();
			}

			protected Control getInitialFocusControl()
			{
				return _controls[0];
			}

			public Control createInner(Composite parent)
			{
				Composite c = SystemWidgetHelpers.createComposite(parent, 2);

				int numberOfFields = _uniqueDescriptors.length;
				_controls = new Text[numberOfFields + 1];
				_filters = new String[numberOfFields + 1];

				Label nLabel = new Label(c, SWT.NONE);
				nLabel.setText(SystemPropertyResources.RESID_PROPERTY_NAME_LABEL);


				_controls[0] = SystemWidgetHelpers.createTextField(c, null);
				GridData textData = new GridData(GridData.HORIZONTAL_ALIGN_FILL | GridData.GRAB_HORIZONTAL);
				_controls[0].setLayoutData(textData);
				_controls[0].setText("*"); //$NON-NLS-1$
				_controls[0].setToolTipText(SystemResources.RESID_TABLE_SUBSET_ENTRY_TOOLTIP);



				for (int i = 0; i < numberOfFields; i++)
				{
					IPropertyDescriptor des = _uniqueDescriptors[i];

					Label aLabel = new Label(c, SWT.NONE);
					aLabel.setText(des.getDisplayName());

					_controls[i + 1] = SystemWidgetHelpers.createTextField(c, null);
					GridData textData3 = new GridData(GridData.HORIZONTAL_ALIGN_FILL | GridData.GRAB_HORIZONTAL);
					_controls[i + 1].setLayoutData(textData3);
					_controls[i + 1].setText("*"); //$NON-NLS-1$
				}

				setHelp();
				return c;
			}

			private void setHelp()
			{
				setHelp(RSEUIPlugin.HELPPREFIX + "gnss0000"); //$NON-NLS-1$
			}
		}

		public SubSetAction()
		{
			super(SystemMonitorViewPart.this, SystemResources.ACTION_SUBSET_LABEL, null);
			setToolTipText(SystemResources.ACTION_SUBSET_TOOLTIP);
		}

		public void run()
		{
			SubSetDialog subsetDialog = new SubSetDialog(getViewer().getShell(), getViewer().getVisibleDescriptors(getViewer().getInput()));
			if (subsetDialog.open() == Window.OK)
			{
				String[] filters = subsetDialog.getFilters();
				getViewer().setViewFilters(filters);

			}
		}
	}

	
	
	class RefreshAction extends BrowseAction
	{
		public RefreshAction()
		{
			super(SystemMonitorViewPart.this, SystemResources.ACTION_REFRESH_LABEL, 
					//RSEUIPlugin.getDefault().getImageDescriptor(ICON_SYSTEM_REFRESH_ID));
					RSEUIPlugin.getDefault().getImageDescriptor(ISystemIconConstants.ICON_SYSTEM_REFRESH_ID));
			setTitleToolTip(SystemResources.ACTION_REFRESH_TOOLTIP);
		}

		public void run()
		{
			Object inputObject = getViewer().getInput();
			if (inputObject instanceof ISystemContainer)
			{
				((ISystemContainer)inputObject).markStale(true);
			}
			((SystemTableViewProvider) getViewer().getContentProvider()).flushCache();
			getViewer().refresh();

			// refresh layout too
			//_viewer.computeLayout(true);

		}
	}
	private class SelectColumnsAction extends BrowseAction
	{
	    
	    class SelectColumnsDialog extends SystemPromptDialog
		{
	        private ISystemViewElementAdapter _adapter;
	        private ISystemTableViewColumnManager _columnManager;
			private IPropertyDescriptor[] _uniqueDescriptors;
			private ArrayList _currentDisplayedDescriptors;
			private ArrayList _availableDescriptors;
			
			private List _availableList;
			private List _displayedList;
			
			private Button _addButton;
			private Button _removeButton;
			private Button _upButton;
			private Button _downButton;
			
			private boolean _changed = false;
			
			public SelectColumnsDialog(Shell shell, ISystemViewElementAdapter viewAdapter, ISystemTableViewColumnManager columnManager, int[] originalOrder)
			{
				super(shell, SystemResources.RESID_TABLE_SELECT_COLUMNS_LABEL);
				setToolTipText(SystemResources.RESID_TABLE_SELECT_COLUMNS_TOOLTIP);
				setInitialOKButtonEnabledState(_changed);
				_adapter = viewAdapter;
				_columnManager = columnManager;
				_uniqueDescriptors = viewAdapter.getUniquePropertyDescriptors();
				IPropertyDescriptor[] initialDisplayedDescriptors = _columnManager.getVisibleDescriptors(_adapter);
								
				IPropertyDescriptor[] sortedDisplayedDescriptors = new IPropertyDescriptor[initialDisplayedDescriptors.length];
				for (int i = 0; i < initialDisplayedDescriptors.length; i++){
					int position = originalOrder[i+1];
					sortedDisplayedDescriptors[i] = initialDisplayedDescriptors[position-1];
				}				
				_currentDisplayedDescriptors = new ArrayList(initialDisplayedDescriptors.length);
				for (int i = 0; i < sortedDisplayedDescriptors.length;i++)
				{					
					_currentDisplayedDescriptors.add(sortedDisplayedDescriptors[i]);				
				}
				_availableDescriptors = new ArrayList(_uniqueDescriptors.length);
				for (int i = 0; i < _uniqueDescriptors.length;i++)
				{
				    if (!_currentDisplayedDescriptors.contains(_uniqueDescriptors[i]))
				    {
				        _availableDescriptors.add(_uniqueDescriptors[i]);
				    }
				}
			}




			public void handleEvent(Event e)
			{
			    Widget source = e.widget;
			    if (source == _addButton)
			    {
			        int[] toAdd = _availableList.getSelectionIndices();
			        addToDisplay(toAdd);	    
			        _changed = true;
			    }
			    else if (source == _removeButton)
			    {
			        int[] toAdd = _displayedList.getSelectionIndices();
			        removeFromDisplay(toAdd);	   
			        _changed = true;
			    }
			    else if (source == _upButton)
			    {
			        int index = _displayedList.getSelectionIndex();
			        moveUp(index);
			        _displayedList.select(index - 1);
			        _changed = true;
			    }
			    else if (source == _downButton)
			    {
			        int index = _displayedList.getSelectionIndex();
			        moveDown(index);
			        _displayedList.select(index + 1);
			        _changed = true;
			    }
			    
			    // update button enable states
			    updateEnableStates();
			}
			
			public IPropertyDescriptor[] getDisplayedColumns()
			{
			    IPropertyDescriptor[] displayedColumns = new IPropertyDescriptor[_currentDisplayedDescriptors.size()];
			    for (int i = 0; i< _currentDisplayedDescriptors.size();i++)
			    {
			        displayedColumns[i]= (IPropertyDescriptor)_currentDisplayedDescriptors.get(i);
			    }
			    return displayedColumns;
			}
			
			private void updateEnableStates()
			{
			    boolean enableAdd = false;
			    boolean enableRemove = false;
			    boolean enableUp = false;
			    boolean enableDown = false;
			    
			    int[] availableSelected = _availableList.getSelectionIndices();
			    for (int i = 0; i < availableSelected.length; i++)
			    {
			        int index = availableSelected[i];
			        IPropertyDescriptor descriptor = (IPropertyDescriptor)_availableDescriptors.get(index);
			        if (!_currentDisplayedDescriptors.contains(descriptor))
			        {
			            enableAdd = true;
			        }
			    }
			    
			    if (_displayedList.getSelectionCount()>0)
			    {
			        enableRemove = true;
			        
			        int index = _displayedList.getSelectionIndex();
			        if (index > 0)
			        {
			            enableUp = true;
			        }
			        if (index < _displayedList.getItemCount()-1)
			        {
			            enableDown = true;
			        }
			    }
			    
			    _addButton.setEnabled(enableAdd);
			    _removeButton.setEnabled(enableRemove);
			    _upButton.setEnabled(enableUp);
			    _downButton.setEnabled(enableDown);
			    enableOkButton(_changed);
			    
			}
			
			private void moveUp(int index)
			{
			    Object obj = _currentDisplayedDescriptors.remove(index);
		        _currentDisplayedDescriptors.add(index - 1, obj);
		        refreshDisplayedList();
			}
			
			private void moveDown(int index)
			{
			    Object obj = _currentDisplayedDescriptors.remove(index);
		        _currentDisplayedDescriptors.add(index + 1, obj);
		        
		        refreshDisplayedList();
			}
			
			private void addToDisplay(int[] toAdd)
			{
			    ArrayList added = new ArrayList();
			    for (int i = 0; i < toAdd.length; i++)
			    {
			        int index = toAdd[i];
			        
			        IPropertyDescriptor descriptor = (IPropertyDescriptor)_availableDescriptors.get(index);
			        
			        if (!_currentDisplayedDescriptors.contains(descriptor))
			        {
			            _currentDisplayedDescriptors.add(descriptor);
			            added.add(descriptor);
			        }			            
			    }
			    
			    for (int i = 0; i < added.size(); i++)
			    {			       
			      _availableDescriptors.remove(added.get(i));			       			            
			    }
			    
			    
			    refreshAvailableList();
			    refreshDisplayedList();
			  
			}
			
			private void removeFromDisplay(int[] toRemove)
			{
			    for (int i = 0; i < toRemove.length; i++)
			    {
			        int index = toRemove[i];
			        IPropertyDescriptor descriptor = (IPropertyDescriptor)_currentDisplayedDescriptors.get(index);
			        _currentDisplayedDescriptors.remove(index);			    
			        _availableDescriptors.add(descriptor);
			    }
			    refreshDisplayedList();
			    refreshAvailableList();
			}

			protected void buttonPressed(int buttonId)
			{
				setReturnCode(buttonId);

				close();
			}

			protected Control getInitialFocusControl()
			{
				return _availableList;
			}

			public Control createInner(Composite parent)
			{
				Composite main = SystemWidgetHelpers.createComposite(parent, 1);
				
				SystemWidgetHelpers.createLabel(main, SystemResources.RESID_TABLE_SELECT_COLUMNS_DESCRIPTION_LABEL);
				
				Composite c = SystemWidgetHelpers.createComposite(main, 4);
				c.setLayoutData(new GridData(GridData.FILL_BOTH));
				_availableList = SystemWidgetHelpers.createListBox(c, SystemResources.RESID_TABLE_SELECT_COLUMNS_AVAILABLE_LABEL, this, true);
				
				Composite addRemoveComposite = SystemWidgetHelpers.createComposite(c, 1);
				addRemoveComposite.setLayoutData(new GridData(GridData.VERTICAL_ALIGN_CENTER));
				_addButton = SystemWidgetHelpers.createPushButton(addRemoveComposite,
				        SystemResources.RESID_TABLE_SELECT_COLUMNS_ADD_LABEL, 
				        this);
				_addButton.setToolTipText(SystemResources.RESID_TABLE_SELECT_COLUMNS_ADD_TOOLTIP);
				
				_removeButton = SystemWidgetHelpers.createPushButton(addRemoveComposite, 
				        SystemResources.RESID_TABLE_SELECT_COLUMNS_REMOVE_LABEL,
				        this);
				_removeButton.setToolTipText(SystemResources.RESID_TABLE_SELECT_COLUMNS_REMOVE_TOOLTIP);
				
				_displayedList = SystemWidgetHelpers.createListBox(c, SystemResources.RESID_TABLE_SELECT_COLUMNS_DISPLAYED_LABEL, this, false);
				
				Composite upDownComposite = SystemWidgetHelpers.createComposite(c, 1);
				upDownComposite.setLayoutData(new GridData(GridData.VERTICAL_ALIGN_CENTER));
				_upButton = SystemWidgetHelpers.createPushButton(upDownComposite, 
				        SystemResources.RESID_TABLE_SELECT_COLUMNS_UP_LABEL,
				        this);
				_upButton.setToolTipText(SystemResources.RESID_TABLE_SELECT_COLUMNS_UP_TOOLTIP);
				
				_downButton = SystemWidgetHelpers.createPushButton(upDownComposite, 
				        SystemResources.RESID_TABLE_SELECT_COLUMNS_DOWN_LABEL, 
				        this);
				_downButton.setToolTipText(SystemResources.RESID_TABLE_SELECT_COLUMNS_DOWN_TOOLTIP);
				
				initLists();

				setHelp();
				return c;
			}

			private void initLists()
			{
			   refreshAvailableList();
			   refreshDisplayedList();
			   updateEnableStates();
			}
			
			private void refreshAvailableList()
			{
			    _availableList.removeAll();
			    // initialize available list
			    for (int i = 0; i < _availableDescriptors.size(); i++)
			    {
			        IPropertyDescriptor descriptor = (IPropertyDescriptor)_availableDescriptors.get(i);
			        _availableList.add(descriptor.getDisplayName());
			    }
			}
			
			private void refreshDisplayedList()
			{
			    _displayedList.removeAll();
			    // initialize display list
			    for (int i = 0; i < _currentDisplayedDescriptors.size(); i++)
			    {
		
			        Object obj = _currentDisplayedDescriptors.get(i);
			        if (obj != null && obj instanceof IPropertyDescriptor)
			        {
			            _displayedList.add(((IPropertyDescriptor)obj).getDisplayName());
			        }
			    }  
			}
			
			private void setHelp()
			{
				setHelp(RSEUIPlugin.HELPPREFIX + "gntc0000"); //$NON-NLS-1$
			}
		}
	    
		public SelectColumnsAction()
		{
			super(SystemMonitorViewPart.this, SystemResources.ACTION_SELECTCOLUMNS_LABEL, null);
			setToolTipText(SystemResources.ACTION_SELECTCOLUMNS_TOOLTIP);
			setImageDescriptor(RSEUIPlugin.getDefault().getImageDescriptor(ISystemIconConstants.ICON_SYSTEM_FILTER_ID));
		}

		public void checkEnabledState()
		{
			
			if (getViewer() != null && getViewer().getInput() != null)
			{
				setEnabled(true);
			}
			else
			{
				setEnabled(false);
			}
		}
		public void run()
		{
			SystemTableView viewer = getViewer();
			Table table = viewer.getTable();
			int[] originalOrder = table.getColumnOrder();
		    ISystemTableViewColumnManager mgr = viewer.getColumnManager();		    
		    ISystemViewElementAdapter adapter = viewer.getAdapterForContents();
		    SelectColumnsDialog dlg = new SelectColumnsDialog(getShell(), adapter, mgr, originalOrder);
		    if (dlg.open() == Window.OK)
		    {
		    	IPropertyDescriptor[] newDescriptors = dlg.getDisplayedColumns();
		    	
		    	// reset column order
		    	int n = newDescriptors.length + 1;
		    	int[] newOrder = new int[n];		    	
		    	for (int i = 0; i < n; i++){	
		    		newOrder[i] = i;
		    	}
		    			
		        mgr.setCustomDescriptors(adapter, dlg.getDisplayedColumns());
		        viewer.computeLayout(true);
		        table.setColumnOrder(newOrder);
		        viewer.refresh();
		    }
		}
	}

	MonitorViewWorkbook _folder = null;
	private CellEditorActionHandler _editorActionHandler = null;

	//  for ISystemMessageLine
	private String _message, _errorMessage;
	private SystemMessage sysErrorMessage;
	private IStatusLineManager _statusLine = null;

	private SelectColumnsAction _selectColumnsAction = null;
	private RefreshAction _refreshAction = null;

	private ClearAction _clearAllAction = null;
	private ClearSelectedAction _clearSelectedAction = null;

	private SubSetAction _subsetAction = null;
	private PositionToAction _positionToAction = null;
	
	private ISelectionProvider viewerProvider = null;
	private ArrayList selectionListeners = new ArrayList();
	
	private ISelectionChangedListener selectionListener = null;
	
	// constants			
	public static final String ID = "org.eclipse.rse.ui.view.monitorView"; //$NON-NLS-1$
	// matches id in plugin.xml, view tag	

	private IMemento _memento = null;
	
	public void setFocus()
	{
		if (_folder.getInput() == null){
			if (_memento != null){
				restoreState(_memento);
			}
		}
		_folder.showCurrentPage();
	}

	public Shell getShell()
	{
		return _folder.getShell();
	}
	
	public SystemTableView getViewer()
	{
		return _folder.getViewer();
	}
	
	public Viewer getRSEViewer()
	{
		return _folder.getViewer();
	}

	public CellEditorActionHandler getEditorActionHandler()
	{
	    if (_editorActionHandler == null)
	    {
	        _editorActionHandler = new CellEditorActionHandler(getViewSite().getActionBars());
	    }
	    return _editorActionHandler;
	}
	
	public void createPartControl(Composite parent)
	{
		_folder = new MonitorViewWorkbook(parent, this);
		_folder.getFolder().addSelectionListener(this);

		ISelectionService selectionService = getSite().getWorkbenchWindow().getSelectionService();
		selectionService.addSelectionListener(this);
		

		SystemWidgetHelpers.setHelp(_folder, RSEUIPlugin.HELPPREFIX + "mntr0000"); //$NON-NLS-1$

		ISystemRegistry registry = RSECorePlugin.getTheSystemRegistry();
		registry.addSystemResourceChangeListener(this);
		registry.addSystemRemoteChangeListener(this);

		getSite().setSelectionProvider(this);
		selectionListener = new ISelectionChangedListener() {
			public void selectionChanged (SelectionChangedEvent event)
			{
				for (int i = 0; i < selectionListeners.size(); i++)
				{
					if (selectionListeners.get(i) instanceof ISelectionChangedListener)
					{
						((ISelectionChangedListener) selectionListeners.get(i)).selectionChanged(event);
					}
				}
			}
		};
		
		
		fillLocalToolBar();
		
	}

	public void selectionChanged(IWorkbenchPart part, ISelection sel)
	{
	}

	public void dispose()
	{
		ISelectionService selectionService = getSite().getWorkbenchWindow().getSelectionService();
		selectionService.removeSelectionListener(this);
		_folder.dispose();

		ISystemRegistry registry = RSECorePlugin.getTheSystemRegistry();
		registry.removeSystemResourceChangeListener(this);
		registry.removeSystemRemoteChangeListener(this);
		

		super.dispose();
	}

	public void updateActionStates()
	{

		if (_folder != null && _folder.getInput() != null)
		{
		}
		if (_clearAllAction != null)
		{
			_clearAllAction.checkEnabledState();
			_clearSelectedAction.checkEnabledState();
			_selectColumnsAction.checkEnabledState();
			_refreshAction.checkEnabledState();
			_positionToAction.checkEnabledState();
			_subsetAction.checkEnabledState();
		}
	}

	public void fillLocalToolBar()
	{
		if (_folder != null )
		{

		
			//updateActionStates();
	
			IActionBars actionBars = getViewSite().getActionBars();
				
			_refreshAction= new RefreshAction();
			
			_clearSelectedAction = new ClearSelectedAction(this);
			_clearAllAction = new ClearAction(this);
			
			_selectColumnsAction = new SelectColumnsAction();
			
			_subsetAction = new SubSetAction();
			_positionToAction = new PositionToAction();
			
			IToolBarManager toolBarManager = actionBars.getToolBarManager();
			addToolBarItems(toolBarManager);
			addToolBarMenuItems(actionBars.getMenuManager());
		}
		updateActionStates();
	}

	private void addToolBarItems(IToolBarManager toolBarManager)
	{
		toolBarManager.removeAll();

		toolBarManager.add(_refreshAction);
		
		toolBarManager.add(new Separator());
		toolBarManager.add(_clearSelectedAction);
		toolBarManager.add(_clearAllAction);
		
		toolBarManager.add(new Separator());
		toolBarManager.add(_selectColumnsAction);		
	
		toolBarManager.update(true);		
	}


	
	public void selectionChanged(SelectionChangedEvent e)
	{
	}

	public void addSelectionChangedListener(ISelectionChangedListener listener)
	{
		if (selectionListeners != null)
			selectionListeners.add(listener);
	}
	
	public void removeSelectionChangedListener(ISelectionChangedListener listener)
	{
		if (selectionListeners != null)
			selectionListeners.remove(listener);
	}
	
	public ISelection getSelection()
	{
		if (viewerProvider == null)
			return null;
		else
			return viewerProvider.getSelection();
	}
	
	/**
	 * Sets the wrapped selection provider.
	 * This method should only be called when the viewer changes.
	 * @param newProvider The new wrapped selection provider.
	 */
	public void setActiveViewerSelectionProvider(ISelectionProvider newProvider)
	{
		if (viewerProvider != null)
			viewerProvider.removeSelectionChangedListener(selectionListener);
	
		viewerProvider = newProvider;
			
		if (newProvider != null)
		{
			newProvider.addSelectionChangedListener(selectionListener);
			
			// Create a new event and tell all listeners about it, so that the properties
			// view is updated to show the new viewers selected object
			SelectionChangedEvent event = new SelectionChangedEvent(newProvider, newProvider.getSelection());
			for (int i = 0; i < selectionListeners.size(); i++)
			{
				if (selectionListeners.get(i) instanceof ISelectionChangedListener)
				{
					((ISelectionChangedListener) selectionListeners.get(i)).selectionChanged(event);
				}
			}
		}
	}
	
	public void setSelection(ISelection selection)
	{
		if (viewerProvider != null)
			viewerProvider.setSelection(selection);
	}

	public void addItemToMonitor(IAdaptable root)
	{
		if (root != null)
		{
			_folder.addItemToMonitor(root, true);
			if (true)
			    updateActionStates();
		}
	}
	
	public void removeItemToMonitor(IAdaptable root)
	{
		if (root != null)
		{
			_folder.remove(root);
			if (true)
			    updateActionStates();
		}
	}
	
	public void removeAllItemsToMonitor()
	{
		while (_folder.getInput() != null)
		{
			removeItemToMonitor((IAdaptable)_folder.getInput());
		}
	}

	public void setInput(IAdaptable object)
	{
		_folder.setInput(object);
	}


	/**
	   * Used to asynchronously update the view whenever properties change.
	   */
	public void systemResourceChanged(ISystemResourceChangeEvent event)
	{

		Object child = event.getSource();
		SystemTableView viewer = getViewer();
		if (viewer != null)
		{
			Object input = viewer.getInput();
			switch (event.getType())
			{
			case ISystemResourceChangeEvents.EVENT_PROPERTY_CHANGE:
			{						
				_folder.removeDisconnected();
				updateActionStates();
			}
			break;

			// Fix bug#282239: Monitor view does not update icon according to connection status 
			case ISystemResourceChangeEvents.EVENT_ICON_CHANGE:
			{						
				_folder.updateTitleIcon((IAdaptable)child);
			}
			break;
			case ISystemResourceChangeEvents.EVENT_RENAME:
			{
				if (child == input)
				{
					_folder.getCurrentTabItem().updateTitle((IAdaptable)child);
				}
			}
			break;
			case ISystemResourceChangeEvents.EVENT_DELETE:   	    	  
			case ISystemResourceChangeEvents.EVENT_DELETE_MANY:
		  	{   
		          if (child == input)
		          {
		              removeItemToMonitor((IAdaptable)child);
		    	  }
		  	}
		     break;  
		     default:
		          break;
			}
		}
	}
	
	/**
	 * This is the method in your class that will be called when a remote resource
	 *  changes. You will be called after the resource is changed.
	 * @see org.eclipse.rse.core.events.ISystemRemoteChangeEvent
	 */
	public void systemRemoteResourceChanged(ISystemRemoteChangeEvent event)
	{
		int eventType = event.getEventType();
		Object remoteResource = event.getResource();
	
		java.util.List remoteResourceNames = null;
		if (remoteResource instanceof java.util.List)
		{
			remoteResourceNames = (java.util.List) remoteResource;
			remoteResource = remoteResourceNames.get(0);
		}

		Object child = event.getResource();
		
		SystemTableView viewer = getViewer();
		if (viewer != null)
		{
			Object input = viewer.getInput();
			if (input == child || child instanceof Vector)
			{
				switch (eventType)
				{
					// --------------------------
					// REMOTE RESOURCE CHANGED...
					// --------------------------
					case ISystemRemoteChangeEvents.SYSTEM_REMOTE_RESOURCE_CHANGED :
						break;
		
						// --------------------------
						// REMOTE RESOURCE CREATED...
						// --------------------------
					case ISystemRemoteChangeEvents.SYSTEM_REMOTE_RESOURCE_CREATED :
						break;
		
						// --------------------------
						// REMOTE RESOURCE DELETED...
						// --------------------------
					case ISystemRemoteChangeEvents.SYSTEM_REMOTE_RESOURCE_DELETED :
						{				    
					    	if (child instanceof Vector)
					    	{
					    	    /*Vector vec = (Vector)child;
					    	    for (int v = 0; v < vec.size(); v++)
					    	    {
					    	        Object c = vec.get(v);
					    	     
					    	    }*/
					    	}
					    	else
					    	{
					    	   
					    	    return;
					    	}
						}
						break;
		
						// --------------------------
						// REMOTE RESOURCE RENAMED...
						// --------------------------
					case ISystemRemoteChangeEvents.SYSTEM_REMOTE_RESOURCE_RENAMED :
						{						
					    	addItemToMonitor((IAdaptable)child);
						}
		
						break;
				}
			}
		}
	}

	public void widgetDefaultSelected(SelectionEvent e)
	{
		widgetSelected(e);
	}

	public void widgetSelected(SelectionEvent e)
	{
		Widget source = e.widget;
		Widget item = e.item;
		Object data = item.getData();
		MonitorViewPage page = null;	
		
		if (data instanceof MonitorViewPage)
			page = (MonitorViewPage) data;
		
		// Set the wrapped viewer to be the viewer of the new selected tab
		if (page != null)
		{
			SystemTableView viewer = page.getViewer();
			setActiveViewerSelectionProvider(viewer);
		}
		
		if (source == _folder.getFolder())
		{
			updateActionStates();
		}
	}
	
	
//	 -------------------------------
	// ISystemMessageLine interface...
	// -------------------------------
	/**
	 * Clears the currently displayed error message and redisplayes
	 * the message which was active before the error message was set.
	 */
	public void clearErrorMessage()
	{
		_errorMessage = null;
		sysErrorMessage = null;
		if (_statusLine != null)
			_statusLine.setErrorMessage(_errorMessage);
	}
	/**
	 * Clears the currently displayed message.
	 */
	public void clearMessage()
	{
		_message = null;
		if (_statusLine != null)
			_statusLine.setMessage(_message);
	}
	/**
	 * Get the currently displayed error text.
	 * @return The error message. If no error message is displayed <code>null</code> is returned.
	 */
	public String getErrorMessage()
	{
		return _errorMessage;
	}
	/**
	 * Get the currently displayed message.
	 * @return The message. If no message is displayed <code>null<code> is returned.
	 */
	public String getMessage()
	{
		return _message;
	}
	/**
	 * Display the given error message. A currently displayed message
	 * is saved and will be redisplayed when the error message is cleared.
	 */
	public void setErrorMessage(String message)
	{
		this._errorMessage = message;
		if (_statusLine != null)
			_statusLine.setErrorMessage(message);
	}
	/**
	 * Get the currently displayed error text.
	 * @return The error message. If no error message is displayed <code>null</code> is returned.
	 */
	public SystemMessage getSystemErrorMessage()
	{
		return sysErrorMessage;
	}

	/**
	 * Display the given error message. A currently displayed message
	 * is saved and will be redisplayed when the error message is cleared.
	 */
	public void setErrorMessage(SystemMessage message)
	{
		sysErrorMessage = message;
		setErrorMessage(message.getLevelOneText());
	}
	/**
	 * Display the given error message. A currently displayed message
	 * is saved and will be redisplayed when the error message is cleared.
	 */
	public void setErrorMessage(Throwable exc)
	{
		setErrorMessage(exc.getMessage());
	}

	/**
	 * Set the message text. If the message line currently displays an error,
	 * the message is stored and will be shown after a call to clearErrorMessage
	 */
	public void setMessage(String message)
	{
		this._message = message;
		if (_statusLine != null)
			_statusLine.setMessage(message);
	}
	/** 
	 *If the message line currently displays an error,
	 * the message is stored and will be shown after a call to clearErrorMessage
	 */
	public void setMessage(SystemMessage message)
	{
		setMessage(message.getLevelOneText());
	}

	private void addToolBarMenuItems(IMenuManager menuManager)
	{
		menuManager.removeAll();
		menuManager.add(_selectColumnsAction);
		menuManager.add(new Separator("Filter")); //$NON-NLS-1$
		menuManager.add(_positionToAction);
		menuManager.add(_subsetAction);		
	}

	private void restoreState(IMemento memento)
	{
		RestoreStateRunnable rsr = new RestoreStateRunnable(memento);
		rsr.setRule(RSECorePlugin.getTheSystemRegistry());
		rsr.schedule();		
	}
	
	/**
	* Initializes this view with the given view site.  A memento is passed to
	* the view which contains a snapshot of the views state from a previous
	* session.  Where possible, the view should try to recreate that state
	* within the part controls.
	* <p>
	* The parent's default implementation will ignore the memento and initialize
	* the view in a fresh state.  Subclasses may override the implementation to
	* perform any state restoration as needed.
	*/
	public void init(IViewSite site, IMemento memento) throws PartInitException
	{
		super.init(site, memento);

		if (memento != null && SystemPreferencesManager.getRememberState()){
			_memento = memento;
		}
	}

	protected void saveTabState(IMemento memento, CTabItem item, int index){
		MonitorViewPage page = (MonitorViewPage) item.getData();
		Object input = page.getInput();

		if (input != null){
			if (input instanceof ISystemRegistry){
			}
			else if (input instanceof IHost){
				IHost connection = (IHost) input;
				String connectionID = connection.getAliasName();
				String profileID = connection.getSystemProfileName();
				memento.putString(TAG_MONITOR_TAB_CONNECTION_ID+index, connectionID);
				memento.putString(TAG_MONITOR_TAB_PROFILE_ID+index, profileID);
			}
			else{
				ISystemViewElementAdapter va = (ISystemViewElementAdapter) ((IAdaptable) input).getAdapter(ISystemViewElementAdapter.class);

				ISubSystem subsystem = va.getSubSystem(input);
				if (subsystem != null){
					ISystemRegistry registry = RSECorePlugin.getTheSystemRegistry();
					String subsystemID = registry.getAbsoluteNameForSubSystem(subsystem);
					String profileID = subsystem.getHost().getSystemProfileName();
					String connectionID = subsystem.getHost().getAliasName();
					String objectID = va.getAbsoluteName(input);

					memento.putString(TAG_MONITOR_TAB_PROFILE_ID+index, profileID);
					memento.putString(TAG_MONITOR_TAB_CONNECTION_ID+index, connectionID);
					memento.putString(TAG_MONITOR_TAB_SUBSYSTEM_ID+index, subsystemID);

					if (input instanceof ISystemFilterReference){
						memento.putString(TAG_MONITOR_TAB_FILTER_ID+index, objectID);
						memento.putString(TAG_MONITOR_TAB_OBJECT_ID+index, null);
					}
					else if (input instanceof ISubSystem){
						memento.putString(TAG_MONITOR_TAB_OBJECT_ID+index, null);
						memento.putString(TAG_MONITOR_TAB_FILTER_ID+index, null);
					}
					else {
						memento.putString(TAG_MONITOR_TAB_OBJECT_ID+index, objectID);
						memento.putString(TAG_MONITOR_TAB_FILTER_ID+index, null);
					}
				}
			}


			boolean isConnected = false;
			// don't reconnect
			ISystemViewElementAdapter adapter = (ISystemViewElementAdapter)((IAdaptable)input).getAdapter(ISystemViewElementAdapter.class);
			if (adapter != null){
				ISubSystem ss = adapter.getSubSystem(input);
				if (ss != null){
					isConnected = ss.isConnected();
				}
			}				

			SystemTableView viewer = page.getViewer();
			if (isConnected){ // calling this requires a connect so only do it if already connected
				viewer.inputChanged(input, input);
			}
			Map cachedColumnWidths = viewer.getCachedColumnWidths();
			StringBuffer columnWidths = new StringBuffer();
			Iterator keyIter = cachedColumnWidths.keySet().iterator();
			while (keyIter.hasNext()){
				String key = (String)keyIter.next();
				int[] widths = (int[])cachedColumnWidths.get(key);
				columnWidths.append(key);
				columnWidths.append('=');
				
				for (int w = 0; w < widths.length; w++){						
					columnWidths.append(widths[w]);
					if (w != widths.length - 1){
						columnWidths.append(',');
					}
				}
				
				// always append this, even with last item
				columnWidths.append(';');
			}
			
			memento.putString(TAG_MONITOR_TAB_COLUMN_WIDTHS_ID+index, columnWidths.toString());
			memento.putBoolean(TAG_MONITOR_TAB_POLLING_ON_ID+index, page.isPollingEnabled());
			memento.putInteger(TAG_MONITOR_TAB_POLLING_INTERVAL_ID+index, page.getPollingInterval());
		}
	}
	
	/**
	 * Method declared on IViewPart.
	 */
	public void saveState(IMemento memento)
	{
		super.saveState(memento);

		if (!SystemPreferencesManager.getRememberState())
			return;
				
		if (_folder != null){
			CTabFolder flder = _folder.getFolder();
			int itemCount = flder.getItemCount();
			memento.putInteger(TAG_MONITOR_TAB_COUNT_ID, itemCount);
			
			for (int i = 0; i < itemCount; i++){
				CTabItem item = flder.getItem(i);
				saveTabState(memento, item, i);			
			}
		}			
	}

	
}