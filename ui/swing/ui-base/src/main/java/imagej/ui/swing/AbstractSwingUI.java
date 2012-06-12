/*
 * #%L
 * ImageJ software for multidimensional image processing and analysis.
 * %%
 * Copyright (C) 2009 - 2012 Board of Regents of the University of
 * Wisconsin-Madison, Broad Institute of MIT and Harvard, and Max Planck
 * Institute of Molecular Cell Biology and Genetics.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * 
 * The views and conclusions contained in the software and documentation are
 * those of the authors and should not be interpreted as representing official
 * policies, either expressed or implied, of any organization.
 * #L%
 */

package imagej.ui.swing;

import imagej.event.EventHandler;
import imagej.event.EventSubscriber;
import imagej.ext.display.Display;
import imagej.ext.display.DisplayService;
import imagej.ext.display.event.DisplayActivatedEvent;
import imagej.ext.display.event.DisplayCreatedEvent;
import imagej.ext.display.event.DisplayDeletedEvent;
import imagej.ext.display.event.DisplayUpdatedEvent;
import imagej.ext.display.ui.DisplayViewer;
import imagej.ext.menu.MenuService;
import imagej.ext.menu.ShadowMenu;
import imagej.ext.ui.swing.SwingJMenuBarCreator;
import imagej.ext.ui.swing.SwingJPopupMenuCreator;
import imagej.platform.event.AppMenusCreatedEvent;
import imagej.ui.AbstractUserInterface;
import imagej.ui.OutputWindow;
import imagej.ui.SystemClipboard;
import imagej.ui.common.awt.AWTClipboard;
import imagej.ui.common.awt.AWTDropListener;
import imagej.ui.common.awt.AWTInputEventDispatcher;
import imagej.util.Log;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.dnd.DropTarget;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JMenuBar;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * Abstract superclass for Swing-based user interfaces.
 * 
 * @author Curtis Rueden
 * @author Lee Kamentsky
 * @author Barry DeZonia
 * @author Grant Harris
 */
public abstract class AbstractSwingUI extends AbstractUserInterface {

	/**
	 * A list of extant display viewers. It's needed in order to find the viewer
	 * associated with a display.
	 */
	// NB: I'm a little queasy about including this.
	// TODO: push up into DefaultUIService?
	protected final List<DisplayViewer<?>> displayViewers =
		new ArrayList<DisplayViewer<?>>();

	private SwingApplicationFrame appFrame;
	private SwingToolBar toolBar;
	private SwingStatusBar statusBar;
	private AWTClipboard systemClipboard;
	private boolean activationInvocationPending = false;

	@SuppressWarnings("unused")
	private List<EventSubscriber<?>> subscribers;

	// -- UserInterface methods --

	@Override
	public SwingApplicationFrame getApplicationFrame() {
		return appFrame;
	}

	@Override
	public SwingToolBar getToolBar() {
		return toolBar;
	}

	@Override
	public SwingStatusBar getStatusBar() {
		return statusBar;
	}

	@Override
	public SystemClipboard getSystemClipboard() {
		return systemClipboard;
	}

	/**
	 * Creates a {@link JMenuBar} from the master {@link ShadowMenu} structure.
	 */
	protected JMenuBar createMenus() {
		final MenuService menuService = getUIService().getMenuService();
		final JMenuBar menuBar =
			menuService.createMenus(new SwingJMenuBarCreator(), new JMenuBar());
		getEventService().publish(new AppMenusCreatedEvent(menuBar));
		return menuBar;
	}

	@Override
	public OutputWindow newOutputWindow(final String title) {
		return new SwingOutputWindow(title);
	}

	@Override
	public void showContextMenu(final String menuRoot, final Display<?> display,
		final int x, final int y)
	{
		final MenuService menuService = getUIService().getMenuService();
		final ShadowMenu shadowMenu = menuService.getMenu(menuRoot);

		final JPopupMenu popupMenu = new JPopupMenu();
		new SwingJPopupMenuCreator().createMenus(shadowMenu, popupMenu);

		final DisplayViewer<?> displayViewer = getDisplayViewer(display);
		if (displayViewer != null) {
			final Component invoker = (Component) displayViewer.getPanel();
			popupMenu.show(invoker, x, y);
		}
	}

	// -- Internal methods --

