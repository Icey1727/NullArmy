package com.example.nullarmy;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class NullArmy extends JavaPlugin {

    private ArmyManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new ArmyManager(this);

        getServer().getPluginManager().registerEvents(new NullListener(this, manager), this);
        getServer().getPluginManager().registerEvents(new ChatCommander(this, manager), this);

        NullCommand cmd = new NullCommand(this, manager);
        PluginCommand pc = getCommand("nullarmy");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }

        manager.startTasks();
        getLogger().info("NullArmy enabled.");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.stopTasks();
            manager.workers().stopAll();
            manager.puppets().removeAll();
        }
    }

    public ArmyManager manager() {
        return manager;
    }
}
