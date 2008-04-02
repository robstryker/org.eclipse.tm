/********************************************************************************
 * Copyright (c) 2008 IBM Corporation and others. All rights reserved.
 * This program and the accompanying materials are made available under the terms
 * of the Eclipse Public License v1.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * David Dykstal (IBM) - [197167] adding notification and waiting for RSE model
 ********************************************************************************/
package org.eclipse.rse.internal.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.IJobChangeListener;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.osgi.util.NLS;
import org.eclipse.rse.core.IRSEInitListener;
import org.eclipse.rse.core.IRSEModelInitializer;
import org.eclipse.rse.core.RSECorePlugin;
import org.eclipse.rse.logging.Logger;

/**
 * This is a job named "Initialize RSE". It is instantiated and run during 
 * RSE startup. It must not be run at any other time. The job restores the 
 * persistent form of the RSE model. Use the extension point 
 * org.eclipse.rse.core.modelInitializers to supplement the model once it is 
 * restored.
 */
public final class RSEInitJob extends Job {
	
	/**
	 * The name of this job. This is API. Clients may use this name to find this job by name.
	 */
	public final static String NAME = "Initialize RSE"; //$NON-NLS-1$
	
	private static RSEInitJob instance = new RSEInitJob();

	private class Phase {
		private volatile boolean isCanceled = false;
		private volatile boolean isComplete = false;
		private int phaseNumber = 0;
		public Phase(int phaseNumber) {
			this.phaseNumber = phaseNumber;
		}
		public synchronized void waitForCompletion() throws InterruptedException {
			while (!isComplete && !isCanceled) {
				wait();
			}
			if (isCanceled) {
				throw new InterruptedException();
			}
		}
		public void done() {
			synchronized (this) {
				isComplete = true;
				notifyAll();
			}
			notifyListeners(phaseNumber);
		}
		public synchronized void cancel() {
			isCanceled = true;
			notifyAll();
		}
		public boolean isComplete() {
			return isComplete;
		}
	}
	
	private class MyJobChangeListener implements IJobChangeListener {
		public void aboutToRun(IJobChangeEvent event) {
		}
		public void awake(IJobChangeEvent event) {
		}
		public void done(IJobChangeEvent event) {
			IStatus status = event.getJob().getResult();
			if (status.getSeverity() == IStatus.CANCEL) {
				modelPhase.cancel();
				initializerPhase.cancel();
				finalPhase.cancel();
			} else {
				finalPhase.done();
			}
		}
		public void running(IJobChangeEvent event) {
		}
		public void scheduled(IJobChangeEvent event) {
		}
		public void sleeping(IJobChangeEvent event) {
		}
	}
	
	private Phase finalPhase = new Phase(RSECorePlugin.INIT_ALL);
	private Phase modelPhase = new Phase(RSECorePlugin.INIT_MODEL);
	private Phase initializerPhase = new Phase(RSECorePlugin.INIT_INITIALIZER);
	private List listeners = new ArrayList(10);
	private Logger logger = RSECorePlugin.getDefault().getLogger();
	private MyJobChangeListener myJobChangeListener = new MyJobChangeListener();
	
	
	/**
	 * Returns the singleton instance of this job.
	 * @return the InitRSEJob instance for this workbench.
	 */
	public static RSEInitJob getInstance() {
		return instance;
	}
	
	private RSEInitJob() {
		super(NAME);
		addJobChangeListener(myJobChangeListener);
	}
	
	/**
	 * Adds a new listener to the set of listeners to be notified when initialization phases complete.
	 * If the listener is added after the phase has completed it will not be invoked.
	 * If the listener is already in the set it will not be added again.
	 * Listeners may be notified in any order.
	 * @param listener the listener to be added
	 */
	public void addInitListener(IRSEInitListener listener) {
		synchronized (listeners) {
			listeners.add(listener);
		}
	}
	
	/**
	 * Removes a listener to the set of listeners to be notified when phases complete.
	 * If the listener is not in the set this does nothing.
	 * @param listener the listener to be removed
	 */
	public void removeInitListener(IRSEInitListener listener) {
		synchronized (listeners) {
			listeners.remove(listener);
		}
	}
	