	@Override
	protected void createUI() {
		createMenus();

		appFrame = new SwingApplicationFrame("ImageJ");
		toolBar = new SwingToolBar(getUIService());
		statusBar = new SwingStatusBar(getUIService());
		systemClipboard = new AWTClipboard();

		setupAppFrame();

		super.createUI();

		// NB: The following setup happens for both SDI and MDI frames.

		appFrame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		appFrame.addWindowListener(new WindowAdapter() {

			@Override
			public void windowClosing(final WindowEvent evt) {
				getUIService().getAppService().quit();
			}

		});

		appFrame.getContentPane().add(toolBar, BorderLayout.NORTH);
		appFrame.getContentPane().add(statusBar, BorderLayout.SOUTH);

		// listen for input events on all components of the app frame
		final AWTInputEventDispatcher dispatcher =
			new AWTInputEventDispatcher(null, getEventService());
		appFrame.addEventDispatcher(dispatcher);

		appFrame.pack();
		appFrame.setVisible(true);

		// setup drag and drop targets
		final AWTDropListener dropListener = new AWTDropListener(getUIService());
		new DropTarget(toolBar, dropListener);
		new DropTarget(statusBar, dropListener);
		new DropTarget(appFrame, dropListener);

		subscribers = getEventService().subscribe(this);
	}

	/**
	 * Configures the application frame for subclass-specific settings (e.g., SDI
	 * or MDI).
	 */
	protected abstract void setupAppFrame();

	/**
	 * Called any time a display is created.
	 * 
	 * @param e
	 */
	protected abstract void onDisplayCreated(DisplayCreatedEvent e);

	/**
	 * Called any time a display is deleted. The display viewer is not removed
	 * from the list of viewers until after this returns.
	 * 
	 * @param e
	 */
	protected abstract void onDisplayDeleted(DisplayDeletedEvent e);

	/**
	 * Called any time a display is updated.
	 * 
	 * @param e
	 */
	protected void onDisplayUpdated(final DisplayUpdatedEvent e) {
		final DisplayViewer<?> displayViewer = getDisplayViewer(e.getDisplay());
		if (displayViewer != null) {
			displayViewer.onDisplayUpdatedEvent(e);
		}

	}

	/**
	 * Called any time a display is activated.
	 * <p>
	 * The goal here is to eventually synchronize the window activation state with
	 * the display activation state if the display activation state changed
	 * programatically. We queue a call on the UI thread to activate the display
	 * viewer of the currently active window.
	 * </p>
	 * 
	 * @param e
	 */
	protected synchronized void onDisplayActivated(final DisplayActivatedEvent e)
	{
		final DisplayService displayService =
			e.getContext().getService(DisplayService.class);
		final Display<?> activeDisplay = displayService.getActiveDisplay();
		if (activeDisplay != null) {
			final DisplayViewer<?> displayViewer = getDisplayViewer(activeDisplay);
			if (displayViewer != null) {
				displayViewer.onDisplayActivatedEvent(e);
			}
		}
		activationInvocationPending = false;
	}

	// -- Event handling methods --

	/**
	 * Handle a DisplayCreatedEvent.
	 * <p>
	 * Note that the handling of all display events is synchronized on the GUI
	 * singleton in order to serialize processing.
	 * </p>
	 * 
	 * @param e
	 */
	@EventHandler
	protected synchronized void onEvent(final DisplayCreatedEvent e) {
		onDisplayCreated(e);
	}

	@EventHandler
	protected synchronized void onEvent(final DisplayDeletedEvent e) {
		final DisplayViewer<?> displayViewer = getDisplayViewer(e.getObject());
		if (displayViewer != null) {
			onDisplayDeleted(e);
			displayViewer.onDisplayDeletedEvent(e);
			displayViewers.remove(displayViewer);
		}
	}

	@EventHandler
	protected synchronized void onEvent(final DisplayUpdatedEvent e) {
		final DisplayViewer<?> displayViewer = getDisplayViewer(e.getDisplay());
		if (displayViewer != null) {
			onDisplayUpdated(e);
		}
	}

	@EventHandler
	protected synchronized void onEvent(final DisplayActivatedEvent e) {
		if (activationInvocationPending) return;
		activationInvocationPending = true;
		SwingUtilities.invokeLater(new Runnable() {

			@Override
			public void run() {
				onDisplayActivated(e);
			}
		});
	}

	// -- Internal methods --

	protected DisplayViewer<?> getDisplayViewer(final Display<?> display) {
		for (final DisplayViewer<?> displayViewer : displayViewers) {
			if (displayViewer.getDisplay() == display) return displayViewer;
		}
		Log.warn("No DisplayViewer found for display: '" + display.getName() + "'");
		return null;
	}

}
