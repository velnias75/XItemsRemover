/*
 *  This file is part of XItemsRemover,
 *  licensed under the Apache License, Version 2.0.
 *
 *  Copyright (c) Xezard (Zotov Ivan)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package ru.xezard.items.remover.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent.Builder;
import ru.xezard.items.remover.ItemsRemoverPlugin;
import ru.xezard.items.remover.configurations.Configurations;
import ru.xezard.items.remover.utils.Chat;
import ru.xezard.items.remover.utils.Materials;

public class TrackingManager
{
	private final static String TRANSLATED_MATERIAL_NAME = "{translated_material_name}";

	private List<Map<Entity, Long>> entities = new ArrayList<Map<Entity, Long>> (20)
    {{
        for (int i = 0; i < 20; i++)
        {
            this.add(i, new ConcurrentHashMap<> ());
        }
    }};

    private NavigableMap<Long, String> displayNames = new TreeMap<> ();

    private Map<Integer, Integer> ids = new HashMap<> ();

    private Map<String, TrackData> trackData = new HashMap<> ();

    private Configurations configurations;

    private ItemsRemoverPlugin plugin;

    private BukkitTask removerTask;

    private AtomicInteger tick = new AtomicInteger(0);

    private Logger logger;

    public TrackingManager(Configurations configurations, ItemsRemoverPlugin plugin, Logger logger)
    {
        this.configurations = configurations;

        this.plugin = plugin;

        this.logger = logger;
    }

    public void load(FileConfiguration config)
    {
        ConfigurationSection customMaterialsSection = config.getConfigurationSection("Items.Remove-timer.Custom-materials"),
                             displayNamesSection = config.getConfigurationSection("Items.Display-name-formats");

        if (customMaterialsSection != null)
        {
            for (String typeName : customMaterialsSection.getKeys(false))
            {
                String sectionKey = "Items.Remove-timer.Custom-materials." + typeName + ".";

                NavigableMap<Long, String> dataDisplayNames = new TreeMap<> ();

                ConfigurationSection typeDisplayNamesSection = config.getConfigurationSection(sectionKey + "Display-name-formats");

                if (typeDisplayNamesSection == null) 
                {
                    continue;
                }

                for (String dataTimerString : typeDisplayNamesSection.getKeys(false))
                {
                    dataDisplayNames.put(Long.parseLong(dataTimerString), config.getString(sectionKey + "Display-name-formats." + dataTimerString));
                }

                long timer = config.getLong(sectionKey + "Timer", -1);

                boolean tracked = config.getBoolean(sectionKey + "Tracked", true);

                this.trackData.put(typeName, new TrackData(dataDisplayNames, timer, tracked));
            }
        } else {
            this.logger.warning("Custom drop data was not loaded.");
        }

        if (displayNamesSection == null) 
        {
            this.logger.warning("Can't find display names section for items!");
            return;
        }

        for (String timerString : displayNamesSection.getKeys(false))
        {
            this.displayNames.put(Long.parseLong(timerString), config.getString("Items.Display-name-formats." + timerString));
        }
    }

    private synchronized void clearEntities()
    {
        int tick = this.tick.incrementAndGet();

        if (tick == 20)
        {
            tick = 0;
            this.tick.set(0);
        }

        Map<Entity, Long> entities = this.entities.get(tick);

        for (Map.Entry<Entity, Long> entry : entities.entrySet())
        {
            Entity entity = entry.getKey();

            TrackData data = this.trackData.get(this.getType(entity));

            long time = entry.getValue();

            Optional<String> entityDisplayNameFormat = Optional.ofNullable(data != null ? data.getDisplayNames().ceilingEntry(time) : null)
                                                               .map(Map.Entry::getValue);

            String entityDisplayName = "",
                   displayNameFormat = Optional.ofNullable(this.displayNames.ceilingEntry(time))
                                               .orElse(this.displayNames.lastEntry())
                                               .getValue();

            int amount = 1;

            if (entity instanceof Item)
            {
                Item item = (Item) entity;

                ItemStack itemStack = item.getItemStack();

                ItemMeta itemMeta = itemStack.getItemMeta();

                entityDisplayName = itemMeta.hasDisplayName() ? 
                                    itemMeta.getDisplayName() :
                                    	TRANSLATED_MATERIAL_NAME;

                amount = itemStack.getAmount();
            }

            String displayName = entityDisplayNameFormat.orElse(displayNameFormat);

            if (time > 0)
            {
                entry.setValue(time - 1);

                final String base = Chat.colorize(displayName
                        .replace("{time}", Long.toString(time))
                        .replace("{amount}", Integer.toString(amount)));

                final int dn_idx = base.indexOf("{display_name}");
                final Builder customNameBuilder;

                if(entity instanceof Item && dn_idx != -1) {

                	customNameBuilder = Component.text().content(base.substring(0, dn_idx));

                	final int tmn_idx = entityDisplayName.indexOf(TRANSLATED_MATERIAL_NAME);

                	if(tmn_idx != -1) {
                		customNameBuilder.append(Component.translatable(((Item) entity).
                				getItemStack().getType()));
                	} else {
                		customNameBuilder.append(Component.text(entityDisplayName));
                	}

                	customNameBuilder.append(Component.text(base.substring(dn_idx + 14)));

                } else {
                	customNameBuilder = Component.text().content(base);
                }

                entity.customName(customNameBuilder.build());
                continue;
            }

            this.removeEntity(entity);

            entity.remove();
        }
    }

    public void updateTimeForAll()
    {
        this.entities.forEach((map) ->
        {
            map.replaceAll((entity, entityTime) ->
            {
                return this.getTimeByEntity(entity, false);
            });
        });
    }

    public void startRemoverTask()
    {
        if (this.configurations.get("config.yml").getBoolean("Items.Remove.Async"))
        {
            this.removerTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this.plugin, this::clearEntities, 0, 1);
        } else {
            this.removerTask = Bukkit.getScheduler().runTaskTimer(this.plugin, this::clearEntities, 0, 1);
        }
    }

    private long getTimeByEntity(Entity entity, boolean afterDeath)
    {
        String typeName = this.getType(entity);

        TrackData data = this.trackData.get(typeName);

        FileConfiguration config = this.configurations.get("config.yml");

        long time = afterDeath ? config.getLong("Items.Remove-timer.After-player-death") :
                                 config.getLong("Items.Remove-timer.Default");

        if (data != null)
        {
            long timer = data.getTimer();

            if (timer > 0)
            {
                time = timer;
            }
        }

        return time;
    }

    public void addEntity(Entity entity, boolean afterDeath)
    {
        String typeName = this.getType(entity);

        int entityId = entity.getEntityId();

        if (this.ids.containsKey(entityId) || 
            !this.tracked(typeName) ||
            this.configurations.get("config.yml")
                               .getStringList("Restricted-worlds")
                               .contains(entity.getWorld().getName()) ||
            entity.hasMetadata("no_pickup")) // fix for slimefun
        {
            return;
        }

        entity.setCustomNameVisible(true);

        int currentTick = this.tick.get();

        this.entities.get(currentTick).put(entity, this.getTimeByEntity(entity, afterDeath));
        this.ids.put(entityId, currentTick);
    }

    public void removeEntity(Entity entity)
    {
        int entityId = entity.getEntityId(),
            tick = this.ids.getOrDefault(entityId, -1);

        if (tick == -1)
        {
            return;
        }

        this.entities.get(tick).remove(entity);
        this.ids.remove(entityId);
    }

    public void setEntityTimer(Entity entity)
    {
        int tick = this.ids.getOrDefault(entity.getEntityId(), -1);

        if (tick == -1)
        {
            return;
        }

        this.entities.get(tick).replace(entity, this.getTimeByEntity(entity, false));
    }

    public void mergeItems(Item merged, Item target)
    {
        this.removeEntity(merged);
        this.setEntityTimer(target);
    }

    public boolean tracked(String typeName) 
    {
        TrackData data = this.trackData.get(typeName);

        if (data == null) 
        {
            return true;
        }

        return data.isTracked();
    }

    private String getType(Entity entity) 
    {
        String typeName = entity.getType().name();

        if (entity instanceof Item)
        {
            Item item = (Item) entity;

            typeName = item.getItemStack().getType().name();
        }

        return typeName;
    }

    public void clearData()
    {
        this.displayNames.clear();
        this.trackData.clear();
    }

    public void clear()
    {
        this.entities.forEach(Map::clear);
    }

    public void stopRemoverTask()
    {
        this.removerTask.cancel();
    }
}
