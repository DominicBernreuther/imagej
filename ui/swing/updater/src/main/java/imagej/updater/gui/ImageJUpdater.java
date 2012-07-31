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

package imagej.updater.gui;

import imagej.event.StatusService;
import imagej.ext.plugin.Menu;
import imagej.ext.plugin.Parameter;
import imagej.ext.plugin.Plugin;
import imagej.log.LogService;
import imagej.updater.core.Conflicts.Conflict;
import imagej.updater.core.FileObject;
import imagej.updater.core.FilesCollection;
import imagej.updater.core.Installer;
import imagej.updater.core.UpdaterUIPlugin;
import imagej.updater.gui.ViewOptions.Option;
import imagej.updater.util.Canceled;
import imagej.updater.util.Progress;
import imagej.updater.util.StderrLogService;
import imagej.updater.util.UpdaterUserInterface;
import imagej.updater.util.Util;
import imagej.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.net.Authenticator;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * The Updater. As plugin.
 * <p>
 * Incidentally, this class can be used as an out-of-ImageJ entry point to the
 * updater, as it does not *require* a StatusService to run. Use this Beanshell
 * script as a template:
 * </p>
 * 
 * <pre>
 *     import java.io.File;
 *     import java.net.URL;
 *     import java.net.URLClassLoader;
 * 
 *     prefix = "file:/path/to/imagej/";
 *     loader = new URLClassLoader(new URL[] {
 *         new URL(prefix + "ui/awt-swing/swing/updater/target/ij-ui-swing-updater-2.0.0-SNAPSHOT.jar"),
 *         new URL(prefix + "core/updater/core/target/ij-updater-core-2.0.0-SNAPSHOT.jar"),
 *         new URL(prefix + "core/core/target/ij-core-2.0.0-SNAPSHOT.jar")
 *     });
 *     loader.loadClass("imagej.updater.gui.ImageJUpdater").newInstance().run();
 * </pre>
 * 
 * @author Johannes Schindelin
 */
@Plugin(type = UpdaterUIPlugin.class, menu = { @Menu(label = "Help"),
	@Menu(label = "Update...") })
public class ImageJUpdater implements UpdaterUIPlugin {

	@Parameter
	private StatusService statusService;

	@Parameter
	private LogService log;

