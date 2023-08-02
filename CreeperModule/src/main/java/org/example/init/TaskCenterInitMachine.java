package org.example.init;

import org.example.constpool.ConstPool;
import org.example.constpool.ModuleName;
import org.example.constpool.PluginName;
import org.example.plugin.annotation.Plugin;
import org.example.taskcenter.TaskCenter;

import java.util.List;

/**
 * @author Genius
 * @date 2023/07/28 23:49
 **/

@Plugin(moduleName = ModuleName.CREEPER,
        pluginName = PluginName.TASK_CENTER_PLUGIN,
        needPlugin = {PluginName.FILE_CACHE_PLUGIN,PluginName.CREEPER_CONFIG_PLUGIN},
        pluginClass= TaskCenter.class )
public class TaskCenterInitMachine extends CommonInitMachine{


    public TaskCenterInitMachine(List<String> needPlugins, boolean isAutoStart, String moduleName, String name, Class clazz) {
        super(needPlugins, isAutoStart, moduleName, name, clazz);
    }

    @Override
    public void shutdown() {
        this.getPlugin().shutdown();
    }

}