	/**
	 * Notify all registered listeners of a phase completion
	 * @param phase the phase just completed.
	 */
	private void notifyListeners(int phase) {
		IRSEInitListener[] myListeners = new IRSEInitListener[listeners.size()];
		synchronized (listeners) {
			listeners.toArray(myListeners);
		}
		for (int i = 0; i < myListeners.length; i++) {
			IRSEInitListener listener = myListeners[i];
			try {
				listener.phaseComplete(phase);
			} catch (RuntimeException e) {
				logger.logError(RSECoreMessages.InitRSEJob_listener_ended_in_error, e);
			}
		}
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.core.runtime.jobs.Job#run(org.eclipse.core.runtime.IProgressMonitor)
	 */
	public IStatus run(IProgressMonitor monitor) {
		IStatus result = Status.OK_STATUS;
		// get and initialize the profile manager
		RSECorePlugin.getTheSystemProfileManager();
		modelPhase.done();
		// instantiate and run initializers
		IConfigurationElement[] elements = Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.rse.core.modelInitializers"); //$NON-NLS-1$
		monitor.beginTask(RSECoreMessages.InitRSEJob_initializing_rse, elements.length); 
		for (int i = 0; i < elements.length && !monitor.isCanceled(); i++) {
			IConfigurationElement element = elements[i];
			IProgressMonitor submonitor = new SubProgressMonitor(monitor, 1);
			String initializerType = element.getAttribute("type"); //$NON-NLS-1$
			boolean isMarked = isMarked(element);
			boolean isSessionType = initializerType == null || initializerType.equals("session"); //$NON-NLS-1$
			boolean isWorkspaceType = initializerType != null && initializerType.equals("workspace"); //$NON-NLS-1$
			if (isSessionType || (isWorkspaceType && !isMarked)) {
				IStatus status = runInitializer(element, submonitor);
				if (status.getSeverity() < IStatus.ERROR && isWorkspaceType) {
					mark(element);
				}
				if (result.getSeverity() < status.getSeverity()) {
					result = status;
				}
			}
			submonitor.done();
		}
		initializerPhase.done();
		// finish up - propogate cancel if necessary
		if (monitor.isCanceled()) {
			result = Status.CANCEL_STATUS;
		} else {
			monitor.done();
		}
		return result;
	}
	
	/**
	 * Returns a handle to a mark file based on the initializer class name.
	 * @param element the element that defines the initializer
	 * @return a handle to a file in the state location of this plugin
	 */
	private File getMarkFile(IConfigurationElement element) {
		String initializerName = element.getAttribute("class"); //$NON-NLS-1$
		String markName = initializerName + ".mark"; //$NON-NLS-1$
		IPath stateLocation = RSECorePlugin.getDefault().getStateLocation();
		IPath marksLocation = stateLocation.append("initializerMarks"); //$NON-NLS-1$
		IPath markPath = marksLocation.append(markName);
		File markFile = new File(markPath.toOSString());
		return markFile;
	}
	
	/**
	 * @param element the element to test for marking
	 * @return true if the element is marked
	 */
	private boolean isMarked(IConfigurationElement element) {
		File markFile = getMarkFile(element);
		return markFile.exists();
	}
	
	/**
	 * @param element the element to mark
	 * @return a status indicating if the marking was successful
	 */
	private IStatus mark(IConfigurationElement element) {
		IStatus status = Status.OK_STATUS;
		File markFile = getMarkFile(element);
		File markFolder = markFile.getParentFile();
		try {
			markFolder.mkdirs();
			markFile.createNewFile();
		} catch (IOException e) {
			String message = NLS.bind(RSECoreMessages.InitRSEJob_error_creating_mark, markFile.getPath());
			status = new Status(IStatus.ERROR, RSECorePlugin.PLUGIN_ID, message, e);
			logger.logError(message, e);
		}
		return status;
	}

	/**
	 * Instantiate and run an initializer
	 * @param element the configuration element from which to instantiate the initializer
	 * @param submonitor the monitor with which to run the initializer
	 * @return the status of this initializer
	 */
	private IStatus runInitializer(IConfigurationElement element, IProgressMonitor submonitor) {
		IStatus status = Status.OK_STATUS;
		String initializerName = element.getAttribute("class"); //$NON-NLS-1$
		try {
			IRSEModelInitializer initializer = (IRSEModelInitializer) element.createExecutableExtension("class"); //$NON-NLS-1$
			try {
				status = initializer.run(submonitor);
			} catch (RuntimeException e) {
				String message = NLS.bind(RSECoreMessages.InitRSEJob_initializer_ended_in_error, initializerName); 
				logger.logError(message, e);
				status = new Status(IStatus.ERROR, RSECorePlugin.PLUGIN_ID, message, e);
			}
		} catch (CoreException e) {
			String message = NLS.bind(RSECoreMessages.InitRSEJob_initializer_failed_to_load, initializerName); 
			logger.logError(message, e);
			status = new Status(IStatus.ERROR, RSECorePlugin.PLUGIN_ID, message, e);
		}
		return status;
	}
	
	/**
	 * Waits until a job is completed.
	 * @return the status of the job upon its completion.
	 * @throws InterruptedException if the job is interrupted while waiting.
	 */
	public IStatus waitForCompletion() throws InterruptedException {
		waitForCompletion(RSECorePlugin.INIT_ALL);
		return getResult();
	}

	/**
	 * Wait for the completion of a particular phase
	 * @param phase the phase to wait for
	 * @see RSECorePlugin#INIT_ALL
	 * @see RSECorePlugin#INIT_MODEL
	 * @see RSECorePlugin#INIT_INITIALIZER
	 */
	public void waitForCompletion(int phase) throws InterruptedException {
		switch (phase) {
		case RSECorePlugin.INIT_MODEL:
			modelPhase.waitForCompletion();
			break;
		case RSECorePlugin.INIT_INITIALIZER:
			initializerPhase.waitForCompletion();
			break;
		case RSECorePlugin.INIT_ALL:
			finalPhase.waitForCompletion();
			break;
		default:
			throw new IllegalArgumentException("undefined phase"); //$NON-NLS-1$
		}
	}

	/**
	 * @param phase the phase for which completion is requested. 
	 * Phases are defined in {@link RSECorePlugin}.
	 * @return true if this phase has completed.
	 * @throws IllegalArgumentException if the phase is undefined.
	 * @see RSECorePlugin#INIT_ALL
	 * @see RSECorePlugin#INIT_MODEL
	 * @see RSECorePlugin#INIT_INITIALIZER
	 */
	public boolean isComplete(int phase) {
		boolean result = false;
		switch (phase) {
		case RSECorePlugin.INIT_MODEL:
			result = modelPhase.isComplete();
			break;
		case RSECorePlugin.INIT_INITIALIZER:
			result = initializerPhase.isComplete();
			break;
		case RSECorePlugin.INIT_ALL:
			result = finalPhase.isComplete();
			break;
		default:
			throw new IllegalArgumentException("undefined phase"); //$NON-NLS-1$
		}
		return result;
	}
	
}