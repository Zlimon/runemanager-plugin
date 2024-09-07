package com.zlimon.twitch;

import lombok.Getter;

public enum TwitchSegmentType {
	BROADCASTER("broadcaster"),
	GLOBAL("global"),
	DEVELOPER("developer"),
	;

	@Getter
	private final String key;

	TwitchSegmentType(String key)
	{
		this.key = key;
	}
}

