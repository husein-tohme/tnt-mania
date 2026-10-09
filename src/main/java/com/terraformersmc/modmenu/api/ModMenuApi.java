package com.terraformersmc.modmenu.api;

/**
 * Compile-time stand-in for Mod Menu's interface of the same name (only the one method this mod uses).
 * NOT put in the jar: when Mod Menu is installed, its own interface is the one that is used.
 */
public interface ModMenuApi {
    default ConfigScreenFactory<?> getModConfigScreenFactory() {
        return screen -> null;
    }
}
