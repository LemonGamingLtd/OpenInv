/*
 * Copyright (C) 2011-2022 lishid. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.lishid.openinv.util.lang;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * A simple language manager supporting both custom and bundled languages.
 *
 * @author Jikoo
 */
public class LanguageManager {

    private final Plugin plugin;
    private final File folder;
    private final String defaultLocale;
    private final Map<String, YamlConfiguration> locales;

    public LanguageManager(@NotNull Plugin plugin, @NotNull String defaultLocale) {
        this.plugin = plugin;
        this.defaultLocale = defaultLocale;
        this.locales = new HashMap<>();
        this.folder = new File(plugin.getDataFolder(), "locale");

        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning(() -> "Unable to create " + folder.getPath() + "! Languages may not be editable.");
        }

        reload();
    }

    public void reload() {
        this.locales.clear();
        getOrLoadLocale(defaultLocale);
    }

    private @NotNull YamlConfiguration getOrLoadLocale(@NotNull String locale) {
        YamlConfiguration loaded = locales.get(locale);
        if (loaded != null) {
            return loaded;
        }

        LangLocation lang = bestMatch(locale, null);

        // If a parent was a better match, check if it is already loaded.
        if (!locale.equals(lang.locale)) {
            loaded = locales.get(lang.locale);
            if (loaded != null) {
                locales.put(locale, loaded);
                return loaded;
            }
        }

        // Load locale config from disk and bundled locale defaults.
        YamlConfiguration localeConfig = loadLocale(lang);

        // If the locale is not the default locale, also handle any missing translations from the default locale.
        if (!locale.equals(defaultLocale)) {
            addTranslationFallthrough(lang, localeConfig);

            if (plugin.getConfig().getBoolean("settings.secret.warn-about-guess-section", true)
                    && localeConfig.isConfigurationSection("guess")) {
                // Warn that guess section exists. This should run once per language per server restart
                // when accessed by a user to hint to server owners that they can make UX improvements.
                plugin.getLogger().info(() -> "[LanguageManager] Missing translations from " + lang.locale + ".yml! Check the guess section!");
            }
        }

        locales.put(locale, localeConfig);
        locales.put(lang.locale, localeConfig);

        return localeConfig;
    }

    private @NotNull LangLocation bestMatch(@NotNull String locale, @Nullable LangLocation initial) {
        File file = new File(folder, locale + ".yml");
        InputStream bundled = plugin.getResource("locale/" + locale + ".yml");

        if (file.exists() || bundled != null) {
            return new LangLocation(locale, file, bundled);
        }

        if (initial == null) {
            initial = new LangLocation(locale, file, null);
        }

        int lastSeparator = locale.lastIndexOf('_');

        // Must be at least some content before separator.
        if (lastSeparator < 1) {
            return initial;
        }

        return bestMatch(locale.substring(0, lastSeparator), initial);
    }

    private @NotNull YamlConfiguration loadLocale(@NotNull LangLocation lang) {
        YamlConfiguration localeConfigDefaults;
        if (lang.bundled == null) {
            localeConfigDefaults = new YamlConfiguration();
        } else {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(lang.bundled, StandardCharsets.UTF_8))) {
                localeConfigDefaults = YamlConfiguration.loadConfiguration(reader);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, e, () -> "[LanguageManager] Unable to load resource " + lang.locale + ".yml");
                localeConfigDefaults = new YamlConfiguration();
            }
        }

        if (!lang.file.exists()) {
            // If the file does not exist on disk, save bundled defaults.
            try {
                localeConfigDefaults.save(lang.file);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, e, () -> "[LanguageManager] Unable to save resource " + lang.locale + ".yml");
            }
            // Return loaded bundled locale.
            return localeConfigDefaults;
        }

        // If the file does exist on disk, load it.
        YamlConfiguration localeConfig = YamlConfiguration.loadConfiguration(lang.file);
        // Check for missing translations from the bundled file.
        List<String> newKeys = getMissingKeys(localeConfigDefaults, localeConfig::isSet);

        if (newKeys.isEmpty()) {
            return localeConfig;
        }

        // Get guess section for missing keys.
        ConfigurationSection guess = localeConfig.getConfigurationSection("guess");

        for (String newKey : newKeys) {
            // Set all missing keys to defaults.
            localeConfig.set(newKey, localeConfigDefaults.get(newKey));

            // Delete relevant guess keys in case this is a new translation.
            if (guess != null) {
                guess.set(newKey, null);
            }
        }

        // If guess section is empty, delete it.
        if (guess != null && guess.getKeys(false).isEmpty()) {
            localeConfig.set("guess", null);
        }

        plugin.getLogger().info(() -> "[LanguageManager] Added new translation keys to " + lang.locale + ".yml: " + String.join(", ", newKeys));

        // Write new keys to disk.
        try {
            localeConfig.save(lang.file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, e, () -> "[LanguageManager] Unable to save resource " + lang.locale + ".yml");
        }

        return localeConfig;
    }

    private void addTranslationFallthrough(@NotNull LangLocation location, @NotNull YamlConfiguration localeConfig) {
        YamlConfiguration defaultLocaleConfig = locales.get(defaultLocale);

        // Get missing keys. Keys that already have a guess value are not new and don't need to trigger another write.
        List<String> missingKeys = getMissingKeys(
                defaultLocaleConfig,
                key -> localeConfig.isSet(key) || localeConfig.isSet("guess." + key));

        if (!missingKeys.isEmpty()) {
            // Set up guess section for missing keys.
            for (String key : missingKeys) {
                localeConfig.set("guess." + key, defaultLocaleConfig.get(key));
            }

            // Write modified guess section to disk.
            try {
                localeConfig.save(location.file);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, e, () -> "[LanguageManager] Unable to save resource " + location.locale + ".yml");
            }
        }

        // Fall through to default locale.
        localeConfig.setDefaults(defaultLocaleConfig);
    }

    private @NotNull List<String> getMissingKeys(
            @NotNull Configuration configurationDefault,
            @NotNull Predicate<String> nodeSetPredicate) {
        List<String> missingKeys = new ArrayList<>();
        for (String key : configurationDefault.getKeys(true)) {
            if (!configurationDefault.isConfigurationSection(key) && !nodeSetPredicate.test(key)) {
                // Missing keys are non-section keys that fail the predicate.
                missingKeys.add(key);
            }
        }
        return missingKeys;
    }

    public @Nullable String getValue(@NotNull String key, @Nullable String locale) {
        String value = getOrLoadLocale(locale == null ? defaultLocale : locale.toLowerCase(Locale.ENGLISH)).getString(key);
        if (value == null || value.isBlank()) {
            return null;
        }

        value = ChatColor.translateAlternateColorCodes('&', value);

        return value;
    }

    public @Nullable String getValue(@NotNull String key, @Nullable String locale, Replacement @NotNull ... replacements) {
        String value = getValue(key, locale);

        if (value == null) {
            return null;
        }

        for (Replacement replacement : replacements) {
            value = value.replace(replacement.placeholder(), replacement.value());
        }

        return value;
    }

    public @Nullable String getLocalizedMessage(@NotNull CommandSender sender, @NotNull String key) {
        return getValue(key, getLocale(sender));
    }

    public @Nullable String getLocalizedMessage(
        @NotNull CommandSender sender,
        @NotNull String key,
        Replacement @NotNull ... replacements) {
        return getValue(key, getLocale(sender), replacements);
    }

    private @NotNull String getLocale(@NotNull CommandSender sender) {
        if (sender instanceof Player) {
            return ((Player) sender).getLocale();
        } else {
            return plugin.getConfig().getString("settings.locale", "en");
        }
    }

    public void sendMessage(@NotNull CommandSender sender, @NotNull String key) {
        String message = getLocalizedMessage(sender, key);

        if (message != null && !message.isEmpty()) {
            sender.sendMessage(message);
        }
    }

    public void sendMessage(@NotNull CommandSender sender, @NotNull String key, Replacement @NotNull... replacements) {
        String message = getLocalizedMessage(sender, key, replacements);

        if (message != null && !message.isEmpty()) {
            sender.sendMessage(message);
        }
    }

    public void sendSystemMessage(@NotNull Player player, @NotNull String key) {
        String message = getLocalizedMessage(player, key);

        if (message == null) {
            return;
        }

        int newline = message.indexOf('\n');
        if (newline != -1) {
            // No newlines in action bar chat.
            message = message.substring(0, newline);
        }

        if (message.isEmpty()) {
            return;
        }

        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacy(message));
    }

    private record LangLocation(@NotNull String locale, @NotNull File file, @Nullable InputStream bundled) {}

}
