//
// JODConverter - Java OpenDocument Converter
// Copyright 2004-2012 Mirko Nasato and contributors
//
// JODConverter is Open Source software, you can redistribute it and/or
// modify it under either (at your option) of the following licenses
//
// 1. The GNU Lesser General Public License v3 (or later)
// -> http://www.gnu.org/licenses/lgpl-3.0.txt
// 2. The Apache License, Version 2.0
// -> http://www.apache.org/licenses/LICENSE-2.0.txt
//
package org.artofsolving.jodconverter.office;

import static org.artofsolving.jodconverter.process.ProcessManager.PID_NOT_FOUND;
import static org.artofsolving.jodconverter.process.ProcessManager.PID_UNKNOWN;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.commons.io.FileUtils;
import org.artofsolving.jodconverter.process.ProcessManager;
import org.artofsolving.jodconverter.process.ProcessQuery;
import org.artofsolving.jodconverter.util.PlatformUtils;
import org.artofsolving.jodconverter.util.ProcessLoggingUtils;

class OfficeProcess {

	private final File officeHome;
	private final UnoUrl unoUrl;
	private final String[] runAsArgs;
	private final File templateProfileDir;
	private final File instanceProfileDir;
	private final ProcessManager processManager;

	private Process process;
	private long pid = PID_UNKNOWN;

	private final Logger logger = LoggerFactory.getLogger(OfficeProcess.class);
	private final Logger loggerProcessOutput = LoggerFactory.getLogger(logger.getName() + ".ProcessOutput");
	
	public OfficeProcess(File officeHome, UnoUrl unoUrl, String[] runAsArgs, File templateProfileDir, File workDir, ProcessManager processManager) {
		this.officeHome = officeHome;
		this.unoUrl = unoUrl;
		this.runAsArgs = runAsArgs;
		this.templateProfileDir = templateProfileDir;
		this.instanceProfileDir = getInstanceProfileDir(workDir, unoUrl);
		this.processManager = processManager;
	}

	public void start() throws IOException {
		start(false);
	}

    public void start(boolean restart) throws IOException {
        final File officeExecutable = OfficeUtils.getOfficeExecutable(officeHome);
		ProcessQuery processQuery = new ProcessQuery(officeExecutable.getName(), unoUrl.getAcceptString());
        long existingPid = processManager.findPid(processQuery);
		if (!(existingPid == PID_NOT_FOUND || existingPid == PID_UNKNOWN)) {
			throw new UnknownOficeProcessAlreadyExistingException(unoUrl.getAcceptString(), existingPid);
		}
		if (!restart) {
			prepareInstanceProfileDir();
		}
        List<String> command = new ArrayList<String>();
        File executable = officeExecutable;
		if (runAsArgs != null) {
			command.addAll(Arrays.asList(runAsArgs));
		}
		command.add(executable.getAbsolutePath());
		command.add("-accept=" + unoUrl.getAcceptString() + ";urp;");
		command.add("-env:UserInstallation=" + OfficeUtils.toUrl(instanceProfileDir));
		command.add("-headless");
		command.add("-nocrashreport");
		command.add("-nodefault");
		command.add("-nofirststartwizard");
		command.add("-nolockcheck");
		command.add("-nologo");
		command.add("-norestore");
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        if (PlatformUtils.isWindows()) {
			addBasisAndUrePaths(processBuilder);
		}
		logger.info(String.format("starting process with acceptString '{}' and profileDir '{}'", unoUrl, instanceProfileDir));
		process = processBuilder.start();
		pid = processManager.findPid(processQuery);
		ProcessLoggingUtils.logProcessOutput(process, pid, loggerProcessOutput);
		if (pid == PID_NOT_FOUND) {
            throw new IllegalStateException("process with acceptString '" + unoUrl.getAcceptString() + "' started but its pid could not be found");
		}
		logger.info("started process" + (pid != PID_UNKNOWN ? "; pid = " + pid : ""));
	}

	private File getInstanceProfileDir(File workDir, UnoUrl unoUrl) {
        String dirName = ".jodconverter_" + unoUrl.getAcceptString().replace(',', '_').replace('=', '-');
		return new File(workDir, dirName);
	}

	private void prepareInstanceProfileDir() throws OfficeException {
		if (instanceProfileDir.exists()) {
			logger.warn("profile dir '{}' already exists; deleting", instanceProfileDir);
			deleteProfileDir();
		}
		if (templateProfileDir != null) {
			try {
				FileUtils.copyDirectory(templateProfileDir, instanceProfileDir);
            } catch (IOException ioException) {
				throw new OfficeException("failed to create profileDir", ioException);
			}
		}
	}

