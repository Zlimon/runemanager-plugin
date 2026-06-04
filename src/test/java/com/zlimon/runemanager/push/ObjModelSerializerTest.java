package com.zlimon.runemanager.push;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import net.runelite.api.JagexColor;
import org.junit.Test;

public class ObjModelSerializerTest
{
	private static String obj(ObjModelSerializer.Result result)
	{
		return new String(result.obj(), StandardCharsets.UTF_8);
	}

	private static String mtl(ObjModelSerializer.Result result)
	{
		return new String(result.mtl(), StandardCharsets.UTF_8);
	}

	@Test
	public void serialisesASingleTriangle()
	{
		ObjModelSerializer.Result result = ObjModelSerializer.serialize(
			new float[]{0, 1, 0}, new float[]{0, 0, 1}, new float[]{0, 0, 0}, 3,
			new int[]{0}, new int[]{1}, new int[]{2},
			new short[]{JagexColor.packHSL(0, 0, 64)}, 1);

		String obj = obj(result);
		assertTrue(obj.contains("mtllib avatar.mtl"));
		assertTrue(obj.contains("o player"));
		// Three vertices, one usemtl, one 1-based face.
		assertEquals(3, countLines(obj, "v "));
		assertTrue(obj.contains("usemtl m0"));
		assertTrue(obj.contains("f 1 2 3"));

		String mtl = mtl(result);
		assertEquals(1, countLines(mtl, "newmtl "));
		assertTrue(mtl.contains("newmtl m0"));
		assertTrue(mtl.contains("Kd "));
	}

	@Test
	public void flipsTheYAxisSoTheModelIsUpright()
	{
		ObjModelSerializer.Result result = ObjModelSerializer.serialize(
			new float[]{0}, new float[]{10}, new float[]{0}, 1,
			new int[]{0}, new int[]{0}, new int[]{0},
			new short[]{JagexColor.packHSL(0, 0, 64)}, 1);

		assertTrue(obj(result).contains("v 0.0000 -10.0000 0.0000"));
	}

	@Test
	public void reusesAMaterialForFacesOfTheSameColourAndAddsOneForADifferentColour()
	{
		short red = JagexColor.packHSL(0, 7, 64);
		short blue = JagexColor.packHSL(43, 7, 64);

		ObjModelSerializer.Result result = ObjModelSerializer.serialize(
			new float[]{0, 1, 0, 1}, new float[]{0, 0, 1, 1}, new float[]{0, 0, 0, 0}, 4,
			new int[]{0, 0}, new int[]{1, 1}, new int[]{2, 3},
			new short[]{red, red}, 2);

		// Two same-colour faces => a single material.
		assertEquals(1, countLines(mtl(result), "newmtl "));

		ObjModelSerializer.Result twoColours = ObjModelSerializer.serialize(
			new float[]{0, 1, 0, 1}, new float[]{0, 0, 1, 1}, new float[]{0, 0, 0, 0}, 4,
			new int[]{0, 0}, new int[]{1, 1}, new int[]{2, 3},
			new short[]{red, blue}, 2);

		assertEquals(2, countLines(mtl(twoColours), "newmtl "));
	}

	@Test
	public void higherLuminanceProducesABrighterColour()
	{
		int dark = brightness(ObjModelSerializer.hslToRgb(JagexColor.packHSL(20, 4, 20)));
		int light = brightness(ObjModelSerializer.hslToRgb(JagexColor.packHSL(20, 4, 110)));

		assertTrue("brighter luminance should sum higher channels", light > dark);
	}

	private static int brightness(int rgb)
	{
		return ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
	}

	private static int countLines(String text, String prefix)
	{
		int count = 0;
		for (String line : text.split("\n"))
		{
			if (line.startsWith(prefix))
			{
				count++;
			}
		}
		return count;
	}
}
