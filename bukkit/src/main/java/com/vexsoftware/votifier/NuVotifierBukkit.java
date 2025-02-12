/*
 * Copyright (C) 2012 Vex Software LLC
 * This file is part of Votifier.
 *
 * Votifier is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Votifier is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Votifier.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.vexsoftware.votifier;

import com.vexsoftware.votifier.commands.TestVoteCommand;
import com.vexsoftware.votifier.commands.VotifierReloadCommand;
import com.vexsoftware.votifier.model.Vote;
import com.vexsoftware.votifier.model.VotifierEvent;
import com.vexsoftware.votifier.net.VotifierServerBootstrap;
import com.vexsoftware.votifier.net.VotifierSession;
import com.vexsoftware.votifier.net.protocol.v1crypto.RSAIO;
import com.vexsoftware.votifier.net.protocol.v1crypto.RSAKeygen;
import com.vexsoftware.votifier.platform.JavaUtilLogger;
import com.vexsoftware.votifier.platform.LoggingAdapter;
import com.vexsoftware.votifier.platform.VotifierPlugin;
import com.vexsoftware.votifier.platform.forwarding.BukkitPluginMessagingForwardingSink;
import com.vexsoftware.votifier.platform.scheduler.BukkitScheduler;
import com.vexsoftware.votifier.platform.scheduler.VotifierScheduler;
import com.vexsoftware.votifier.support.forwarding.ForwardedVoteListener;
import com.vexsoftware.votifier.support.forwarding.ForwardingVoteSink;
import com.vexsoftware.votifier.support.forwarding.redis.RedisCredentials;
import com.vexsoftware.votifier.support.forwarding.redis.RedisForwardingSink;
import com.vexsoftware.votifier.util.IOUtil;
import com.vexsoftware.votifier.util.KeyCreator;
import com.vexsoftware.votifier.util.TokenUtil;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.Key;
import java.security.KeyPair;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Main plugin class for NuVotifier on Bukkit-based servers.
 * <p>
 * It initializes the Votifier server, loads the configuration (including RSA keys
 * and tokens), and sets up vote forwarding if configured.
 */
public class NuVotifierBukkit extends JavaPlugin implements VoteHandler, VotifierPlugin, ForwardedVoteListener {

    private static final int DEFAULT_PORT = 8192;
    private static final String DEFAULT_CHANNEL = "NuVotifier";
    private static final String CONFIG_FILE_NAME = "config.yml";
    private static final String RSA_FOLDER_NAME = "rsa";

    private VotifierServerBootstrap bootstrap;
    private KeyPair keyPair;
    private boolean debug;
    private final Map<String, Key> tokens = new HashMap<>();

    private ForwardingVoteSink forwardingMethod;
    private VotifierScheduler scheduler;
    private LoggingAdapter pluginLogger;
    private boolean isFolia;

    @Override
    public void onEnable() {
        // Register commands
        getCommand("nvreload").setExecutor(new VotifierReloadCommand(this));
        getCommand("testvote").setExecutor(new TestVoteCommand(this));

        // Load configuration, RSA keys, tokens and start the Votifier server
        if (!loadAndBind()) {
            gracefulExit();
            setEnabled(false);
        }
    }

    @Override
    public void onDisable() {
        halt();
        getLogger().info("Votifier disabled.");
    }

