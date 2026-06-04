package com.zlimon.runemanager.push;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.runelite.api.JagexColor;

/**
 * Serialises a RuneLite {@link net.runelite.api.Model}'s geometry into a
 * Wavefront OBJ + MTL pair. Pure (no client/game state) so it can be unit
 * tested and run off the client thread.
 *
 * Each distinct unlit face colour becomes one MTL material; faces are grouped
 * by material so the OBJ emits a single {@code usemtl} per colour. Texture maps
 * (e.g. fire/infernal capes) aren't reproduced — those faces fall back to their
 * flat colour, which is fine for a static avatar.
 */
public final class ObjModelSerializer
{
	private static final String MTL_FILENAME = "avatar.mtl";

	public static final class Result
	{
		private final byte[] obj;
		private final byte[] mtl;

		Result(byte[] obj, byte[] mtl)
		{
			this.obj = obj;
			this.mtl = mtl;
		}

		public byte[] obj()
		{
			return obj;
		}

		public byte[] mtl()
		{
			return mtl;
		}
	}

	private ObjModelSerializer()
	{
	}

	public static Result serialize(
		float[] verticesX, float[] verticesY, float[] verticesZ, int vertexCount,
		int[] faceIndicesA, int[] faceIndicesB, int[] faceIndicesC,
		short[] faceColors, int faceCount)
	{
		// Distinct face colour (RGB) → material name, in first-seen order.
		Map<Integer, String> materials = new LinkedHashMap<>();
		// Material name → its accumulated `f` lines, so each colour is emitted once.
		Map<String, StringBuilder> facesByMaterial = new LinkedHashMap<>();

		for (int face = 0; face < faceCount; face++)
		{
			int rgb = hslToRgb(faceColors[face]);
			String material = materials.computeIfAbsent(rgb, key -> "m" + materials.size());

			facesByMaterial
				.computeIfAbsent(material, key -> new StringBuilder())
				.append("f ")
				.append(faceIndicesA[face] + 1).append(' ')
				.append(faceIndicesB[face] + 1).append(' ')
				.append(faceIndicesC[face] + 1).append('\n');
		}

		StringBuilder obj = new StringBuilder();
		obj.append("# RuneManager player avatar export\n");
		obj.append("mtllib ").append(MTL_FILENAME).append('\n');
		obj.append("o player\n");
		for (int v = 0; v < vertexCount; v++)
		{
			// OSRS height runs along -Y; flip it so the model stands upright in OBJ.
			obj.append("v ")
				.append(format(verticesX[v])).append(' ')
				.append(format(-verticesY[v])).append(' ')
				.append(format(verticesZ[v])).append('\n');
		}
		for (Map.Entry<String, StringBuilder> entry : facesByMaterial.entrySet())
		{
			obj.append("usemtl ").append(entry.getKey()).append('\n');
			obj.append(entry.getValue());
		}

		StringBuilder mtl = new StringBuilder();
		mtl.append("# RuneManager player avatar materials\n");
		for (Map.Entry<Integer, String> entry : materials.entrySet())
		{
			int rgb = entry.getKey();
			double r = ((rgb >> 16) & 0xFF) / 255.0;
			double g = ((rgb >> 8) & 0xFF) / 255.0;
			double b = (rgb & 0xFF) / 255.0;

			mtl.append("newmtl ").append(entry.getValue()).append('\n');
			mtl.append("Kd ").append(format(r)).append(' ').append(format(g)).append(' ').append(format(b)).append('\n');
		}

		return new Result(
			obj.toString().getBytes(StandardCharsets.UTF_8),
			mtl.toString().getBytes(StandardCharsets.UTF_8));
	}

	private static String format(double value)
	{
		return String.format(Locale.ROOT, "%.4f", value);
	}

	/**
	 * Convert a Jagex-packed 16-bit HSL face colour to a packed 0xRRGGBB int.
	 * The hue/saturation half-bucket offsets mirror the client's palette build;
	 * note the saturation offset means even "saturation 0" carries a faint tint,
	 * exactly as in-game.
	 */
	static int hslToRgb(short hsl)
	{
		double hue = (double) JagexColor.unpackHue(hsl) / (JagexColor.HUE_MAX + 1)
			+ 0.5 / (JagexColor.HUE_MAX + 1);
		double saturation = (double) JagexColor.unpackSaturation(hsl) / (JagexColor.SATURATION_MAX + 1)
			+ 0.5 / (JagexColor.SATURATION_MAX + 1);
		double lightness = (double) JagexColor.unpackLuminance(hsl) / (JagexColor.LUMINANCE_MAX + 1);

		double q = lightness < 0.5
			? lightness * (1.0 + saturation)
			: lightness + saturation - lightness * saturation;
		double p = 2.0 * lightness - q;
		double r = hueToChannel(p, q, hue + 1.0 / 3.0);
		double g = hueToChannel(p, q, hue);
		double b = hueToChannel(p, q, hue - 1.0 / 3.0);

		return (clampChannel(r) << 16) | (clampChannel(g) << 8) | clampChannel(b);
	}

	private static int clampChannel(double channel)
	{
		int value = (int) Math.round(channel * 255.0);
		return Math.max(0, Math.min(255, value));
	}

	private static double hueToChannel(double p, double q, double t)
	{
		if (t < 0.0)
		{
			t += 1.0;
		}
		if (t > 1.0)
		{
			t -= 1.0;
		}
		if (t < 1.0 / 6.0)
		{
			return p + (q - p) * 6.0 * t;
		}
		if (t < 1.0 / 2.0)
		{
			return q;
		}
		if (t < 2.0 / 3.0)
		{
			return p + (q - p) * (2.0 / 3.0 - t) * 6.0;
		}
		return p;
	}
}
