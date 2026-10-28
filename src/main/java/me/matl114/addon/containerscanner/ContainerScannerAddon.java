package me.matl114.addon.containerscanner;

import me.matl114.hacks.api.ModuleGroup;
import me.matl114.managers.config.Config;
import me.matl114.plugins.SlimefunHelperPlugin;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

public final class ContainerScannerAddon implements SlimefunHelperPlugin {
    public static final String ID = "sfh_container_scanner";
    public static Config CONFIG;

    @Override
    public void onConfigBootstrap() {
        Config config = loadRuntimeConfig("container-scanner.yml", "container scanner settings");
        CONFIG = registerConfig(config);
    }

    @Override
    public void onListenerBootstrap() {}

    @Override
    public void onModuleBootstrap() {
        ModuleGroup group = new ModuleGroup("ContainerScanner");
        group.registerFactories(manager -> new ContainerScannerModule().register(manager));
        registerModuleGroup(group);
    }

    @Override
    public void postInitialize() {}

    @Override
    public String getPluginId() {
        return ID;
    }

    @Override
    public ModContainer getContainer() {
        return FabricLoader.getInstance().getModContainer(ID).orElseThrow();
    }
}