	public void deleteProfileDir() {
		if (instanceProfileDir != null) {
			try {
				FileUtils.deleteDirectory(instanceProfileDir);
            } catch (IOException ioException) {
                File oldProfileDir = new File(instanceProfileDir.getParentFile(), instanceProfileDir.getName() + ".old." + System.currentTimeMillis());
				if (instanceProfileDir.renameTo(oldProfileDir)) {
					logger.warn("could not delete profileDir: " + ioException.getMessage() + "; renamed it to " + oldProfileDir);
				} else {
					logger.warn("could not delete profileDir: " + ioException.getMessage());
				}
			}
		}
	}

    private void addBasisAndUrePaths(ProcessBuilder processBuilder) throws IOException {
		// see http://wiki.services.openoffice.org/wiki/ODF_Toolkit/Efforts/Three-Layer_OOo
        File basisLink = new File(officeHome, "basis-link");
		if (!basisLink.isFile()) {
			logger.debug("no %OFFICE_HOME%/basis-link found; assuming it's OOo 2.x and we don't need to append URE and Basic paths");
			return;
		}
        String basisLinkText = FileUtils.readFileToString(basisLink).trim();
        File basisHome = new File(officeHome, basisLinkText);
        File basisProgram = new File(basisHome, "program");
        File ureLink = new File(basisHome, "ure-link");
        String ureLinkText = FileUtils.readFileToString(ureLink).trim();
        File ureHome = new File(basisHome, ureLinkText);
        File ureBin = new File(ureHome, "bin");
        Map<String,String> environment = processBuilder.environment();
		// Windows environment variables are case insensitive but Java maps are not :-/
		// so let's make sure we modify the existing key
		String pathKey = "PATH";
        for (String key : environment.keySet()) {
			if ("PATH".equalsIgnoreCase(key)) {
				pathKey = key;
			}
		}
        String path = environment.get(pathKey) + ";" + ureBin.getAbsolutePath() + ";" + basisProgram.getAbsolutePath();
		logger.debug(String.format("setting %s to \"%s\"", pathKey, path));
		environment.put(pathKey, path);
	}

	public boolean isRunning() {
		if (process == null) {
			return false;
		}
		try {
			long foundPid = this.processManager.findPid(new ProcessQuery(OfficeUtils.getOfficeExecutable(officeHome).getName(), this.unoUrl.getAcceptString()));
			if(foundPid == PID_NOT_FOUND) {
				return false;
			}
		} catch(IOException e) {
			logger.warn("Could not even request pid to find");
			//fail back to exit code
		}

		return getExitCode() == null;
	}

	private class ExitCodeRetryable extends Retryable {

		private int exitCode;

		protected void attempt() throws TemporaryException, Exception {
			try {
				exitCode = process.exitValue();
            } catch (IllegalThreadStateException illegalThreadStateException) {
				throw new TemporaryException(illegalThreadStateException);
			}
		}

		public int getExitCode() {
			return exitCode;
		}

	}

	public Integer getExitCode() {
		try {
			return process.exitValue();
        } catch (IllegalThreadStateException exception) {
			return null;
		}
	}

    public int getExitCode(long retryInterval, long retryTimeout) throws RetryTimeoutException {
		try {
            ExitCodeRetryable retryable = new ExitCodeRetryable();
			retryable.execute(retryInterval, retryTimeout);
			return retryable.getExitCode();
        } catch (RetryTimeoutException retryTimeoutException) {
			throw retryTimeoutException;
        } catch (Exception exception) {
			throw new OfficeException("could not get process exit code", exception);
		}
	}

    public int forciblyTerminate(long retryInterval, long retryTimeout) throws IOException, RetryTimeoutException {
        this.logger.info("trying to forcibly terminate process: '" + this.unoUrl + "'"
            + (this.pid != PID_UNKNOWN ? " (pid " + this.pid + ")" : ""));
        if (this.pid == PID_UNKNOWN) {
            long foundPid = this.processManager.findPid(new ProcessQuery(OfficeUtils.getOfficeExecutable(officeHome).getName(), this.unoUrl.getAcceptString()));
            this.processManager.kill(this.process, foundPid);
        } else {
        	this.processManager.kill(this.process, this.pid);
        }
        return getExitCode(retryInterval, retryTimeout);
    }

}
