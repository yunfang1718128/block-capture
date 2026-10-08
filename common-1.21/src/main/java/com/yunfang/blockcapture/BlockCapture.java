package com.yunfang.blockcapture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loader-agnostic identity and platform metadata shared by every target.
 *
 * <p>The common core must not depend on any loader entry class, so the mod id,
 * logger and the {@code mcVersion}/{@code modLoader} written into the
 * {@code .mcvox} header all live here. Each loader's entry point calls
 * {@link #setPlatform(String, String)} during initialisation.
 */
public final class BlockCapture {
	public static final String MOD_ID = "block-capture";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Output folder inside the game directory. */
	public static final String OUTPUT_DIR = "block-capture";

	private static volatile String mcVersion = "1.21.1";
	private static volatile String loader = "unknown";

	private BlockCapture() {
	}

	public static void setPlatform(String minecraftVersion, String modLoader) {
		mcVersion = minecraftVersion;
		loader = modLoader;
	}

	public static String mcVersion() {
		return mcVersion;
	}

	public static String loader() {
		return loader;
	}
}
