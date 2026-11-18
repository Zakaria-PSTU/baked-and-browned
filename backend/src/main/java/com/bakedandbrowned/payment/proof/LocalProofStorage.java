package com.bakedandbrowned.payment.proof;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Proof files on a local directory ({@code app.payments.proof-storage.directory}), one file per
 * random UUID key. Keys are validated before use, so no caller-supplied path can escape the
 * directory. Writes go to a temporary file first and are moved into place atomically.
 */
@Component
@EnableConfigurationProperties(LocalProofStorage.Properties.class)
public class LocalProofStorage implements ProofStorage {

	private static final Pattern KEY = Pattern
		.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

	private final Path directory;

	public LocalProofStorage(Properties properties) {
		this.directory = properties.directory().toAbsolutePath().normalize();
		try {
			Files.createDirectories(directory);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot create proof storage directory " + directory, ex);
		}
	}

	@Override
	public String store(byte[] content) {
		String key = UUID.randomUUID().toString();
		try {
			Path temp = Files.createTempFile(directory, "upload-", ".tmp");
			try {
				Files.write(temp, content);
				Files.move(temp, path(key), StandardCopyOption.ATOMIC_MOVE);
			}
			finally {
				Files.deleteIfExists(temp);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot store proof file", ex);
		}
		return key;
	}

	@Override
	public byte[] read(String key) {
		try {
			return Files.readAllBytes(path(key));
		}
		catch (NoSuchFileException ex) {
			throw new ProofNotFoundException(key);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot read proof file", ex);
		}
	}

	@Override
	public void delete(String key) {
		try {
			Files.deleteIfExists(path(key));
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot delete proof file", ex);
		}
	}

	private Path path(String key) {
		if (!KEY.matcher(key).matches()) {
			throw new IllegalArgumentException("Not a proof storage key");
		}
		return directory.resolve(key);
	}

	/**
	 * {@code app.payments.proof-storage.*}.
	 *
	 * @param directory where proof files live: a private volume outside any web root, backed up
	 * with the database
	 */
	@ConfigurationProperties("app.payments.proof-storage")
	public record Properties(Path directory) {

		public Properties {
			Objects.requireNonNull(directory, "app.payments.proof-storage.directory");
		}

	}

}
