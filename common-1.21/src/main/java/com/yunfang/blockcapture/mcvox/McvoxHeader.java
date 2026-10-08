package com.yunfang.blockcapture.mcvox;

/**
 * Header JSON of a `.mcvox` file. Field names must match the studio's reader.
 *
 * <p>The container is shared with entity-capture, so the id/name fields keep
 * their original {@code entityId}/{@code entityName} names: an older studio
 * build that predates {@link #subject} still reads a block capture correctly
 * (it just labels it as an entity). {@code subject} and {@code blockState} are
 * additive optional keys, and because the header is length-prefixed JSON they
 * cannot disturb the payload offset.
 */
public final class McvoxHeader {
	public int formatVersion;
	/** Namespaced block id, e.g. {@code minecraft:oak_stairs}. */
	public String entityId;
	public String entityName;
	public String mcVersion;
	public String modLoader;
	public int unitsPerBlock;
	public int[] dimensions;
	public boolean solid;
	public int animatedTick;
	public String generatedAt;
	/** Always {@code "block"} for this mod; absent means an entity capture. */
	public String subject;
	/** Full block state string, e.g. {@code oak_stairs[facing=east,half=bottom]}. */
	public String blockState;
	/**
	 * How the geometry was obtained: {@code model}, {@code blockEntity}, or
	 * {@code model+blockEntity}. Purely diagnostic — the studio ignores it.
	 */
	public String captureMode;

	public McvoxHeader(
			int formatVersion,
			String entityId,
			String entityName,
			String mcVersion,
			String modLoader,
			int unitsPerBlock,
			int[] dimensions,
			boolean solid,
			int animatedTick,
			String generatedAt,
			String subject,
			String blockState,
			String captureMode) {
		this.formatVersion = formatVersion;
		this.entityId = entityId;
		this.entityName = entityName;
		this.mcVersion = mcVersion;
		this.modLoader = modLoader;
		this.unitsPerBlock = unitsPerBlock;
		this.dimensions = dimensions;
		this.solid = solid;
		this.animatedTick = animatedTick;
		this.generatedAt = generatedAt;
		this.subject = subject;
		this.blockState = blockState;
		this.captureMode = captureMode;
	}
}
