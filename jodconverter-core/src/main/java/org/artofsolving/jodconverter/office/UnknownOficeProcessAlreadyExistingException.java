package org.artofsolving.jodconverter.office;

public class UnknownOficeProcessAlreadyExistingException extends IllegalStateException {

	private final long existingPid;
	private final String acceptString;

	public UnknownOficeProcessAlreadyExistingException(final String acceptString, final long existingPid) {
		super(String.format("a process with acceptString '%s' is already running; pid %d", acceptString, existingPid));
		this.acceptString = acceptString;
		this.existingPid = existingPid;
	}

	public long getExistingPid() {
		return existingPid;
	}

	public String getAcceptString() {
		return acceptString;
	}
}
