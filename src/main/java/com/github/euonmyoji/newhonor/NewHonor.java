package com.github.euonmyoji.newhonor;

import com.github.euonmyoji.newhonor.api.event.NewHonorReloadEvent;
import com.github.euonmyoji.newhonor.command.HonorCommand;
import com.github.euonmyoji.newhonor.configuration.*;
import com.github.euonmyoji.newhonor.listener.NewHonorMessageListener;
import com.github.euonmyoji.newhonor.listener.UltimateChatEventListener;
import com.github.euonmyoji.newhonor.task.EffectsOffer;
import com.github.euonmyoji.newhonor.task.HaloEffectsOffer;
import com.github.euonmyoji.newhonor.task.TaskManager;
import com.google.common.base.Charsets;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.EventManager;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.entity.living.humanoid.player.RespawnPlayerEvent;
import org.spongepowered.api.event.game.state.GameStartedServerEvent;
import org.spongepowered.api.event.game.state.GameStartingServerEvent;
import org.spongepowered.api.event.network.ClientConnectionEvent;
import org.spongepowered.api.plugin.Dependency;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.text.Text;
import org.spongepowered.plugin.meta.version.ComparableVersion;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

/**
 * @author yinyangshi
 */
@Plugin(id = "newhonor", name = "New Honor", version = NewHonor.VERSION, authors = "yinyangshi", description = "NewHonor plugin",
        dependencies = {@Dependency(id = "ultimatechat", optional = true), @Dependency(id = "placeholderapi", optional = true),
                @Dependency(id = "nucleus", optional = true)})
public class NewHonor {
    public static final String VERSION = "2.0.0-pre-b10";
    public static final NewHonorMessageChannel M_MESSAGE = new NewHonorMessageChannel();
    public static final Object DATA_LOCK = new Object();
    @Inject
    @ConfigDir(sharedRoot = false)
    private Path cfgDir;

    @Inject
    public Logger logger;
    @Inject
    private PluginContainer pluginContainer;

    public static NewHonor plugin;
    public final HashMap<UUID, Text> honorTextCache = new HashMap<>();
    public final HashMap<UUID, String> playerUsingEffectCache = new HashMap<>();

    private static final String OLD_COMPATIBLE_UCHAT_NODE_PATH = "compatibleUChat";
    private static final String OLD_USE_PAPI_NODE_PATH = "usePAPI";
    private static final String DISPLAY_HONOR_NODE_PATH = "displayHonor";
    private static final String FORCE_ENABLE_DEFAULT_LISTENER = "force-enable-default-listener";

    private final UltimateChatEventListener UChatListener = new UltimateChatEventListener();
    private final NewHonorMessageListener NewHonorListener = new NewHonorMessageListener();

    private boolean enabledPlaceHolderAPI = false;
    private boolean hookedNucleus = false;
    private boolean hookedUChat = false;

