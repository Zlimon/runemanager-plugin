package com.zlimon.runemanager;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.hiscore.HiscoreSkill;

/**
 * Temporary helper used to bootstrap the website's /public/images/boss/ tree
 * with the same 25×25 icons that the RuneLite hiscore panel paints.
 *
 * RuneLite's HiscorePanel pulls these from the OSRS sprite cache at runtime
 * via {@link SpriteManager#getSpriteAsync}, so they aren't shipped as bundled
 * PNGs anywhere we can scrape from. The extractor walks the full
 * {@link HiscoreSkill} enum (skills + activities + bosses), fetches each
 * sprite, and writes it as {@code {hiscore-skill-name-lowercase-dashed}.png}
 * under {@code ~/.runelite/runemanager-hiscore-icons/}.
 *
 * Triggered from {@link RuneManagerPlugin} when the matching config flag is
 * set; the flag is cleared back to false after the dump so it doesn't loop
 * on every launch. Once the website has the icons committed this class can
 * be removed entirely.
 */
@Slf4j
@Singleton
public class HiscoreIconExtractor
{
	@Inject
	private SpriteManager spriteManager;

	public File extractAll()
	{
		File outDir = new File(RuneLite.RUNELITE_DIR, "runemanager-hiscore-icons");
		if (!outDir.exists() && !outDir.mkdirs())
		{
			log.warn("RuneManager: couldn't create extract dir {}", outDir);
			return outDir;
		}

		int requested = 0;
		for (HiscoreSkill skill : HiscoreSkill.values())
		{
			int spriteId = skill.getSpriteId();
			if (spriteId == -1)
			{
				continue;
			}
			final String filename = skill.name().toLowerCase(Locale.ROOT).replace('_', '-') + ".png";

			spriteManager.getSpriteAsync(spriteId, 0, (BufferedImage img) -> writeSprite(outDir, filename, img));
			requested++;
		}

		log.info("RuneManager: queued {} hiscore-icon extracts to {}", requested, outDir);
		return outDir;
	}

	private void writeSprite(File dir, String filename, BufferedImage img)
	{
		if (img == null)
		{
			log.debug("RuneManager: null sprite for {}, skipping", filename);
			return;
		}
		try
		{
			ImageIO.write(img, "png", new File(dir, filename));
		}
		catch (IOException e)
		{
			log.warn("RuneManager: failed to write {}: {}", filename, e.getMessage());
		}
	}
}
