package com.zlimon.fights;

import lombok.Getter;

public enum CombatStyle {
    MELEE("melee"),
    RANGED("ranged"),
    MAGIC("magic"),
    ;

    @Getter
    private final String key;

    CombatStyle(String key)
    {
        this.key = key;
    }
}