    @Listener
    public void onStarting(GameStartingServerEvent event) {
        plugin = this;
        try {
            NewHonorConfig.defaultCfgDir = cfgDir;
            if (Files.notExists(cfgDir)) {
                Files.createDirectory(cfgDir);
            }
            cfgDir = null;
            NewHonorConfig.init();
            if (Files.notExists(NewHonorConfig.cfgDir)) {
                Files.createDirectory(NewHonorConfig.cfgDir);
            }
            final String playerData = "PlayerData";
            if (Files.notExists(NewHonorConfig.cfgDir.resolve(playerData))) {
                Files.createDirectory(NewHonorConfig.cfgDir.resolve(playerData));
            }
            if (NewHonorConfig.isCheckUpdate()) {
                checkUpdate();
            } else {
                logger.info("check update was canceled");
            }

            //已经不在使用的N个配置文件node
            NewHonorConfig.getCfg().removeChild(OLD_USE_PAPI_NODE_PATH);
            NewHonorConfig.getCfg().removeChild(OLD_COMPATIBLE_UCHAT_NODE_PATH);
            NewHonorConfig.getCfg().removeChild("nucleus-placeholder");

            NewHonorConfig.getCfg().getNode(DISPLAY_HONOR_NODE_PATH)
                    .setValue(NewHonorConfig.getCfg().getNode(DISPLAY_HONOR_NODE_PATH).getBoolean(false));
            NewHonorConfig.getCfg().getNode(FORCE_ENABLE_DEFAULT_LISTENER)
                    .setValue(NewHonorConfig.getCfg().getNode(FORCE_ENABLE_DEFAULT_LISTENER).getBoolean(false));
            NewHonorConfig.save();
            LanguageManager.reload();
            SqlManager.init();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void checkUpdate() {
        Task.builder().async().name("NewHonor - check for update").execute(() -> {
            try {
                URL url = new URL("https://api.github.com/repos/euOnmyoji/NewHonor-plugin-for-sponge/releases");
                HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.getResponseCode();
                try (InputStreamReader reader = new InputStreamReader(connection.getInputStream(), Charsets.UTF_8)) {
                    JsonObject jsonObject = new JsonParser().parse(reader).getAsJsonArray().get(0).getAsJsonObject();
                    String version = jsonObject.get("tag_name").getAsString().replace("v", "")
                            .replace("version", "");
                    int c = new ComparableVersion(version).compareTo(new ComparableVersion(VERSION));
                    if (c > 0) {
                        logger.info("found a latest version:" + version + ".Your version now:" + VERSION);
                    } else if (c < 0) {
                        logger.info("the latest version in com.github:" + version + "[Your version:" + VERSION + "]");
                    }
                }
            } catch (Exception e) {
                logger.info("check for updating failed");
            }
        }).submit(this);
    }

    @Inject
    private Metrics metrics;

    @Listener
    public void onStarted(GameStartedServerEvent event) {
        Sponge.getCommandManager().register(this, HonorCommand.honor, "honor", "honour");
        logger.info("NewHonor author email:1418780411@qq.com");
        hook();
        try {
            TaskManager.update();
        } catch (IOException e) {
            logger.warn("Task init error", e);
        }
        metrics.addCustomChart(new Metrics.SimplePie("useeffects", () -> EffectsOffer.TASK_DATA.size() > 0 ? "true" : "false"));
        metrics.addCustomChart(new Metrics.SimplePie("displayhonor", () -> ScoreBoardManager.enable ? "true" : "false"));
        metrics.addCustomChart(new Metrics.SimplePie("usepapi",
                () -> enabledPlaceHolderAPI ? "true" : "false"));
        metrics.addCustomChart(new Metrics.SimplePie("usehaloeffects", () -> HaloEffectsOffer.TASK_DATA.size() > 0 ?
                "true" : "false"));
        metrics.addCustomChart(new Metrics.SimplePie("usenucleus", () -> hookedNucleus ? "true" : "false"));
    }

    @Listener
    public void onClientConnectionJoin(ClientConnectionEvent.Join event) {
        Player p = event.getTargetEntity();
        Task.builder().execute(() -> {
            try {
                PlayerConfig pd = PlayerConfig.get(p);
                pd.init();
                doSomething(pd);
            } catch (Throwable e) {
                logger.error("error while init player", e);
            }
        }).async().name("newhonor - init Player" + p.getName()).submit(this);
    }

    @Listener
    public void onPlayerDie(RespawnPlayerEvent event) {
        Player p = event.getTargetEntity();
        Task.builder().execute(() -> {
            try {
                doSomething(PlayerConfig.get(p));
            } catch (Throwable e) {
                logger.error("error while init player", e);
            }
        }).async().name("newhonor - (die) init Player" + p.getName()).submit(this);
    }


    public static void clearCaches() {
        synchronized (DATA_LOCK) {
            plugin.honorTextCache.clear();
            plugin.playerUsingEffectCache.clear();
            EffectsOffer.TASK_DATA.clear();
        }
    }

    /**
     * 各种兼容 额外模式开启?
     */
    private void hook() {
        EventManager eventManager = Sponge.getEventManager();
        eventManager.unregisterListeners(UChatListener);
        eventManager.unregisterListeners(NewHonorListener);
        ScoreBoardManager.enable = false;
        ScoreBoardManager.clear();

        boolean enableDefault = true;
        //hook nucleus
        try {
            Class.forName("io.github.nucleuspowered.nucleus.api.NucleusAPI");
            NucleusManager.doIt();
            enableDefault = false;
            if (!hookedNucleus) {
                logger.info("hooked nucleus");
                logger.info("default listener is disabling, please use {{pl:newhonor:newhonor}} to show honor in chat");
                logger.info("发现nucleus插件，请在nucleus配置里面的chat里面使用变量{{pl:newhonor:newhonor}}来在聊天栏显示头衔");
            }
            hookedNucleus = true;
        } catch (ClassNotFoundException ignore) {
        }

        //hook PAPI
        try {
            PlaceHolderManager.create();
            if (!enableDefault) {
                logger.info("hooked PAPI, you can use '%newhonor%' now.");
            }
            enabledPlaceHolderAPI = true;
        } catch (RuntimeException ignore) {
        }

        //hook UChat
        try {
            Class.forName("br.net.fabiozumbi12.UltimateChat.Sponge.API.SendChannelMessageEvent");
            Sponge.getEventManager().registerListeners(this, UChatListener);
            if (!hookedUChat) {
                logger.info("hooked UChat");
                logger.info("please use {newhonor} in UChat config to show honor in the chat");
            }
            hookedUChat = true;
            enableDefault = false;
        } catch (ClassNotFoundException ignore) {
        }

        if (NewHonorConfig.getCfg().getNode(DISPLAY_HONOR_NODE_PATH).getBoolean(false)) {
            ScoreBoardManager.enable = true;
            ScoreBoardManager.init();
            logger.info("displayHonor mode enabled");
            logger.info("if you find honor shows twice, try to change config in nucleus(overwrite-early-prefix = true)");
            logger.info("如果发现头衔重复显示，请尝试在nucleus配置文件里面将overwrite-early-prefix设置为true");
            if (NewHonorConfig.getCfg().getNode(FORCE_ENABLE_DEFAULT_LISTENER).getBoolean() && enableDefault) {
                Sponge.getEventManager().registerListeners(this, NewHonorListener);
            }
        } else if (enableDefault) {
            Sponge.getEventManager().registerListeners(this, NewHonorListener);
        }
    }

    public void reload() {
        Sponge.getEventManager().post(new NewHonorReloadEvent());
        synchronized (DATA_LOCK) {
            NewHonorConfig.reload();
            LanguageManager.reload();
            HonorConfig.reload();
            try {
                TaskManager.update();
            } catch (IOException e) {
                logger.warn("reload error!", e);
            }
        }

        NewHonor.plugin.hook();
    }

    public static void doSomething(PlayerConfig pd) {
        Runnable r = () -> {
            synchronized (DATA_LOCK) {
                try {
                    pd.checkUsingHonor();
                    plugin.playerUsingEffectCache.remove(pd.getUUID());
                    plugin.honorTextCache.remove(pd.getUUID());
                    if (pd.isUseHonor()) {
                        pd.getUsingHonorText().ifPresent(text -> plugin.honorTextCache.put(pd.getUUID(), text));
                        if (pd.isEnableEffects()) {
                            HonorConfig.getEffectsID(pd.getUsingHonorID()).ifPresent(s -> plugin.playerUsingEffectCache.put(pd.getUUID(), s));
                        }
                    }
                } catch (Exception e) {
                    plugin.logger.error("error about data!", e);
                }
            }
        };
        Optional<Runnable> r2 = Sponge.getServer().getPlayer(pd.getUUID()).map(player -> () -> ScoreBoardManager.initPlayer(player));

        //r为插件数据修改 异步(有mysql) r2为玩家自身数据修改 可能不存在需要运行的 需要同步
        if (Sponge.getServer().isMainThread()) {
            Task.builder().execute(r).async().name("NewHonor - do something with playerdata " + pd.hashCode()).submit(plugin);
            r2.ifPresent(Runnable::run);
        } else {
            r.run();
            r2.ifPresent(runnable -> Task.builder().execute(runnable).submit(plugin));
        }
    }

    static PluginContainer container() {
        return plugin.pluginContainer;
    }
}
