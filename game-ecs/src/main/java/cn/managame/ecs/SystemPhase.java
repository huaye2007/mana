package cn.managame.ecs;

/** Stable phases for one world update. */
public enum SystemPhase {
    INPUT,
    SIMULATION,
    POST_SIMULATION
}
