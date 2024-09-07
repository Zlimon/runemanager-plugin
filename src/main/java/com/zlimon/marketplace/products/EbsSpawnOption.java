package com.zlimon.marketplace.products;

import java.util.ArrayList;

import static com.zlimon.marketplace.MarketplaceConstants.INDIVIDUAL_SPAWN_POINT_TYPE;

public class EbsSpawnOption {
	public Double chance;
	public ArrayList<EbsCondition> conditions;
	public EbsRandomRange spawnAmount;
	public EbsRandomRange spawnDelayMs;
	public ArrayList<EbsSpawn> spawns;
	public String spawnPointType = INDIVIDUAL_SPAWN_POINT_TYPE;
}