	@Override
	public void run() {

		if (log == null) {
			log = new StderrLogService();
		}

		UpdaterUserInterface.set(new SwingUserInterface(log, statusService));

		if (errorIfDebian()) return;

		final File imagejRoot = FileUtils.getBaseDirectory();
		final FilesCollection files = new FilesCollection(imagejRoot);
		final UpdaterFrame main = new UpdaterFrame(log, files);
		if (new File(imagejRoot, "update").exists()) {
			if (!UpdaterUserInterface.get().promptYesNo("ImageJ was not restarted after the previous update\n\n"
					+ "Do you want to move the files into place without a restart (dangerous)?",
					"Restart required to finalize update"))
				return;
			try {
				new Installer(files, null).moveUpdatedIntoPlace();
			} catch (IOException e) {
				log.debug(e);
				UpdaterUserInterface.get().error("Could not move files into place: " + e);
				return;
			}
		}
		Util.useSystemProxies();
		Authenticator.setDefault(new SwingAuthenticator());

		main.setEasyMode(true);
		Progress progress = main.getProgress("Starting up...");

		try {
			String warnings = files.downloadIndexAndChecksum(progress);
			main.addCustomViewOptions();
			if (!warnings.equals("")) main.warn(warnings);
			final List<Conflict> conflicts = files.getConflicts();
			if (conflicts != null && conflicts.size() > 0 &&
					!new ConflictDialog(main, "Conflicting versions") {
						private static final long serialVersionUID = 1L;

						@Override
						protected void updateConflictList() {
							conflictList = conflicts;
						}
					}.resolve())
				return;
		}
		catch (final Canceled e) {
			main.error("Canceled");
			return;
		}
		catch (final Exception e) {
			log.error(e);
			String message;
			if (e instanceof UnknownHostException) message =
				"Failed to lookup host " + e.getMessage();
			else message = "There was an error reading the cached metadata: " + e;
			main.error(message);
			return;
		}

		if (Installer.isTheUpdaterUpdateable(files)) {
			if (SwingTools.showQuestion(main, "Update the updater",
				"There is an update available for the Updater. Install now?"))
			{
				try {
					// download just the updater
					Installer.updateTheUpdater(files, main.getProgress("Installing the updater..."));
				}
				catch (final Canceled e) {
					main.error("Canceled");
				}
				catch (final IOException e) {
					main.error("Installer failed: " + e);
				}

				// make a class path using the updated files
				final List<URL> classPath = new ArrayList<URL>();
				final FileObject guiJar = files.get("jars/ij-updater-core.jar");
				for (final FileObject component : guiJar.getFileDependencies(files, true)) {
					final String name = component.getLocalFilename(false);
					File file = files.prefix(name);
					try {
						classPath.add(file.toURI().toURL());
					} catch (MalformedURLException e) {
						log.error(e);
					}
				}
				try {
					log.info("Trying to install and execute the new updater");
					new Installer(files, null).moveUpdatedIntoPlace();
					URLClassLoader remoteClassLoader = new URLClassLoader(classPath.toArray(new URL[classPath.size()]), ClassLoader.getSystemClassLoader());
					System.setProperty("imagej.update.updater", "true");
					Class<?> runnable = remoteClassLoader.loadClass(ImageJUpdater.class.getName());
					new Thread((Runnable)runnable.newInstance()).start();
					return;
				} catch (Throwable t) {
					log.error(t);
				}

				main.info("Please restart ImageJ and call Help>Update to continue with the update");
			}
			// we do not save the files to prevent the mtime from changing
			return;
		}

		main.setLocationRelativeTo(null);
		main.setVisible(true);
		main.requestFocus();

		files.markForUpdate(false);
		main.setViewOption(Option.UPDATEABLE);
		if (files.hasForcableUpdates()) {
			main.warn("There are locally modified files!");
			if (files.hasUploadableSites() && !files.hasChanges()) {
				main.setViewOption(Option.LOCALLY_MODIFIED);
				main.setEasyMode(false);
			}
		}
		else if (!files.hasChanges()) main.info("Your ImageJ is up to date!");

		main.updateFilesTable();
	}

	protected boolean overwriteWithUpdated(final FilesCollection files,
		final FileObject file)
	{
		File downloaded = files.prefix("update/" + file.filename);
		if (!downloaded.exists()) return true; // assume all is well if there is no
																						// updated file
		final File jar = files.prefix(file.filename);
		if (!jar.delete() && !moveOutOfTheWay(jar)) return false;
		if (!downloaded.renameTo(jar)) return false;
		for (;;) {
			downloaded = downloaded.getParentFile();
			if (downloaded == null) return true;
			final String[] list = downloaded.list();
			if (list != null && list.length > 0) return true;
			// dir is empty, remove
			if (!downloaded.delete()) return false;
		}
	}

	/**
	 * This returns true if this seems to be the Debian packaged version of
	 * ImageJ, or false otherwise.
	 */

	public static boolean isDebian() {
		final String debianProperty = System.getProperty("fiji.debian");
		return debianProperty != null && debianProperty.equals("true");
	}

	/**
	 * If this seems to be the Debian packaged version of ImageJ, then produce an
	 * error and return true. Otherwise return false.
	 */

	public static boolean errorIfDebian() {
		// If this is the Debian / Ubuntu packaged version, then
		// insist that the user uses apt-get / synaptic instead:
		if (isDebian()) {
			String message = "You are using the Debian packaged version of ImageJ.\n";
			message +=
				"You should update ImageJ with your system's usual package manager instead.";
			UpdaterUserInterface.get().error(message);
			return true;
		}
		else return false;
	}

	protected static boolean moveOutOfTheWay(final File file) {
		if (!file.exists()) return true;
		File backup = new File(file.getParentFile(), file.getName() + ".old");
		if (backup.exists() && !backup.delete()) {
			final int i = 2;
			for (;;) {
				backup = new File(file.getParentFile(), file.getName() + ".old" + i);
				if (!backup.exists()) break;
			}
		}
		return file.renameTo(backup);
	}

	public static void main(String[] args) {
		new ImageJUpdater().run();
	}
}