    /**
     * Loads the configuration, initializes RSA keys, tokens, vote forwarding, and starts the server.
     *
     * @return true if initialization was successful, false otherwise.
     */
    private boolean loadAndBind() {
        try {
            isFolia = isFoliaServer();
            if (isFolia) {
                getLogger().info("Using Folia; VotifierEvent will be fired asynchronously.");
            }

            this.scheduler = new BukkitScheduler(this);
            this.pluginLogger = new JavaUtilLogger(getLogger());

            if (!createDataFolder()) {
                throw new RuntimeException("Could not create data folder: " + getDataFolder());
            }

            File configFile = new File(getDataFolder(), CONFIG_FILE_NAME);
            String hostAddr = Bukkit.getServer().getIp();
            if (hostAddr.isEmpty()) {
                hostAddr = "0.0.0.0";
            }
            if (!configFile.exists()) {
                createDefaultConfig(configFile, hostAddr);
            }
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);

            File rsaDirectory = new File(getDataFolder(), RSA_FOLDER_NAME);
            this.keyPair = initializeRSAKeys(rsaDirectory);

            // If "quiet" is set in config, then disable debug output.
            debug = cfg.isBoolean("quiet") ? !cfg.getBoolean("quiet") : cfg.getBoolean("debug", true);
            if (!debug) {
                getLogger().info("QUIET mode enabled.");
            }

            initializeTokens(cfg, configFile);

            final String host = cfg.getString("host", hostAddr);
            final int port = cfg.getInt("port", DEFAULT_PORT);
            if (port >= 0) {
                boolean disableV1 = cfg.getBoolean("disable-v1-protocol");
                if (disableV1) {
                    getLogger().info("------------------------------------------------------------------------------");
                    getLogger().info("Votifier v1 protocol has been disabled. Many voting sites do not support");
                    getLogger().info("the modern NuVotifier protocol.");
                    getLogger().info("------------------------------------------------------------------------------");
                }
                bootstrap = new VotifierServerBootstrap(host, port, this, disableV1);
                bootstrap.start(error -> { /* Startup errors are ignored */ });
            } else {
                getLogger().info("------------------------------------------------------------------------------");
                getLogger().info("Votifier port is less than 0, so the vote server will not be started.");
                getLogger().info("Only forwarded votes will be listened to.");
                getLogger().info("------------------------------------------------------------------------------");
            }

            ConfigurationSection forwardingConfig = cfg.getConfigurationSection("forwarding");
            if (forwardingConfig != null) {
                return initializeForwarding(forwardingConfig);
            } else {
                getLogger().info("No vote forwarding has been configured. Using the default implementation (noop).");
            }

            return true;
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Error initializing Votifier", ex);
            return false;
        }
    }

    /**
     * Checks if the server is running Folia by attempting to load a specific class.
     *
     * @return true if Folia is detected, false otherwise.
     */
    private boolean isFoliaServer() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Creates the data folder if it does not exist.
     *
     * @return true if the data folder exists or was created successfully.
     */
    private boolean createDataFolder() {
        if (!getDataFolder().exists()) {
            return getDataFolder().mkdir();
        }
        return true;
    }

    /**
     * Creates the default configuration file using a bundled resource.
     *
     * @param configFile The configuration file to create.
     * @param hostAddr   The default host IP address.
     * @throws IOException if an error occurs during file creation or copying.
     */
    private void createDefaultConfig(File configFile, String hostAddr) throws IOException {
        getLogger().info("Setting up Votifier for the first time...");
        if (!configFile.createNewFile()) {
            throw new IOException("Could not create configuration file at " + configFile);
        }
        try (InputStream defaults = getResource("bukkitConfig.yml")) {
            if (defaults == null) {
                throw new IOException("Could not retrieve the default configuration file!");
            }
            String cfgStr = new String(IOUtil.readAllBytes(defaults), StandardCharsets.UTF_8);
            String token = TokenUtil.newToken();
            cfgStr = cfgStr.replace("%default_token%", token).replace("%ip%", hostAddr);

            Files.copy(
                    new ByteArrayInputStream(cfgStr.getBytes(StandardCharsets.UTF_8)),
                    configFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );

            getLogger().info("------------------------------------------------------------------------------");
            getLogger().info("Votifier assigned to port " + DEFAULT_PORT + ". If your server is on shared hosting,");
            getLogger().info("make sure that this port is available. Your hosting provider may assign a different port,");
            getLogger().info("which you will need to update in config.yml.");
            getLogger().info("------------------------------------------------------------------------------");
            getLogger().info("The default Votifier token is '" + token + "'. This token will be used when submitting your server");
            getLogger().info("to a voting list.");
            getLogger().info("------------------------------------------------------------------------------");
        }
    }

    /**
     * Initializes the RSA keys.
     * <p>
     * If no RSA keys exist in the designated folder, new keys will be generated.
     *
     * @param rsaDirectory The directory where RSA keys are stored.
     * @return The loaded or newly generated RSA key pair.
     * @throws Exception if the RSA directory cannot be created or keys cannot be loaded/generated.
     */
    private KeyPair initializeRSAKeys(File rsaDirectory) throws Exception {
        if (!rsaDirectory.exists() && !rsaDirectory.mkdir()) {
            throw new RuntimeException("Could not create RSA directory: " + rsaDirectory);
        }

        if (!rsaDirectory.exists() || Objects.requireNonNull(rsaDirectory.list()).length == 0) {
            KeyPair keyPair = RSAKeygen.generate(2048);
            RSAIO.save(rsaDirectory, keyPair);
            return keyPair;
        } else {
            return RSAIO.load(rsaDirectory);
        }
    }

    /**
     * Loads tokens from the configuration.
     * <p>
     * If no tokens are found, a default token is generated, stored in the config, and logged.
     *
     * @param cfg        The configuration.
     * @param configFile The configuration file to save to if a token is generated.
     * @throws IOException if an error occurs while saving the configuration.
     */
    private void initializeTokens(YamlConfiguration cfg, File configFile) throws IOException {
        ConfigurationSection tokenSection = cfg.getConfigurationSection("tokens");
        if (tokenSection != null) {
            tokenSection.getValues(false).forEach((website, value) -> {
                tokens.put(website, KeyCreator.createKeyFrom(value.toString()));
                getLogger().info("Token loaded for site: " + website);
            });
        } else {
            String token = TokenUtil.newToken();
            tokenSection = cfg.createSection("tokens");
            tokenSection.set("default", token);
            tokens.put("default", KeyCreator.createKeyFrom(token));
            cfg.save(configFile);

            getLogger().info("------------------------------------------------------------------------------");
            getLogger().info("No tokens were found in the configuration; a default one has been generated.");
            getLogger().info("The default Votifier token is: " + token + ".");
            getLogger().info("You must provide this token when submitting your server to a voting list.");
            getLogger().info("------------------------------------------------------------------------------");
        }
    }

    /**
     * Initializes vote forwarding based on the configuration.
     *
     * @param forwardingConfig The forwarding configuration section.
     * @return true if the forwarding was initialized successfully, false otherwise.
     */
    private boolean initializeForwarding(ConfigurationSection forwardingConfig) {
        String method = forwardingConfig.getString("method", "none");
        switch (method) {
            case "none":
                getLogger().info("Forwarding method 'none' selected: forwarded votes will not be received.");
                return true;
            case "pluginmessaging": {
                String channel = forwardingConfig.getString("pluginMessaging.channel", DEFAULT_CHANNEL);
                try {
                    forwardingMethod = new BukkitPluginMessagingForwardingSink(this, channel, this, pluginLogger);
                    forwardingMethod.init();
                    return true;
                } catch (RuntimeException ex) {
                    getLogger().log(Level.SEVERE, "Could not set up plugin messaging for vote forwarding", ex);
                    return false;
                }
            }
            case "redis": {
                ConfigurationSection redisSection = forwardingConfig.getConfigurationSection("redis");
                if (redisSection == null) {
                    getLogger().severe("Missing 'redis' section in the forwarding configuration.");
                    return false;
                }
                String channel = redisSection.getString("channel");
                forwardingMethod = new RedisForwardingSink(
                        RedisCredentials.builder()
                                .host(redisSection.getString("address"))
                                .port(redisSection.getInt("port"))
                                .username(redisSection.getString("username"))
                                .password(redisSection.getString("password"))
                                .uri(redisSection.getString("uri"))
                                .channel(channel)
                                .build(),
                        this,
                        pluginLogger
                );
                try {
                    forwardingMethod.init();
                } catch (RuntimeException ex) {
                    getLogger().log(Level.SEVERE, "Could not configure Redis for forwarding", ex);
                    return false;
                }
                return true;
            }
            default:
                getLogger().severe("Unknown forwarding method '" + method + "'. The default implementation (noop) will be used.");
                return false;
        }
    }

    /**
     * Shuts down the vote server and the forwarding method if they are active.
     */
    private void halt() {
        if (bootstrap != null) {
            bootstrap.shutdown();
            bootstrap = null;
        }
        if (forwardingMethod != null) {
            forwardingMethod.halt();
            forwardingMethod = null;
        }
    }

    /**
     * Reloads the Votifier configuration and reinitializes the server.
     *
     * @return true if the reload was successful, false otherwise.
     */
    public boolean reload() {
        try {
            halt();
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "On halt, an exception was thrown. This may be fine!", ex);
        }

        if (loadAndBind()) {
            getLogger().info("Reload was successful.");
            return true;
        } else {
            try {
                halt();
                getLogger().log(Level.SEVERE,
                    "On reload, there was a problem with the configuration. Votifier currently does nothing!");
            } catch (Exception ex) {
                getLogger().log(Level.SEVERE,
                    "On reload, there was a problem loading, and we could not re-halt the server. Votifier is in an unstable state!", ex);
            }

            return false;
        }
    }

    private void gracefulExit() {
        getLogger().log(Level.SEVERE, "Votifier did not initialize properly!");
    }

    @Override
    public LoggingAdapter getPluginLogger() {
        return pluginLogger;
    }

    @Override
    public VotifierScheduler getScheduler() {
        return scheduler;
    }

    @Override
    public Map<String, Key> getTokens() {
        return tokens;
    }

    @Override
    public KeyPair getProtocolV1Key() {
        return keyPair;
    }

    public boolean isDebug() {
        return debug;
    }

    @Override
    public void onVoteReceived(final Vote vote, VotifierSession.ProtocolVersion protocolVersion, String remoteAddress) {
        if (debug) {
            getLogger().info("Received vote " + protocolVersion.humanReadable + " from " + remoteAddress + " -> " + vote);
        }

        fireVotifierEvent(vote);
    }

    @Override
    public void onError(Throwable throwable, boolean alreadyHandledVote, String remoteAddress) {
        if (debug) {
            if (alreadyHandledVote) {
                getLogger().log(Level.WARNING, "Vote processed, but an exception occurred for the vote from " + remoteAddress, throwable);
            } else {
                getLogger().log(Level.WARNING, "Unable to process vote from " + remoteAddress, throwable);
            }
        } else if (!alreadyHandledVote) {
            getLogger().log(Level.WARNING, "Unable to process vote from " + remoteAddress);
        }
    }

    @Override
    public void onForward(final Vote vote) {
        if (debug) {
            getLogger().info("Got a forwarded vote -> " + vote);
        }
        fireVotifierEvent(vote);
    }

    /**
     * Fires a VotifierEvent for the given vote.
     * <p>
     * If there are no registered listeners, a severe warning is logged. Additionally, if the
     * configuration is set to ignore offline votes and the player is offline, the vote is skipped.
     *
     * @param vote The vote to process.
     */
    private void fireVotifierEvent(Vote vote) {
        if (VotifierEvent.getHandlerList().getRegisteredListeners().length == 0) {
            getLogger().severe("A vote was received, but no listeners are registered to handle it.");
            getLogger().severe("Visit https://github.com/NuVotifier/NuVotifier/wiki/Setup-Guide#vote-listeners for a list of configurable listeners.");
        }

        if (getConfig().getBoolean("ignore-offline-votes", false)) {
            Player player = Bukkit.getPlayer(vote.getUsername());
            if (player == null) {
                if (debug) {
                    getLogger().warning("Player " + vote.getUsername() + " is not online. The vote will be ignored.");
                }

                return;
            }
        }

        if (!isFolia) {
            getServer().getScheduler().runTask(this, () ->
                    getServer().getPluginManager().callEvent(new VotifierEvent(vote))
            );
        } else {
            getServer().getScheduler().runTaskAsynchronously(this, () ->
                    getServer().getPluginManager().callEvent(new VotifierEvent(vote, true))
            );
        }
    }
}
