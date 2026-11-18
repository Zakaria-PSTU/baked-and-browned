package com.bakedandbrowned.payment.proof;

/**
 * Private storage for manual-payment proof files (architecture, Payment strategy). Files are
 * addressed by an opaque key the storage generates; nothing about them is ever served from a web
 * root. The MVP stores them on a local volume ({@link LocalProofStorage}).
 */
public interface ProofStorage {

	/** Stores {@code content} and returns its key (at most 255 characters). */
	String store(byte[] content);

	/**
	 * @throws ProofNotFoundException if no file has this key
	 */
	byte[] read(String key);

	/** Removes the file if it exists (used to clean up after a failed submission). */
	void delete(String key);

	/** No stored file under the given key. */
	class ProofNotFoundException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		public ProofNotFoundException(String key) {
			super("No proof file stored under key " + key);
		}

	}

}
